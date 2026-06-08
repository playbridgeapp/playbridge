package com.playbridge.sender.cast.dlna

import android.net.Uri
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastTarget
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.TargetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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

    private var currentProxyUrl: String? = null

    override suspend fun load(media: MediaItem) {
        val proxyUrl = if (media.url.startsWith("content://") || media.url.startsWith("file://")) {
            proxy.publishLocal(Uri.parse(media.url), media.mimeType)
        } else {
            proxy.publish(media.url, media.headers, media.mimeType)
        }
        currentProxyUrl = proxyUrl
        // SetAVTransportURI resets the playhead to 0; Play then starts the hand-off.
        avTransport.setAvTransportUri(proxyUrl)
        avTransport.play()
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
            emit(
                PlaybackStatus(
                    state = state,
                    positionMs = parseTime(pos?.relTime),
                    durationMs = parseTime(pos?.trackDuration),
                ),
            )
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override fun release() {
        // Proxy sessions are LRU-evicted, so there's nothing to free per-target.
        currentProxyUrl = null
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

        /** ms → "H:MM:SS" for AVTransport REL_TIME. */
        fun formatTime(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
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
