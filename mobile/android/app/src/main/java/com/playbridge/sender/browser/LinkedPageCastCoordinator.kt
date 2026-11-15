package com.playbridge.sender.browser

import android.util.Log
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.ConnectionMerge
import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.ExternalQueueCoordinator
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.player.PhonePlayerOpeningOrientation
import com.playbridge.shared.network.MediaNetworkPolicy
import com.playbridge.shared.protocol.createContextQueryJson
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.protocol.createPlaylistJumpCommandJson
import com.playbridge.shared.protocol.createQueueAddCommandJson
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import playbridge.PlayPayload
import playbridge.SubtitleResource
import playbridge.VisualMetadata

data class LinkedPageCastUiState(
    val active: Boolean = false,
    val controllerName: String? = null,
)

data class LinkedPageCastOpenRequest(
    val bridgeRequestId: String,
    val sessionId: String,
    val origin: String,
    val tabId: Int,
    val navigationGeneration: Long,
    val items: List<LinkedPageCastItem>,
    val startIndex: Int,
    val playlistMetadata: playbridge.VisualMetadata?,
    val skipPreplay: Boolean,
    val requestedPrivateOrigins: Set<String>,
    val destinationId: String? = null,
    val initialOrientation: PhonePlayerOpeningOrientation? = null,
) {
    fun withPrivateOriginPermission(origins: Collection<String>): LinkedPageCastOpenRequest = copy(
        items = items.map { item ->
            item.copy(payload = item.payload.copy(allowed_private_origins = origins.toList()))
        },
    )
}

data class LinkedPageCastItem(
    val id: String,
    val payload: PlayPayload,
)

internal fun linkedQueueDemand(
    prefetchWindow: Int,
    currentIndex: Int,
    totalCount: Int,
    requestPending: Boolean,
    endOfList: Boolean,
    awaitingPlaylistEcho: Boolean,
): Int {
    if (requestPending || endOfList || awaitingPlaylistEcho) return 0
    val queuedAhead = (totalCount - currentIndex - 1).coerceAtLeast(0)
    return (prefetchWindow.coerceIn(1, 10) - queuedAhead).coerceAtLeast(0)
}

internal enum class LinkedSupplyDisposition { ACCEPT, ALREADY_ACCEPTED, STALE }

internal fun linkedSupplyDisposition(
    requestId: String?,
    pendingRequestId: String?,
    lastAcceptedRequestId: String?,
): LinkedSupplyDisposition = when {
    requestId != null && requestId == lastAcceptedRequestId ->
        LinkedSupplyDisposition.ALREADY_ACCEPTED
    requestId != null && requestId == pendingRequestId -> LinkedSupplyDisposition.ACCEPT
    else -> LinkedSupplyDisposition.STALE
}

internal fun pageRequestSuperseded(
    requestTabId: Int,
    requestNavigationGeneration: Long,
    navigationTabId: Int,
    navigationGeneration: Long,
): Boolean = requestTabId == navigationTabId &&
    navigationGeneration > requestNavigationGeneration

/** Native identity of the document that opened a linked session. */
internal data class LinkedSessionBinding(
    val sessionId: String,
    val origin: String,
    val tabId: Int,
    val navigationGeneration: Long,
)

internal fun LinkedPageCastOpenRequest.linkedSessionBinding() = LinkedSessionBinding(
    sessionId = sessionId,
    origin = origin,
    tabId = tabId,
    navigationGeneration = navigationGeneration,
)

/** Session commands must match the opening document, not only the session id and origin. */
internal fun linkedMessageMatchesSession(
    binding: LinkedSessionBinding,
    message: JSONObject,
    origin: String,
): Boolean = binding.sessionId == message.optString("sessionId") &&
    binding.origin == origin &&
    binding.tabId == message.optInt("tabId", Int.MIN_VALUE) &&
    binding.navigationGeneration == message.optLong("navigationGeneration", Long.MIN_VALUE)

/**
 * Website consent is re-checked on every linked operation, like iOS ownedSession.
 * A message for another document is session_ended and must not end the active session.
 */
internal fun linkedSessionOperationError(sessionMatches: Boolean, websiteApproved: Boolean): String? = when {
    !sessionMatches -> "session_ended"
    !websiteApproved -> "not_allowed"
    else -> null
}

