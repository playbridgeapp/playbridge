package com.playbridge.player.player

import com.playbridge.player.proxy.ReceiverStreamProxy
import com.playbridge.player.proxy.RegisteredMedia
import com.playbridge.shared.network.MediaNetworkPolicy
import playbridge.PlayPayload
import java.util.Locale

internal data class SubtitleFetchTarget(
    val fetchUrl: String,
    val headers: Map<String, String>?,
    val trustedLocalProxyOrigin: String?,
)

internal interface PageProxyClient {
    val isSupported: Boolean
    val trustedOrigin: String?
    suspend fun ensureStarted(): String
    suspend fun registerUrl(
        url: String,
        headers: Map<String, String>,
        contentType: String?,
        allowedPrivateOrigins: List<String>,
        remoteOrigin: Boolean,
    ): RegisteredMedia
    fun retainId(id: String): AutoCloseable?
}

internal object ReceiverStreamProxyClient : PageProxyClient {
    override val isSupported: Boolean
        get() = ReceiverStreamProxy.isSupported()

    override val trustedOrigin: String?
        get() = ReceiverStreamProxy.currentTrustedOrigin()

    override suspend fun ensureStarted(): String {
        val proxy = ReceiverStreamProxy.get()
            ?: throw IllegalStateException("Local media proxy is unavailable")
        return proxy.trustedOrigin
            ?: throw IllegalStateException("Local media proxy has no loopback origin")
    }

    override suspend fun registerUrl(
        url: String,
        headers: Map<String, String>,
        contentType: String?,
        allowedPrivateOrigins: List<String>,
        remoteOrigin: Boolean,
    ): RegisteredMedia {
        val proxy = ReceiverStreamProxy.get()
            ?: throw IllegalStateException("Local media proxy is unavailable")
        return proxy.registerUrl(
            url = url,
            headers = headers,
            contentType = contentType,
            allowedPrivateOrigins = allowedPrivateOrigins,
            remoteOrigin = remoteOrigin,
        )
    }

    override fun retainId(id: String): AutoCloseable? =
        ReceiverStreamProxy.retainIdIfRunning(id)
}

/**
 * Routes page-controlled media (`page_cast` and `linked_page`) and all of its subtitles through
 * the loopback Rust stream proxy while leaving non-page items untouched.
 *
 * Fails closed: if proxy startup, policy validation, or URL registration fails for a page item,
 * callers receive a failure result and never receive a direct upstream URL. A page subtitle that
 * fails is dropped and the item plays without it, as on tvOS; it is never fetched directly.
 */
