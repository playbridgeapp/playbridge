package com.playbridge.sender.cast.googlecast

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastTarget
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [CastTarget] backed by a Google Cast (Chromecast) receiver. Uses the CastV2
 * protocol (TLS + protobuf framing + JSON payloads) to control the Default
 * Media Receiver app on the Chromecast.
 *
 * Media is served through the shared [com.playbridge.sender.cast.dlna.LocalProxyServer]
 * so the Chromecast can reach streams that require request headers (Referer, Cookie, UA)
 * and local files (content://) that are only accessible from the phone.
 */
class GoogleCastTarget(
    val device: TvDevice,
    private val scope: CoroutineScope,
    private val context: Context,
) : CastTarget {

    override val id: String = device.uuid.ifEmpty { "cast://${device.ip}:${device.port}" }
    override val name: String = device.name
    override val kind: TargetKind = TargetKind.GOOGLE_CAST

    override val capabilities: Set<Capability> = setOf(
        Capability.LOAD,
        Capability.PLAY_PAUSE,
        Capability.SEEK,
        Capability.STOP,
        Capability.VOLUME,
        Capability.NOW_PLAYING,
    )

    private val client = CastV2Client()
    private val _status = MutableStateFlow(PlaybackStatus(PlaybackState.IDLE))
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null

    /** Connect to the Chromecast and launch the Default Media Receiver. */
    private suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        if (client.isConnected && client.transportId != null) return@withContext true
        try {
            client.close()
            client.connect(device.ip, device.port.takeIf { it > 0 } ?: 8009)
            val launched = client.launchApp()
            if (launched) {
                startHeartbeat()
            }
            launched
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to Chromecast ${device.ip}: ${e.message}")
            false
        }
    }

    override suspend fun load(media: MediaItem) {
        _status.value = PlaybackStatus(PlaybackState.BUFFERING)
        val connected = ensureConnected()
        if (!connected) {
            _status.value = PlaybackStatus(PlaybackState.ERROR)
            return
        }

        // Relay only when the receiver cannot fetch the source itself. Public HTTP(S) media
        // without custom headers goes direct to Cast, avoiding needless phone bandwidth,
        // wake time, and a single point of failure during long playback.
        val proxy = DlnaProxyHolder.proxy(context)
        val proxyUrl = if (media.url.startsWith("content://") || media.url.startsWith("file://")) {
            proxy.publishLocal(media.url.toUri(), media.mimeType)
        } else if (media.headers.isNotEmpty() ||
            (!media.url.startsWith("http://") && !media.url.startsWith("https://"))
        ) {
            proxy.publish(media.url, media.headers, media.mimeType)
        } else {
            media.url
        }

        val ok = withContext(Dispatchers.IO) {
            client.loadMedia(
                contentUrl = proxyUrl,
                contentType = media.mimeType,
                title = media.title,
                artUrl = media.artUrl,
                startSeconds = media.startPositionMs / 1000.0,
            )
        }
        if (ok) {
            _status.value = PlaybackStatus(PlaybackState.PLAYING)
            startPolling()
        } else {
            _status.value = PlaybackStatus(PlaybackState.ERROR)
        }
    }

    override suspend fun play() {
        withContext(Dispatchers.IO) { client.play() }
    }

    override suspend fun pause() {
        withContext(Dispatchers.IO) { client.pause() }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) { client.stopMedia() }
        stopPolling()
        _status.value = PlaybackStatus(PlaybackState.STOPPED)
    }

    override suspend fun seekTo(positionMs: Long) {
        withContext(Dispatchers.IO) { client.seek(positionMs / 1000.0) }
    }

    override suspend fun setVolume(percent: Int) {
        val level = (percent / 100f).coerceIn(0f, 1f)
        withContext(Dispatchers.IO) { client.setVolume(level) }
    }

    suspend fun adjustVolume(delta: Float) {
        if (!ensureConnected()) return
        val level = ((client.receiverVolume ?: 0.5f) + delta).coerceIn(0f, 1f)
        withContext(Dispatchers.IO) { client.setVolume(level) }
    }

    override fun status(): Flow<PlaybackStatus> = _status.asStateFlow()

    override fun release() {
        stopPolling()
        stopHeartbeat()
        scope.launch(Dispatchers.IO) {
            runCatching { client.disconnect() }
        }
    }

    // -----------------------------------------------------------------------
    // Polling & heartbeat
    // -----------------------------------------------------------------------

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val st = client.pumpAndGetStatus(1500)
                    if (st != null) {
                        val state = mapState(st.playerState)
                        _status.value = PlaybackStatus(
                            state = state,
                            positionMs = (st.currentTime * 1000).toLong(),
                            durationMs = (st.duration * 1000).toLong(),
                        )
                        if (state == PlaybackState.STOPPED || state == PlaybackState.IDLE) {
                            // Media ended or was stopped; fall back to explicit polling
                        }
                    } else {
                        // No broadcast received; actively request status
                        val explicit = client.getMediaStatus()
                        if (explicit != null) {
                            _status.value = PlaybackStatus(
                                state = mapState(explicit.playerState),
                                positionMs = (explicit.currentTime * 1000).toLong(),
                                durationMs = (explicit.duration * 1000).toLong(),
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    client.pong()
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed: ${e.message}")
                    client.close()
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun mapState(s: String): PlaybackState = when (s.uppercase()) {
        "PLAYING" -> PlaybackState.PLAYING
        "PAUSED" -> PlaybackState.PAUSED
        "BUFFERING" -> PlaybackState.BUFFERING
        "IDLE" -> PlaybackState.IDLE
        else -> PlaybackState.IDLE
    }

    companion object {
        private const val TAG = "GoogleCastTarget"
        private const val POLL_INTERVAL_MS = 1000L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }
}
