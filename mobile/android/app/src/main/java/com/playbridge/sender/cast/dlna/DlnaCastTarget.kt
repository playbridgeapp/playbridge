package com.playbridge.sender.cast.dlna

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastTarget
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.googlecast.RustSessionClient
import com.playbridge.sender.cast.proxy.StreamRouteMode
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

internal class DlnaSessionContractUnsupportedException : IllegalStateException(
    "Packaged Cast Core does not support the Rust DLNA session contract",
)

/** DLNA media preparation stays Android-side; all renderer control is owned by Rust. */
class DlnaCastTarget(
    private val device: TvDevice,
    private val descriptionUrl: String,
    private val context: Context,
    private val proxy: LocalProxyServer,
) : CastTarget {
    override val id: String = device.uuid.ifEmpty { "dlna://${device.ip}" }
    override val name: String = device.name
    override val kind: TargetKind = TargetKind.DLNA

    @Volatile
    private var volumeSupported: Boolean? = null

    private val baseCapabilities = setOf(
        Capability.LOAD,
        Capability.PLAY_PAUSE,
        Capability.SEEK,
        Capability.STOP,
        Capability.NOW_PLAYING,
        Capability.SCREEN_MIRROR,
    )

    override val capabilities: Set<Capability>
        get() = if (volumeSupported == true) baseCapabilities + Capability.VOLUME else baseCapabilities

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val _status = MutableStateFlow(PlaybackStatus(PlaybackState.IDLE))
    private var client: RustSessionClient? = null
    private var pollJob: Job? = null
    private var factsJob: Job? = null
    private var connectionAttempt = 0

    @Volatile
    private var released = false

    @Volatile
    private var activeLoadEpoch: Long? = null

    @Volatile
    private var activeIsLive = false

    @Volatile
    private var cachedDurationMs = 0L

    @Volatile
    private var currentProxyUrl: String? = null

    private var lastReportedLive: Boolean? = null
    private var lastReportedDurationMs: Long? = null

    /** Begin the status monitor as soon as this receiver is selected. */
    fun connectReady() {
        if (!released) startPolling()
    }

    override suspend fun load(media: MediaItem) {
        stopPolling()
        factsJob?.cancelAndJoin()
        activeLoadEpoch = media.loadEpoch
        activeIsLive = media.isScreenMirror || media.streamType.equals("LIVE", ignoreCase = true)
        cachedDurationMs = media.durationMs.coerceAtLeast(0L)
        lastReportedLive = activeIsLive.takeIf { it }
        lastReportedDurationMs = media.durationMs.takeIf { it > 0L }
        val loadUrl = resolveLoadUrl(media)
        currentProxyUrl = loadUrl
        _status.value = PlaybackStatus(
            PlaybackState.BUFFERING,
            loadEpoch = media.loadEpoch,
            volumeSupported = volumeSupported,
        )

        try {
            val connectedClient = ensureConnected()
                ?: throw IllegalStateException("DLNA renderer is not ready")
            withContext(Dispatchers.IO) {
                connectedClient.load(
                    contentUrl = loadUrl,
                    contentType = media.mimeType,
                    title = media.title,
                    artUrl = null,
                    startSeconds = media.startPositionMs.coerceAtLeast(0L) / 1000.0,
                    streamType = media.streamType?.uppercase()?.takeIf { it in setOf("LIVE", "BUFFERED") },
                    durationMs = media.durationMs,
                    isScreenMirror = media.isScreenMirror,
                    fallbackUrl = media.mirrorHlsUrl.takeIf { media.isScreenMirror },
                    fallbackContentType = media.mirrorHlsUrl
                        ?.takeIf { media.isScreenMirror }
                        ?.let { HLS_MIME_TYPE },
                )
            }
            _status.value = PlaybackStatus(
                PlaybackState.BUFFERING,
                loadEpoch = media.loadEpoch,
                volumeSupported = volumeSupported,
            )
            startPolling()
            if (cachedDurationMs <= 0L && !activeIsLive && !isHlsMedia(media, loadUrl)) {
                startDurationProbe(loadUrl, media.loadEpoch)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _status.value = PlaybackStatus(
                PlaybackState.ERROR,
                failure = error,
                loadEpoch = media.loadEpoch,
                volumeSupported = volumeSupported,
            )
            throw error
        }
    }

    private fun resolveLoadUrl(media: MediaItem): String {
        when (media.effectiveRoute) {
            StreamRouteMode.DIRECT, StreamRouteMode.VIA_PROXY, StreamRouteMode.VIA_PHONE -> return media.url
            null -> Unit
        }
        return if (media.url.startsWith("content://") || media.url.startsWith("file://")) {
            proxy.publishLocal(media.url.toUri(), media.mimeType)
        } else if (media.headers.isNotEmpty()) {
            proxy.publish(media.url, media.headers, media.mimeType)
        } else {
            media.url
        }
    }

    override suspend fun play() {
        ensureConnected()?.play() ?: throw IllegalStateException("DLNA renderer is not ready")
        startPolling()
    }

    override suspend fun pause() {
        ensureConnected()?.pause() ?: throw IllegalStateException("DLNA renderer is not ready")
    }

    override suspend fun stop() {
        ensureConnected()?.stop() ?: throw IllegalStateException("DLNA renderer is not ready")
        stopPolling()
        _status.value = PlaybackStatus(
            PlaybackState.STOPPED,
            loadEpoch = activeLoadEpoch,
            volumeSupported = volumeSupported,
        )
    }

    override suspend fun seekTo(positionMs: Long) {
        ensureConnected()?.seek(positionMs.coerceAtLeast(0L) / 1000.0)
            ?: throw IllegalStateException("DLNA renderer is not ready")
    }

    override suspend fun setVolume(percent: Int) {
        if (volumeSupported == false) return
        ensureConnected()?.setVolume(percent.coerceIn(0, 100) / 100f)
    }

    /** The remote buttons use +/-0.05 receiver-volume steps. */
    suspend fun adjustVolume(delta: Float) {
        if (volumeSupported == false) return
        ensureConnected()?.adjustVolume(delta.coerceIn(-1f, 1f))
    }

    override fun status(): Flow<PlaybackStatus> = _status.asStateFlow()

    override fun release() {
        if (released) return
        released = true
        stopPolling()
        factsJob?.cancel()
        scope.launch(Dispatchers.IO) {
            connectionMutex.withLock {
                val detached = client
                client = null
                runCatching { detached?.disconnect() }
            }
            scope.cancel()
        }
    }

    private suspend fun ensureConnected(): RustSessionClient? = connectionMutex.withLock {
        if (released) return@withLock null
        client?.takeIf { it.isReady }?.let { return@withLock it }
        val stale = client
        client = null
        if (stale != null) withContext(NonCancellable) { stale.reset() }

        val replacement = RustSessionClient(scope, ++connectionAttempt)
        client = replacement
        try {
            withContext(Dispatchers.IO) {
                replacement.connectDlna(descriptionUrl, localNetworkHandle())
                // is_live is the additive marker for the migrated DLNA status contract. An old
                // packaged worker can connect but silently ignores DLNA-only LOAD fields.
                val initialStatus = replacement.status()
                if (!initialStatus.hasLiveField) throw DlnaSessionContractUnsupportedException()
            }
            if (released || client !== replacement) {
                client = null
                withContext(NonCancellable) { replacement.reset() }
                null
            } else {
                volumeSupported = replacement.volumeSupported
                replacement
            }
        } catch (error: Throwable) {
            if (client === replacement) client = null
            withContext(NonCancellable) { replacement.reset() }
            throw error
        }
    }

    private fun startPolling() {
        if (released || pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            var retryDelay = POLL_INTERVAL_MS
            while (isActive && !released) {
                try {
                    val connectedClient = ensureConnected()
                    if (connectedClient == null) break
                    val status = connectedClient.status()
                    volumeSupported = status.volumeSupported ?: connectedClient.volumeSupported
                    val proxyFacts = currentProxyUrl?.let(proxy::mediaFacts)
                    val proxyLive = proxyFacts?.isLive == true
                    val live = activeIsLive || status.isLive || proxyLive
                    val proxyDuration = proxyFacts?.durationMs?.takeIf { it > 0L } ?: 0L
                    val receiverDuration = (status.durationSeconds * 1000.0).toLong().coerceAtLeast(0L)
                    if (!live) {
                        when {
                            receiverDuration > 0L -> cachedDurationMs = receiverDuration
                            proxyDuration > 0L -> cachedDurationMs = proxyDuration
                        }
                    }
                    reportProxyFacts(connectedClient, activeLoadEpoch, live, proxyDuration)
                    val duration = when {
                        live -> 0L
                        receiverDuration > 0L -> receiverDuration
                        proxyDuration > 0L -> proxyDuration
                        else -> cachedDurationMs
                    }
                    val state = mapState(status.state)
                    _status.value = PlaybackStatus(
                        state = state,
                        positionMs = (status.positionSeconds * 1000.0).toLong().coerceAtLeast(0L),
                        durationMs = duration,
                        isLive = live,
                        loadEpoch = activeLoadEpoch,
                        volumeSupported = volumeSupported,
                    )
                    if (state == PlaybackState.STOPPED) {
                        stopPolling()
                        break
                    }
                    retryDelay = POLL_INTERVAL_MS
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error is DlnaSessionContractUnsupportedException) {
                        _status.value = PlaybackStatus(
                            PlaybackState.ERROR,
                            failure = error,
                            loadEpoch = activeLoadEpoch,
                            volumeSupported = volumeSupported,
                        )
                        break
                    }
                    if (error is DlnaActionFailure) {
                        Log.w(
                            TAG,
                            "DLNA status action failed action=${error.actionName} " +
                                "http=${error.httpStatus} upnp=${error.upnpCode ?: "unknown"}; retrying",
                        )
                    } else {
                        Log.w(TAG, "DLNA status retry after ${error.javaClass.simpleName}")
                    }
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
                delay(retryDelay)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun reportProxyFacts(
        connectedClient: RustSessionClient,
        loadEpoch: Long?,
        live: Boolean,
        proxyDurationMs: Long,
    ) {
        if (loadEpoch == null || loadEpoch != activeLoadEpoch || released) return
        val observedLive = when {
            live -> true
            proxyDurationMs > 0L -> false
            else -> null
        }
        val duration = when {
            proxyDurationMs > 0L -> proxyDurationMs
            cachedDurationMs > 0L && lastReportedDurationMs == null -> cachedDurationMs
            else -> null
        }
        if (observedLive == lastReportedLive && duration == null) return
        if (observedLive == lastReportedLive && duration == lastReportedDurationMs) return
        runCatching {
            connectedClient.mediaFacts(observedLive, duration)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "DLNA media facts update failed: ${error.javaClass.simpleName}")
        }
        if (observedLive != null) lastReportedLive = observedLive
        if (duration != null) lastReportedDurationMs = duration
    }

    private fun startDurationProbe(url: String, loadEpoch: Long?) {
        factsJob = scope.launch(Dispatchers.IO) {
            delay(DURATION_PROBE_DELAY_MS)
            if (released || loadEpoch != activeLoadEpoch || activeIsLive || cachedDurationMs > 0L) return@launch
            val probedDuration = probeDurationMs(url)
            if (probedDuration > 0L && !released && loadEpoch == activeLoadEpoch && !activeIsLive) {
                cachedDurationMs = probedDuration
                val connectedClient = runCatching { ensureConnected() }.getOrNull() ?: return@launch
                runCatching { connectedClient.mediaFacts(isLive = false, durationMs = probedDuration) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "DLNA duration fact update failed: ${error.javaClass.simpleName}")
                    }
                lastReportedLive = false
                lastReportedDurationMs = probedDuration
            }
        }
    }

    private suspend fun probeDurationMs(url: String): Long = withContext(Dispatchers.IO) {
        withTimeoutOrNull(DURATION_PROBE_TIMEOUT_MS) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(url)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            } finally {
                runCatching { retriever.release() }
            }
        } ?: 0L
    }

    /** Bind only DLNA renderer traffic to the physical LAN carrying its LOCATION host. */
    private fun localNetworkHandle(): Long? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val receiverAddress = runCatching { InetAddress.getByName(device.ip) }.getOrNull()
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val isPhysicalLan =
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (isPhysicalLan) network to capabilities else null
        }
        val selected = candidates.firstOrNull { (network, _) ->
            receiverAddress != null && connectivity.getLinkProperties(network)
                ?.routes
                ?.any { route -> route.matches(receiverAddress) } == true
        } ?: candidates.firstOrNull()
        val network = selected?.first ?: return null
        val handle = runCatching { network.networkHandle }.getOrNull()?.takeIf { it != 0L } ?: return null
        val activeHandle = runCatching { connectivity.activeNetwork?.networkHandle }.getOrNull()
        return handle.takeIf { it != activeHandle }
    }

    private fun isHlsMedia(media: MediaItem, url: String): Boolean =
        media.mimeType?.contains("mpegurl", ignoreCase = true) == true ||
            url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)

    private fun mapState(state: String): PlaybackState = when (state.lowercase()) {
        "playing" -> PlaybackState.PLAYING
        "paused" -> PlaybackState.PAUSED
        "buffering" -> PlaybackState.BUFFERING
        "stopped", "finished" -> PlaybackState.STOPPED
        "error" -> PlaybackState.ERROR
        else -> PlaybackState.IDLE
    }

    companion object {
        private const val TAG = "DlnaCastTarget"
        private const val HLS_MIME_TYPE = "application/x-mpegURL"
        private const val POLL_INTERVAL_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 8_000L
        private const val DURATION_PROBE_DELAY_MS = 2_000L
        private const val DURATION_PROBE_TIMEOUT_MS = 8_000L
    }
}