private const val LINKED_SESSION_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1_000L
private const val LINKED_SESSION_MAX_LIFETIME_MILLIS = 2 * 60 * 60 * 1_000L

internal fun linkedSessionExpired(
    nowMillis: Long,
    lastActivityAtMillis: Long,
    createdAtMillis: Long,
): Boolean = nowMillis - lastActivityAtMillis > LINKED_SESSION_IDLE_TIMEOUT_MILLIS ||
    nowMillis - createdAtMillis > LINKED_SESSION_MAX_LIFETIME_MILLIS

/** Fast receiver re-query while a newly opened queue waits for its first playlist echo. */
internal const val LINKED_ECHO_RESYNC_MILLIS = 2_000L
/**
 * Safety re-query once the queue is confirmed. Receivers push every change to every
 * sender and reconnects resync in full, so this only covers a failed send to a live socket.
 */
internal const val LINKED_SAFETY_RESYNC_MILLIS = 30_000L

/**
 * Whether the linked session should re-query the receiver on this tick. Every tick while
 * the queue awaits its echo (the tick is [LINKED_ECHO_RESYNC_MILLIS]); otherwise once per
 * [LINKED_SAFETY_RESYNC_MILLIS].
 */
internal fun linkedResyncDue(awaitingPlaylistEcho: Boolean, nowMillis: Long, lastResyncAtMillis: Long): Boolean =
    awaitingPlaylistEcho || nowMillis - lastResyncAtMillis >= LINKED_SAFETY_RESYNC_MILLIS

