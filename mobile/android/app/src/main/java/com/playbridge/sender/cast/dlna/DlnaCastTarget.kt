package com.playbridge.sender.cast.dlna

import android.media.MediaMetadataRetriever
import android.net.Uri
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastTarget
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.TargetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    private val proxy: LocalProxyServer,
) : CastTarget {

    override val kind = TargetKind.DLNA

    override val capabilities = setOf(
        Capability.LOAD,
        Capability.PLAY_PAUSE,
        Capability.SEEK,
        Capability.STOP,
        Capability.NOW_PLAYING,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentProxyUrl: String? = null
    @Volatile private var cachedDurationMs = 0L
    @Volatile private var durationTries = 0

    override suspend fun load(media: MediaItem) {
        cachedDurationMs = media.durationMs.coerceAtLeast(0L) // e.g. MediaStore for local files
        durationTries = 0
        val proxyUrl = if (media.url.startsWith("content://") || media.url.startsWith("file://")) {
            proxy.publishLocal(Uri.parse(media.url), media.mimeType)
        } else {
            proxy.publish(media.url, media.headers, media.mimeType)
        }
        currentProxyUrl = proxyUrl
        // SetAVTransportURI resets the playhead to 0; Play then starts the hand-off.
        avTransport.setAvTransportUri(proxyUrl)
        avTransport.play()

        // If duration is still unknown, probe it in the background (non-blocking) for VOD — not for
        // live (the proxy learns that from the HLS playlist shortly after this).
        if (cachedDurationMs <= 0L) {
            scope.launch {
                delay(2000) // let the renderer fetch the playlist so isLiveStream is known
                if (cachedDurationMs <= 0L && !proxy.isLiveStream) {
                    val probed = probeDurationMs(proxyUrl)
                    if (probed > 0L) cachedDurationMs = probed
                }
            }
        }
    }

    override suspend fun play() {
        avTransport.play()
    }

    override suspend fun pause() {
        avTransport.pause()
    }

    override suspend fun stop() {
        avTransport.stop()
    }

    override suspend fun seekTo(positionMs: Long) {
        avTransport.seek(formatTime(positionMs))
    }

    /** No RenderingControl wired yet (P3). */
    override suspend fun setVolume(percent: Int) = Unit

    override fun status(): Flow<PlaybackStatus> = flow {
        while (true) {
            val pos = avTransport.getPositionInfo()
            val state = mapState(avTransport.getTransportState())
            val live = proxy.isLiveStream
            // Duration: renderer's TrackDuration, else GetMediaInfo (a few tries), else our
            // probed/seeded value. Live streams have no fixed total.
            var durationMs = if (live) 0L else parseTime(pos?.trackDuration)
            if (!live && durationMs <= 0L) {
                if (cachedDurationMs <= 0L && durationTries < 20) {
                    durationTries++
                    cachedDurationMs = parseTime(avTransport.getMediaDuration())
                }
                durationMs = cachedDurationMs
            } else if (durationMs > 0L) {
                cachedDurationMs = durationMs
            }
            emit(
                PlaybackStatus(
                    state = state,
                    positionMs = parseTime(pos?.relTime),
                    durationMs = durationMs,
                    isLive = live,
                ),
            )
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override fun release() {
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