internal class PageMediaProxyRouter(
    private val proxyClient: PageProxyClient = ReceiverStreamProxyClient,
) : AutoCloseable {

    internal class PreparedPageItem internal constructor(
        val canonicalItem: PlayPayload,
        val proxyOrigin: String?,
        val proxiedMediaUrl: String,
        private val initialSavedExternalSubtitle: String?,
        subtitleMappings: Map<String, String>,
        leases: List<AutoCloseable>,
    ) : AutoCloseable {
        private val lock = Any()
        private val subtitleUrlsByCanonical = LinkedHashMap<String, String>(subtitleMappings)
        private val activeLeases = ArrayList<AutoCloseable>(leases)
        private var closed = false

        val isPageControlled: Boolean
            get() = canonicalItem.isPageControlledMedia()

        fun matches(item: PlayPayload, savedExternalSubtitleUrl: String?): Boolean =
            synchronized(lock) {
                !closed &&
                    canonicalItem == item &&
                    initialSavedExternalSubtitle == savedExternalSubtitleUrl &&
                    (!isPageControlled || proxyOrigin != null)
            }

        fun enginePayload(rendererKind: RendererKind): PlayPayload {
            if (!isPageControlled) return canonicalItem
            val dash = isDashPayload(canonicalItem)
            val hls = isHlsPayload(canonicalItem)
            val deliveryUrl = if (dash && rendererKind == RendererKind.MPV) {
                ReceiverStreamProxy.mpvDashUrl(proxiedMediaUrl)
            } else {
                proxiedMediaUrl
            }
            val deliveryContentType = when {
                dash && rendererKind == RendererKind.EXO && canonicalItem.content_type.isNullOrBlank() ->
                    "application/dash+xml"
                hls && canonicalItem.content_type.isNullOrBlank() &&
                    !proxiedMediaUrl.contains(".m3u8", ignoreCase = true) ->
                    "application/x-mpegURL"
                else -> canonicalItem.content_type
            }
            val proxiedSubtitles = synchronized(lock) {
                canonicalItem.externalSubtitleUrls().mapNotNull { subtitleProxyUrlLocked(it) }
            }
            return canonicalItem.copy(
                url = deliveryUrl,
                headers = emptyMap(),
                subtitles = proxiedSubtitles,
                subtitle_resources = emptyList(),
                content_type = deliveryContentType,
            )
        }

        fun presentationPayload(): PlayPayload = enginePayload(RendererKind.EXO)

        fun resolveSubtitleFetch(
            canonicalUrl: String,
            fallbackHeaders: Map<String, String>?,
        ): SubtitleFetchTarget? {
            if (!isPageControlled) {
                return SubtitleFetchTarget(
                    fetchUrl = canonicalUrl,
                    headers = fallbackHeaders,
                    trustedLocalProxyOrigin = null,
                )
            }
            val origin = proxyOrigin ?: return null
            val proxied = synchronized(lock) { subtitleProxyUrlLocked(canonicalUrl) } ?: return null
            return SubtitleFetchTarget(
                fetchUrl = proxied,
                headers = emptyMap(),
                trustedLocalProxyOrigin = origin,
            )
        }

        internal fun recordLateSubtitle(
            canonicalUrl: String,
            proxiedUrl: String,
            lease: AutoCloseable?,
        ): Boolean = synchronized(lock) {
            if (closed) {
                runCatching { lease?.close() }
                return false
            }
            putSubtitleMapping(subtitleUrlsByCanonical, canonicalUrl, proxiedUrl)
            if (lease != null) activeLeases += lease
            true
        }

        private fun subtitleProxyUrlLocked(canonicalUrl: String): String? =
            subtitleUrlsByCanonical[canonicalUrl]
                ?: subtitleUrlsByCanonical[canonicalUrl.substringBefore('#')]

        override fun close() {
            val leasesToClose = synchronized(lock) {
                if (closed) return
                closed = true
                subtitleUrlsByCanonical.clear()
                activeLeases.toList().also { activeLeases.clear() }
            }
            leasesToClose.forEach { runCatching { it.close() } }
        }
    }

    private val stateLock = Any()
    @Volatile
    private var activePrepared: PreparedPageItem? = null

    fun isProxyAvailable(): Boolean = proxyClient.isSupported

    fun currentPrepared(): PreparedPageItem? = activePrepared

    fun currentTrustedProxyOrigin(): String? = activePrepared?.proxyOrigin

    /**
     * Synchronous validation for queue admission (`QueueAdd`) so page-controlled items cannot
     * enter an active queue when the local proxy is unavailable or their media URL/headers are
     * invalid. Subtitles never block admission; refused ones are dropped in [prepareItem].
     */
    fun canAcceptItem(item: PlayPayload): Boolean {
        if (!item.isPageControlledMedia()) return true
        if (!proxyClient.isSupported) return false
        return validatePageRegistrationInput(
            rawUrl = item.url,
            headers = item.headers,
            allowedPrivateOrigins = item.allowed_private_origins,
        ).isSuccess
    }

    suspend fun prepareItem(
        item: PlayPayload,
        savedExternalSubtitleUrl: String? = null,
    ): Result<PreparedPageItem> {
        val normalizedSavedSub = savedExternalSubtitleUrl?.takeIf(String::isNotBlank)
        synchronized(stateLock) {
            val existing = activePrepared
            if (existing != null && existing.matches(item, normalizedSavedSub)) {
                return Result.success(existing)
            }
        }

        if (!item.isPageControlledMedia()) {
            val direct = PreparedPageItem(
                canonicalItem = item,
                proxyOrigin = null,
                proxiedMediaUrl = item.url,
                initialSavedExternalSubtitle = normalizedSavedSub,
                subtitleMappings = emptyMap(),
                leases = emptyList(),
            )
            swapActivePrepared(direct)
            return Result.success(direct)
        }

        if (!proxyClient.isSupported) {
            clearActiveItem()
            return Result.failure(
                IllegalStateException("Local media proxy is unavailable for webpage media"),
            )
        }

        validatePageRegistrationInput(
            rawUrl = item.url,
            headers = item.headers,
            allowedPrivateOrigins = item.allowed_private_origins,
        ).onFailure { error ->
            clearActiveItem()
            return Result.failure(error)
        }

        val acquiredLeases = ArrayList<AutoCloseable>()
        return try {
            val origin = MediaNetworkPolicy.normalizeTrustedLocalProxyOrigin(
                proxyClient.ensureStarted(),
            ) ?: throw IllegalStateException("Local media proxy bound to an untrusted origin")

            val mediaReg = registerCheckedUrl(
                origin = origin,
                rawUrl = item.url,
                headers = item.headers,
                contentType = item.content_type,
                allowedPrivateOrigins = item.allowed_private_origins,
                leasesOut = acquiredLeases,
            )

            val subtitleMappings = LinkedHashMap<String, String>()
            var registeredSubCount = 0

            for (subUrl in item.subtitles.filter(String::isNotBlank)) {
                if (registeredSubCount >= MAX_PAGE_SUBTITLES) break
                val requestSubUrl = subUrl.substringBefore('#')
                val subHeaders = if (MediaNetworkPolicy.sameOrigin(
                        item.url.substringBefore('#'),
                        requestSubUrl,
                    )
                ) {
                    item.headers
                } else {
                    emptyMap()
                }
                val reg = registerSubtitleOrNull(
                    origin = origin,
                    rawUrl = subUrl,
                    headers = subHeaders,
                    allowedPrivateOrigins = item.allowed_private_origins,
                    leasesOut = acquiredLeases,
                ) ?: continue
                putSubtitleMapping(subtitleMappings, subUrl, reg.url)
                registeredSubCount++
            }

            for (resource in item.subtitle_resources.filter { it.url.isNotBlank() }) {
                if (registeredSubCount >= MAX_PAGE_SUBTITLES) break
                if (subtitleMappings.containsKey(resource.url)) continue
                val reg = registerSubtitleOrNull(
                    origin = origin,
                    rawUrl = resource.url,
                    headers = resource.headers,
                    allowedPrivateOrigins = item.allowed_private_origins,
                    leasesOut = acquiredLeases,
                ) ?: continue
                putSubtitleMapping(subtitleMappings, resource.url, reg.url)
                registeredSubCount++
            }

            if (normalizedSavedSub != null &&
                registeredSubCount < MAX_PAGE_SUBTITLES &&
                !subtitleMappings.containsKey(normalizedSavedSub) &&
                !subtitleMappings.containsKey(normalizedSavedSub.substringBefore('#'))
            ) {
                registerSubtitleOrNull(
                    origin = origin,
                    rawUrl = normalizedSavedSub,
                    headers = item.headersForSubtitle(normalizedSavedSub),
                    allowedPrivateOrigins = item.allowed_private_origins,
                    leasesOut = acquiredLeases,
                )?.let { reg -> putSubtitleMapping(subtitleMappings, normalizedSavedSub, reg.url) }
            }

            val prepared = PreparedPageItem(
                canonicalItem = item,
                proxyOrigin = origin,
                proxiedMediaUrl = mediaReg.url,
                initialSavedExternalSubtitle = normalizedSavedSub,
                subtitleMappings = subtitleMappings,
                leases = acquiredLeases,
            )
            swapActivePrepared(prepared)
            Result.success(prepared)
        } catch (error: kotlinx.coroutines.CancellationException) {
            acquiredLeases.forEach { runCatching { it.close() } }
            throw error
        } catch (error: Throwable) {
            acquiredLeases.forEach { runCatching { it.close() } }
            clearActiveItem()
            Result.failure(error)
        }
    }

    suspend fun registerLateSubtitle(
        item: PlayPayload,
        subtitleUrl: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
    ): Result<String> {
        if (subtitleUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("Subtitle URL is blank"))
        }
        if (!item.isPageControlledMedia()) {
            return Result.success(subtitleUrl)
        }
        val active = activePrepared
        if (active == null || active.canonicalItem != item || !active.isPageControlled) {
            return Result.failure(
                IllegalStateException("Active webpage media session is not prepared"),
            )
        }
        active.resolveSubtitleFetch(subtitleUrl, headers)?.let { existing ->
            return Result.success(existing.fetchUrl)
        }
        if (!proxyClient.isSupported) {
            return Result.failure(
                IllegalStateException("Local media proxy is unavailable for webpage subtitle"),
            )
        }

        val acquiredLeases = ArrayList<AutoCloseable>()
        return try {
            val origin = active.proxyOrigin
                ?.let(MediaNetworkPolicy::normalizeTrustedLocalProxyOrigin)
                ?: MediaNetworkPolicy.normalizeTrustedLocalProxyOrigin(proxyClient.ensureStarted())
                ?: throw IllegalStateException("Local media proxy bound to an untrusted origin")

            val reg = registerCheckedUrl(
                origin = origin,
                rawUrl = subtitleUrl,
                headers = headers,
                contentType = contentType,
                allowedPrivateOrigins = item.allowed_private_origins,
                leasesOut = acquiredLeases,
            )
            val lease = acquiredLeases.singleOrNull()
            if (!active.recordLateSubtitle(subtitleUrl, reg.url, lease)) {
                throw IllegalStateException("Webpage media session closed before subtitle attached")
            }
            Result.success(reg.url)
        } catch (error: kotlinx.coroutines.CancellationException) {
            acquiredLeases.forEach { runCatching { it.close() } }
            throw error
        } catch (error: Throwable) {
            acquiredLeases.forEach { runCatching { it.close() } }
            Result.failure(error)
        }
    }

    fun resolveSubtitleFetch(
        canonicalUrl: String,
        fallbackHeaders: Map<String, String>?,
    ): SubtitleFetchTarget? = activePrepared?.resolveSubtitleFetch(canonicalUrl, fallbackHeaders)

    fun clearActiveItem() {
        swapActivePrepared(null)
    }

    override fun close() {
        clearActiveItem()
    }

    private fun swapActivePrepared(next: PreparedPageItem?) {
        val previous = synchronized(stateLock) {
            val old = activePrepared
            activePrepared = next
            old
        }
        if (previous !== next) {
            previous?.close()
        }
    }

    /** Registers a page subtitle, or returns null when the policy or proxy refuses it. */
    private suspend fun registerSubtitleOrNull(
        origin: String,
        rawUrl: String,
        headers: Map<String, String>,
        allowedPrivateOrigins: List<String>,
        leasesOut: MutableList<AutoCloseable>,
    ): RegisteredMedia? = try {
        registerCheckedUrl(
            origin = origin,
            rawUrl = rawUrl,
            headers = headers,
            contentType = null,
            allowedPrivateOrigins = allowedPrivateOrigins,
            leasesOut = leasesOut,
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun registerCheckedUrl(
        origin: String,
        rawUrl: String,
        headers: Map<String, String>,
        contentType: String?,
        allowedPrivateOrigins: List<String>,
        leasesOut: MutableList<AutoCloseable>,
    ): RegisteredMedia {
        validatePageRegistrationInput(rawUrl, headers, allowedPrivateOrigins).getOrThrow()
        val requestUrl = rawUrl.substringBefore('#')
        val registered = proxyClient.registerUrl(
            url = requestUrl,
            headers = headers,
            contentType = contentType,
            allowedPrivateOrigins = allowedPrivateOrigins,
            remoteOrigin = true,
        )
        if (!MediaNetworkPolicy.isTrustedLocalProxyUrl(registered.url, origin)) {
            throw IllegalStateException("Local media proxy returned an untrusted stream URL")
        }
        val lease = proxyClient.retainId(registered.id)
            ?: throw IllegalStateException("Unable to retain playback lease for proxied resource")
        leasesOut += lease
        return registered
    }

    companion object {
        const val MAX_PAGE_SUBTITLES = 16
        private const val MAX_PAGE_HEADERS = 16
        private const val MAX_HEADER_VALUE_LEN = 8_192

        private val ALLOWED_WEBPAGE_HEADERS = setOf(
            "authorization",
            "cookie",
            "referer",
            "origin",
            "user-agent",
            "accept",
            "accept-language",
        )

        internal fun validatePageRegistrationInput(
            rawUrl: String,
            headers: Map<String, String>,
            allowedPrivateOrigins: List<String>,
        ): Result<Unit> {
            val requestUrl = rawUrl.substringBefore('#')
            if (!MediaNetworkPolicy.isAllowedRegistrationUrlSyntax(
                    requestUrl,
                    allowedPrivateOrigins,
                )
            ) {
                return Result.failure(
                    IllegalArgumentException("Webpage media destination is not allowed"),
                )
            }
            if (headers.size > MAX_PAGE_HEADERS) {
                return Result.failure(
                    IllegalArgumentException("Webpage media carries too many headers"),
                )
            }
            for ((name, value) in headers) {
                val lower = name.trim().lowercase(Locale.ROOT)
                if (lower !in ALLOWED_WEBPAGE_HEADERS ||
                    value.length > MAX_HEADER_VALUE_LEN ||
                    name.contains('\r') ||
                    name.contains('\n') ||
                    value.contains('\r') ||
                    value.contains('\n')
                ) {
                    return Result.failure(
                        IllegalArgumentException("Webpage request header is not allowed: $name"),
                    )
                }
            }
            return Result.success(Unit)
        }

        internal fun isHlsPayload(payload: PlayPayload): Boolean =
            payload.detected_by == "body_content_m3u8" ||
                payload.detected_by == "url_pattern_m3u8" ||
                payload.content_type.equals("application/vnd.apple.mpegurl", ignoreCase = true) ||
                payload.content_type.equals("application/x-mpegurl", ignoreCase = true) ||
                (payload.content_type.isNullOrEmpty() &&
                    (payload.url.contains(".m3u8", ignoreCase = true) ||
                        payload.url.contains(".jpg", ignoreCase = true)))

        internal fun isDashPayload(payload: PlayPayload): Boolean =
            !isHlsPayload(payload) && (
                payload.content_type.equals("application/dash+xml", ignoreCase = true) ||
                    payload.content_type?.contains("dash", ignoreCase = true) == true ||
                    payload.url.substringBefore('#').substringBefore('?')
                        .contains(".mpd", ignoreCase = true)
                )

        private fun putSubtitleMapping(
            target: MutableMap<String, String>,
            canonicalUrl: String,
            proxiedUrl: String,
        ) {
            target[canonicalUrl] = proxiedUrl
            target.putIfAbsent(canonicalUrl.substringBefore('#'), proxiedUrl)
        }
    }
}