/** Owns the single page-linked playlist authority while Android owns the TV or Watch Party session. */
class LinkedPageCastCoordinator internal constructor(
    private val webSocketClient: WebSocketClient,
    private val connectionCoordinator: ConnectionCoordinator,
    private val connectionStore: ConnectionStore,
    private val settingsRepository: SettingsRepository,
    private val externalQueueCoordinator: ExternalQueueCoordinator,
    private val scope: CoroutineScope,
    private val partyGateway: PartyPlaybackGateway,
) {
    constructor(
        webSocketClient: WebSocketClient,
        connectionCoordinator: ConnectionCoordinator,
        connectionStore: ConnectionStore,
        settingsRepository: SettingsRepository,
        externalQueueCoordinator: ExternalQueueCoordinator,
        scope: CoroutineScope,
    ) : this(
        webSocketClient = webSocketClient,
        connectionCoordinator = connectionCoordinator,
        connectionStore = connectionStore,
        settingsRepository = settingsRepository,
        externalQueueCoordinator = externalQueueCoordinator,
        scope = scope,
        partyGateway = PhoneTogetherGateway,
    )
    private val _uiState = MutableStateFlow(LinkedPageCastUiState())
    val uiState: StateFlow<LinkedPageCastUiState> = _uiState.asStateFlow()

    private val engine = LinkedSessionEngine(
        prefetchWindow = { settingsRepository.tvPrefetchWindow.first().coerceIn(1, 10) },
        postLinkedMessage = Components::postLinkedMessage,
        scope = scope,
        onUiStateChange = { _uiState.value = it },
    )

    fun parseOpen(message: JSONObject): LinkedPageCastOpenRequest? = parseOpenMessage(message)

    suspend fun requestedPrivateOrigins(request: LinkedPageCastOpenRequest): Set<String>? =
        withContext(Dispatchers.IO) {
            collectRequestedPrivateOrigins(request.items, request.requestedPrivateOrigins)
        }

    suspend fun messageRequestedPrivateOrigins(message: JSONObject): Set<String>? = withContext(Dispatchers.IO) {
        val payload = message.optJSONObject("payload") ?: return@withContext emptySet()
        val declared = parseRequestedPrivateOrigins(payload) ?: return@withContext null
        val items = parseItems(payload.optJSONArray("items"), emptySet()).orEmpty()
        collectRequestedPrivateOrigins(items, declared)
    }

    fun allowPrivateOriginsForActive(origin: String, origins: Collection<String>): Boolean =
        engine.allowPrivateOriginsForActive(origin, origins)

    fun isMessageForActiveSession(message: JSONObject, origin: String): Boolean =
        engine.isMessageForActiveSession(message, origin)

    fun cancelOpen(targetBridgeRequestId: String, reason: String = "navigation") =
        engine.cancelOpen(targetBridgeRequestId, reason)

    fun isOpenCancelled(bridgeRequestId: String): Boolean =
        engine.isOpenCancelled(bridgeRequestId)

    fun open(request: LinkedPageCastOpenRequest, receiver: TvDevice) {
        engine.open(request, NativeTvLinkedSessionSink(receiver))
    }

    /** Opens a linked session targeting the active Watch Party. */
    fun openParty(request: LinkedPageCastOpenRequest) {
        engine.open(request, WatchPartyLinkedSessionSink(partyGateway))
    }

    fun handle(message: JSONObject) = engine.handle(message)

    fun accept(bridgeRequestId: String) = engine.accept(bridgeRequestId)

    fun reject(bridgeRequestId: String, error: String) = engine.reject(bridgeRequestId, error)

    fun supersedeIfActive() = engine.supersedeIfActive()

    fun unlink(reason: String = "unlinked") = engine.unlink(reason)

    fun activeOrigin(): String? = engine.activeOrigin()

    private fun collectRequestedPrivateOrigins(
        items: List<LinkedPageCastItem>,
        declared: Collection<String>,
    ): Set<String>? {
        MediaNetworkPolicy.normalizePrivateOrigins(declared) ?: return null
        val origins = declared.mapNotNullTo(linkedSetOf(), MediaNetworkPolicy::privateOrigin)
        items.forEach { item ->
            sequenceOf(item.payload.url)
                .plus(item.payload.subtitles.asSequence())
                .plus(item.payload.subtitle_resources.asSequence().map(SubtitleResource::url))
                .mapNotNull(MediaNetworkPolicy::privateOrigin)
                .forEach(origins::add)
        }
        return MediaNetworkPolicy.normalizePrivateOrigins(origins)
    }

    private inner class NativeTvLinkedSessionSink(
        private val receiver: TvDevice,
    ) : LinkedSessionSink {
        private val ids = mutableListOf<String>()
        private var awaitingPlaylistEcho: Boolean = true
        private var hasSeenPlayer: Boolean = false
        private var lastResyncAtMillis: Long = System.currentTimeMillis()
        override var controllerName: String? = null
            private set
        override val showTvUiState: Boolean = true

        override val knownIds: Set<String>
            get() = ids.toSet()

        override suspend fun prepareOpen(request: LinkedPageCastOpenRequest): SinkFailure? =
            if (webSocketClient.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                SinkFailure("connect_failed")
            } else {
                null
            }

        override suspend fun start(
            request: LinkedPageCastOpenRequest,
            host: LinkedSinkHost,
            sessionScope: CoroutineScope,
        ): SinkOpenOutcome {
            if (webSocketClient.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                return SinkOpenOutcome.Failed(SinkFailure("connect_failed"))
            }
            controllerName = PageCastConsentStore.displayName(request.origin)
            _uiState.value = LinkedPageCastUiState(true, controllerName)
            externalQueueCoordinator.stop()
            ids.clear()
            ids.addAll(request.items.map { it.id })
            awaitingPlaylistEcho = true
            hasSeenPlayer = connectionCoordinator.tvActiveContext.value == "player"
            lastResyncAtMillis = System.currentTimeMillis()
            connectionCoordinator.tvPlaylistState.value = null
            val sent = webSocketClient.send(
                createPlaylistCommandJson(
                    pageCastPlaylistPayload(
                        items = request.items.map { it.payload },
                        startIndex = request.startIndex,
                        metadata = request.playlistMetadata,
                        skipPreplay = request.skipPreplay,
                    ),
                ),
            )
            if (!sent) {
                return SinkOpenOutcome.Failed(SinkFailure("connect_failed"))
            }
            startObservers(host, sessionScope)
            return SinkOpenOutcome.Ok
        }

        private fun startObservers(host: LinkedSinkHost, sessionScope: CoroutineScope) {
            sessionScope.launch {
                combine(
                    connectionCoordinator.tvPlaylistState,
                    connectionCoordinator.tvPlayback,
                    connectionCoordinator.tvActiveContext,
                ) { playlist, playback, context -> Triple(playlist, playback, context) }
                    .collect { (playlist, _, context) ->
                        host.runWithLock {
                            if (context == "player") hasSeenPlayer = true
                            if (context == "idle" && hasSeenPlayer) {
                                host.finish("receiver_stopped")
                                return@runWithLock
                            }
                            if (playlist != null && playlist.totalCount >= ids.size) {
                                awaitingPlaylistEcho = false
                            }
                            host.emitSnapshot(currentSnapshot(), evaluateDemand = playlist != null)
                        }
                    }
            }
            sessionScope.launch {
                webSocketClient.connectionState.collect { state ->
                    if (state is WebSocketClient.ConnectionState.Connected) {
                        host.runWithLock {
                            lastResyncAtMillis = host.nowMillis()
                            webSocketClient.send(createContextQueryJson())
                        }
                    }
                }
            }
            sessionScope.launch {
                connectionStore.tvDevice.collect { device ->
                    if (device == null || !ConnectionMerge.isSameDevice(device, receiver)) {
                        host.runWithLock {
                            host.finish("receiver_changed")
                        }
                    }
                }
            }
        }

        override suspend fun validateAvailable(): SinkFailure? {
            val selectedReceiver = connectionStore.tvDevice.first()
            if (selectedReceiver == null || !ConnectionMerge.isSameDevice(selectedReceiver, receiver)) {
                return SinkFailure("receiver_changed", endSessionReason = "receiver_changed")
            }
            return null
        }

        override suspend fun append(items: List<LinkedPageCastItem>): SinkBatchOutcome {
            if (!sendQueueItems(items)) {
                return SinkBatchOutcome.Failed(
                    SinkFailure("connect_failed", endSessionReason = "queue_update_failed"),
                )
            }
            ids += items.map { it.id }
            return SinkBatchOutcome.Ok
        }

        override suspend fun supply(items: List<LinkedPageCastItem>, endOfList: Boolean): SinkBatchOutcome {
            if (!sendQueueItems(items)) {
                return SinkBatchOutcome.Failed(
                    SinkFailure("connect_failed", endSessionReason = "queue_update_failed"),
                )
            }
            ids += items.map { it.id }
            return SinkBatchOutcome.Ok
        }

        override suspend fun replace(
            items: List<LinkedPageCastItem>,
            startIndex: Int,
            metadata: VisualMetadata?,
            skipPreplay: Boolean,
        ): SinkOpenOutcome {
            connectionCoordinator.tvPlaylistState.value = null
            val sent = webSocketClient.send(
                createPlaylistCommandJson(
                    pageCastPlaylistPayload(
                        items = items.map { it.payload },
                        startIndex = startIndex,
                        metadata = metadata,
                        skipPreplay = skipPreplay,
                    ),
                ),
            )
            if (!sent) {
                return SinkOpenOutcome.Failed(
                    SinkFailure("connect_failed", endSessionReason = "queue_update_failed"),
                )
            }
            ids.clear()
            ids.addAll(items.map { it.id })
            awaitingPlaylistEcho = true
            return SinkOpenOutcome.Ok
        }

        override suspend fun jump(index: Int): SinkFailure? {
            if (index !in ids.indices) return SinkFailure("invalid_request")
            return if (webSocketClient.send(createPlaylistJumpCommandJson(index))) {
                null
            } else {
                SinkFailure("connect_failed")
            }
        }

        override fun currentSnapshot(): SinkPlaybackState {
            val playlist = connectionCoordinator.tvPlaylistState.value
            val playback = connectionCoordinator.tvPlayback.value
            val currentIndex = playlist?.currentIndex ?: 0
            val totalCount = playlist?.totalCount ?: ids.size
            val titles = playlist?.items?.map { it.title }.orEmpty()
            val items = List(minOf(totalCount, ids.size)) { index ->
                SinkPlaylistItem(ids[index], titles.getOrNull(index))
            }
            return SinkPlaybackState(
                state = playback?.state ?: "connecting",
                positionMs = playback?.positionMs ?: 0L,
                durationMs = playback?.durationMs ?: 0L,
                title = playback?.title,
                currentIndex = currentIndex,
                totalCount = totalCount,
                items = items,
                awaitingPlaylistEcho = awaitingPlaylistEcho,
            )
        }

        override fun isDemandSuppressed(): Boolean = false

        override suspend fun onHeartbeatTick(nowMillis: Long) {
            if (webSocketClient.connectionState.value is WebSocketClient.ConnectionState.Connected &&
                linkedResyncDue(awaitingPlaylistEcho, nowMillis, lastResyncAtMillis)
            ) {
                // Each query makes the receiver resend its whole snapshot (~4 KB), so ask
                // quickly only until the new queue is confirmed, then rarely as a safety net.
                lastResyncAtMillis = nowMillis
                webSocketClient.send(createContextQueryJson())
            }
        }

        private fun sendQueueItems(items: List<LinkedPageCastItem>): Boolean {
            for (item in items) {
                if (!webSocketClient.send(createQueueAddCommandJson(item.payload))) return false
            }
            return true
        }
    }

    companion object {
        private const val TAG = "LinkedPageCast"
        private const val MAX_ITEMS = 50
        internal const val MAX_SESSION_ITEMS = 200
        private const val MAX_SUBTITLES = 16
        private const val MAX_HEADERS = 16
        private const val MAX_HEADER_BYTES = 16 * 1024
        internal const val MAX_METADATA_BYTES = 16 * 1024
        internal const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_CANCELLED_OPEN_REQUESTS = 64
        internal const val NEED_ITEMS_RETRY_MILLIS = 5_000L
        internal const val STATE_EVENT_INTERVAL_MILLIS = 1_000L
        private val ALLOWED_HEADERS = setOf(
            "authorization", "cookie", "referer", "origin", "user-agent", "accept", "accept-language",
        )

        // Pure request validation shared by the live coordinator and JVM rejection tests.
        internal fun parseOpenMessage(message: JSONObject): LinkedPageCastOpenRequest? = runCatching {
            val bridgeRequestId = message.requiredShortString("bridgeRequestId", 128)
            val sessionId = message.requiredShortString("sessionId", 128)
            val origin = PageCastConsentStore.normalizeOrigin(message.optString("origin")) ?: return null
            val tabId = message.getInt("tabId")
            val navigationGeneration = message.getLong("navigationGeneration")
            if (tabId < 0 || navigationGeneration < 0) return null
            val payload = message.optJSONObject("payload") ?: return null
            if (message.has("progressWebhook") || payload.has("progressWebhook")) return null
            if (payload.toString().toByteArray().size > MAX_REQUEST_BYTES) return null
            val destinationId = if (message.optString("type") == "linked_play") {
                payload.requiredShortString("destinationId", 256)
            } else null
            val initialOrientation = if (payload.has("initialOrientation")) {
                PhonePlayerOpeningOrientation.parse(payload.opt("initialOrientation")) ?: return null
            } else null
            val items = parseItems(payload.optJSONArray("items"), emptySet()) ?: return null
            val startIndex = payload.optInt("startIndex", 0)
            if (startIndex !in items.indices) return null
            val metadata = payload.optJSONObject("metadata")?.let {
                if (it.toString().toByteArray().size > MAX_METADATA_BYTES) return null
                com.playbridge.shared.protocol.decodeVisualMetadataJson(it.toString())
            }
            val skipPreplayValue = payload.opt("skipPreplay")
            val skipPreplay = if (skipPreplayValue == null || skipPreplayValue == JSONObject.NULL) {
                false
            } else {
                skipPreplayValue as? Boolean ?: return null
            }
            LinkedPageCastOpenRequest(
                bridgeRequestId,
                sessionId,
                origin,
                tabId,
                navigationGeneration,
                items,
                startIndex,
                metadata,
                skipPreplay,
                parseRequestedPrivateOrigins(payload) ?: return null,
                destinationId,
                initialOrientation,
            )
        }.getOrNull()

        private fun parseRequestedPrivateOrigins(payload: JSONObject): Set<String>? {
            val values = payload.optJSONArray("privateNetworkOrigins") ?: return emptySet()
            if (values.length() > MediaNetworkPolicy.MAX_PRIVATE_ORIGINS) return null
            val origins = buildList {
                for (index in 0 until values.length()) {
                    val value = values.opt(index) as? String ?: return null
                    add(value)
                }
            }
            return MediaNetworkPolicy.normalizePrivateOrigins(origins)
        }

        internal fun parseItems(
            array: JSONArray?,
            allowedPrivateOrigins: Collection<String>,
        ): List<LinkedPageCastItem>? {
            if (array == null || array.length() !in 1..MAX_ITEMS) return null
            val items = buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: return null
                    if (obj.has("progressWebhook")) return null
                    val id = obj.optString("id")
                    val url = obj.optString("url")
                    if (id.isBlank() || id.length > 128 || !MediaNetworkPolicy.isHttpUrl(url)) return null
                    val title = obj.optString("title").ifBlank { null }
                    val contentType = obj.optString("contentType").ifBlank { null }
                    if ((title?.length ?: 0) > 4_096 || (contentType?.length ?: 0) > 256) return null
                    val startPositionValue = obj.opt("startPositionMs")
                    val startPositionMs: Long? = if (startPositionValue == null || startPositionValue === JSONObject.NULL) {
                        null
                    } else {
                        val value = (startPositionValue as? Number)?.toDouble() ?: return null
                        if (!value.isFinite() || value < 0 || value > 604_800_000 || value % 1 != 0.0) return null
                        value.toLong()
                    }
                    val headers = parseHeaders(obj.optJSONObject("headers")) ?: return null
                    val subtitles = obj.optJSONArray("subtitles")?.let { values ->
                        if (values.length() > MAX_SUBTITLES) return null
                        buildList {
                            for (i in 0 until values.length()) {
                                val subtitle = values.optString(i)
                                if (!MediaNetworkPolicy.isHttpUrl(subtitle)) return null
                                add(subtitle)
                            }
                        }
                    }.orEmpty()
                    val subtitleResources = obj.optJSONArray("subtitleResources")?.let { values ->
                        if (values.length() > MAX_SUBTITLES) return null
                        buildList {
                            for (i in 0 until values.length()) {
                                val resource = values.optJSONObject(i) ?: return null
                                val subtitleUrl = resource.optString("url")
                                if (!MediaNetworkPolicy.isHttpUrl(subtitleUrl)) return null
                                val resourceHeaders = parseHeaders(resource.optJSONObject("headers")) ?: return null
                                val label = resource.optString("label").ifBlank { null }
                                val language = resource.optString("language").ifBlank { null }
                                if ((label?.length ?: 0) > 256 || (language?.length ?: 0) > 64) return null
                                add(SubtitleResource(subtitleUrl, resourceHeaders, label, language))
                            }
                        }
                    }.orEmpty()
                    if (subtitles.size + subtitleResources.size > MAX_SUBTITLES) return null
                    val metadata = obj.optJSONObject("metadata")?.let {
                        if (it.toString().toByteArray().size > MAX_METADATA_BYTES) return null
                        com.playbridge.shared.protocol.decodeVisualMetadataJson(it.toString())
                    }
                    add(
                        LinkedPageCastItem(
                            id,
                            PlayPayload(
                                url = url,
                                title = title,
                                headers = headers,
                                content_type = contentType,
                                start_position_ms = startPositionMs,
                                subtitles = subtitles,
                                subtitle_resources = subtitleResources,
                                detected_by = "linked_page",
                                visual_metadata = metadata,
                                allowed_private_origins = allowedPrivateOrigins.toList(),
                            ),
                        ),
                    )
                }
            }
            return items.takeIf { it.map(LinkedPageCastItem::id).distinct().size == it.size }
        }

        private fun parseHeaders(obj: JSONObject?): Map<String, String>? {
            if (obj == null) return emptyMap()
            if (obj.length() > MAX_HEADERS) return null
            var bytes = 0
            val result = linkedMapOf<String, String>()
            val normalizedNames = mutableSetOf<String>()
            for (name in obj.keys()) {
                val lowerName = name.lowercase(Locale.ROOT)
                if (name != name.trim() || !HEADER_NAME.matches(name) || lowerName !in ALLOWED_HEADERS ||
                    !normalizedNames.add(lowerName)
                ) return null
                val value = obj.opt(name) as? String ?: return null
                if (value.any { it.code in 0..31 || it.code == 127 }) return null
                if (lowerName in setOf("origin", "referer") && !MediaNetworkPolicy.isHttpUrl(value)) return null
                bytes += name.toByteArray().size + value.toByteArray().size
                if (bytes > MAX_HEADER_BYTES) return null
                result[name] = value
            }
            return result
        }

        private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        private fun JSONObject.requiredShortString(name: String, maxLength: Int): String {
            val value = getString(name)
            require(value.isNotBlank() && value.length <= maxLength)
            return value
        }
    }
}
