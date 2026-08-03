package com.playbridge.sender.cast.dlna

import android.media.MediaMetadataRetriever
import com.playbridge.sender.cast.Capability
import androidx.core.net.toUri
import com.playbridge.sender.cast.CastTarget
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.proxy.StreamRouteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [CastTarget] backed by a DLNA renderer: control over AVTransport SOAP, with
 * media served through the [LocalProxyServer] (header injection + HLS rewriting +
 * local files). Reduced capability set — no remote/browser/queue.
 */
class DlnaCastTarget(
    override val id: String,
    override val name: String,
    private val avTransport: AvTransportClient,
    private val renderingControl: RenderingControlClient? = null,
    private val proxy: LocalProxyServer,
) : CastTarget {

    override val kind = TargetKind.DLNA

    override val capabilities = buildSet {
        addAll(setOf(
        Capability.LOAD,
        Capability.PLAY_PAUSE,
        Capability.SEEK,
        Capability.STOP,
        Capability.NOW_PLAYING,
        ))
        if (renderingControl != null) add(Capability.VOLUME)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentProxyUrl: String? = null
    @Volatile private var cachedDurationMs = 0L
    @Volatile private var durationTries = 0
    private val _status = MutableStateFlow(PlaybackStatus(PlaybackState.IDLE))
    private var pollJob: Job? = null
    @Volatile private var activeLoadEpoch: Long? = null

    override suspend fun load(media: MediaItem) {
        stopPolling()
        activeLoadEpoch = media.loadEpoch
        _status.value = PlaybackStatus(PlaybackState.BUFFERING, loadEpoch = media.loadEpoch)
        cachedDurationMs = media.durationMs.coerceAtLeast(0L) // e.g. MediaStore for local files
        durationTries = 0
        val loadUrl = resolveLoadUrl(media)
        currentProxyUrl = loadUrl
        // SetAVTransportURI resets the playhead to 0; Play then starts the hand-off.
        // SOAP/connection failures are control errors. Generic STOPPED is not enough to
        // distinguish an incompatible stream from normal renderer behavior.
        try {
            avTransport.setAvTransportUri(loadUrl)
            avTransport.play()
        } catch (error: java.io.IOException) {
            _status.value = PlaybackStatus(
                PlaybackState.ERROR,
                failure = error,
                loadEpoch = media.loadEpoch,
            )
            throw error
        }
        _status.value = PlaybackStatus(PlaybackState.PLAYING, loadEpoch = media.loadEpoch)
        startPolling(media.loadEpoch)

        // Resume point: seek once the renderer has begun playback (an immediate Seek is
        // ignored by most renderers while still TRANSITIONING). Poll the transport state
        // instead of a blind delay — a fixed 2.5s missed slow renderers entirely and
        // over-waited on fast ones.
        if (media.startPositionMs > 0 && !proxy.isLiveStream) {
            scope.launch {
                val deadline = System.currentTimeMillis() + RESUME_SEEK_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    val state = runCatching { avTransport.getTransportState() }.getOrNull()
                    if (state?.uppercase() == "PLAYING") break
                    delay(500)
                }
                // Re-check liveness: the playlist may only now have been served/parsed.
                if (!proxy.isLiveStream) {
                    runCatching { avTransport.seek(formatTime(media.startPositionMs)) }
                }
            }
        }

        // If duration is still unknown, probe it in the background (non-blocking) for non-HLS VOD.
        // HLS gets its duration from the playlist (proxy.vodDurationMs) — and MediaMetadataRetriever
        // can't open an .m3u8 anyway — so skip the (8s-timeout) probe for it.
        if (cachedDurationMs <= 0L) {
            scope.launch {
                delay(2000) // let the renderer fetch the playlist so live/VOD + duration are known
                val isHls = proxy.isLiveStream || proxy.vodDurationMs > 0L
                if (cachedDurationMs <= 0L && !isHls) {
                    val probed = probeDurationMs(loadUrl)
                    if (probed > 0L) cachedDurationMs = probed
                }
            }
        }
    }

    private fun resolveLoadUrl(media: MediaItem): String {
        when (media.effectiveRoute) {
            StreamRouteMode.DIRECT -> return media.url
            StreamRouteMode.VIA_PROXY -> return media.url
            StreamRouteMode.VIA_PHONE -> return media.url
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
        avTransport.play()
        startPolling(activeLoadEpoch)
    }

    override suspend fun pause() {
        avTransport.pause()
    }

    override suspend fun stop() {
        avTransport.stop()
        stopPolling()
        _status.value = PlaybackStatus(PlaybackState.STOPPED, loadEpoch = activeLoadEpoch)
    }

    override suspend fun seekTo(positionMs: Long) {
        avTransport.seek(formatTime(positionMs))
    }

    override suspend fun setVolume(percent: Int) {
        renderingControl?.setVolume(percent)
    }

    suspend fun adjustVolume(delta: Int) {
        val control = renderingControl ?: return
        val current = control.getVolume() ?: return
        control.setVolume(current + delta)
    }

    override fun status(): Flow<PlaybackStatus> = _status.asStateFlow()

    private fun startPolling(loadEpoch: Long? = activeLoadEpoch) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching {
                    val pos = avTransport.getPositionInfo()
                    val state = mapState(avTransport.getTransportState())
                    val live = proxy.isLiveStream
                    // Duration, renderer-first: TrackDuration, else GetMediaInfo (a few tries),
                    // else the proxy's HLS duration, then the probed/seeded duration.
                    var durationMs = if (live) 0L else parseTime(pos?.trackDuration)
                    if (!live && durationMs <= 0L) {
                        if (cachedDurationMs <= 0L && durationTries < 20) {
                            durationTries++
                            cachedDurationMs = parseTime(avTransport.getMediaDuration())
                        }
                        if (cachedDurationMs <= 0L && proxy.vodDurationMs > 0L) {
                            cachedDurationMs = proxy.vodDurationMs
                        }
                        durationMs = cachedDurationMs
                    } else if (durationMs > 0L) {
                        cachedDurationMs = durationMs
                    }
                    _status.value = PlaybackStatus(
                        state = state,
                        positionMs = parseTime(pos?.relTime),
                        durationMs = durationMs,
                        isLive = live,
                        loadEpoch = loadEpoch,
                    )
                    if (state == PlaybackState.STOPPED) stopPolling()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun release() {
        stopPolling()
        scope.cancel()
        // Proxy sessions are LRU-evicted, so there's nothing to free per-target.
        currentProxyUrl = null
    }

    /** Best-effort duration extraction for web VOD when the renderer reports none. */
    private suspend fun probeDurationMs(url: String): Long = withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(url) // proxy URL — headers are injected by the proxy
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            } finally {
                runCatching { mmr.release() }
            }
        } ?: 0L
    }

    private fun mapState(s: String?): PlaybackState = when (s?.uppercase()) {
        "PLAYING" -> PlaybackState.PLAYING
        "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlaybackState.PAUSED
        "TRANSITIONING" -> PlaybackState.BUFFERING
        "STOPPED" -> PlaybackState.STOPPED
        else -> PlaybackState.IDLE
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L

        /** How long to wait for the renderer to reach PLAYING before the resume seek. */
        private const val RESUME_SEEK_TIMEOUT_MS = 10_000L

        /** ms → "HH:MM:SS" for AVTransport REL_TIME (zero-padded — some renderers reject "0:..."). */
        fun formatTime(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
        }

        /** Parse "H:MM:SS" / "HH:MM:SS.mmm" → ms; 0 on failure. */
        fun parseTime(t: String?): Long {
            if (t.isNullOrBlank()) return 0L
            val p = t.split(":")
            if (p.size != 3) return 0L
            return try {
                val h = p[0].trim().toLong()
                val m = p[1].trim().toLong()
                val s = p[2].substringBefore('.').trim().toLong()
                (h * 3600 + m * 60 + s) * 1000
            } catch (e: Exception) {
                0L
            }
        }
    }
}
