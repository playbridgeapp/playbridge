package com.playbridge.sender.cast.roku

import android.content.Context
import androidx.core.net.toUri
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastTarget
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.sender.cast.proxy.StreamRouteMode
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

class RokuCastTarget(
    val device: TvDevice,
    private val scope: CoroutineScope,
    private val context: Context,
    private val client: RokuClient = RokuClient(),
) : CastTarget {

    override val id: String = device.uuid.ifEmpty { "roku://${device.ip}:${device.port}" }
    override val name: String = device.name
    override val kind: TargetKind = TargetKind.ROKU

    override val capabilities: Set<Capability> = setOf(
        Capability.LOAD,
        Capability.PLAY_PAUSE,
        Capability.STOP,
        Capability.VOLUME,
        Capability.REMOTE,
        Capability.NOW_PLAYING,
    )

    private val _status = MutableStateFlow(PlaybackStatus(PlaybackState.IDLE))
    private var pollJob: Job? = null
    @Volatile private var activeLoadEpoch: Long? = null

    override suspend fun load(media: MediaItem) {
        stopPolling()
        activeLoadEpoch = media.loadEpoch
        _status.value = PlaybackStatus(PlaybackState.BUFFERING, loadEpoch = media.loadEpoch)
        val reachableUrl = resolveLoadUrl(media)
        val ok = client.launchMedia(
            host = device.ip,
            port = device.port.takeIf { it > 0 } ?: 8060,
            url = reachableUrl,
            title = media.title
        )
        if (ok) {
            _status.value = PlaybackStatus(PlaybackState.PLAYING, loadEpoch = media.loadEpoch)
            startPolling(media.loadEpoch)
        } else {
            _status.value = PlaybackStatus(PlaybackState.ERROR, loadEpoch = media.loadEpoch)
            throw IllegalStateException("Roku refused media launch")
        }
    }

    private fun resolveLoadUrl(media: MediaItem): String {
        when (media.effectiveRoute) {
            StreamRouteMode.DIRECT -> return media.url
            StreamRouteMode.VIA_PROXY -> return media.url
            StreamRouteMode.VIA_PHONE -> return media.url
            null -> Unit
        }
        val proxy = DlnaProxyHolder.proxy(context)
        return if (media.url.startsWith("content://") || media.url.startsWith("file://")) {
            proxy.publishLocal(media.url.toUri(), media.mimeType)
        } else if (media.headers.isNotEmpty()) {
            proxy.publish(media.url, media.headers, media.mimeType)
        } else {
            media.url
        }
    }

    override suspend fun play() {
        sendKeypress("Play")
    }

    override suspend fun pause() {
        sendKeypress("Pause")
    }

    override suspend fun stop() {
        sendKeypress("Stop")
        stopPolling()
        _status.value = PlaybackStatus(PlaybackState.STOPPED, loadEpoch = activeLoadEpoch)
    }

    override suspend fun seekTo(positionMs: Long) {
        // Roku ECP does not support arbitrary Seek over keypress easily; no-op or fast forward/rewind
    }

    override suspend fun setVolume(percent: Int) = Unit

    fun sendKeypress(key: String) {
        scope.launch(Dispatchers.IO) {
            client.sendKeypress(
                host = device.ip,
                port = device.port.takeIf { it > 0 } ?: 8060,
                key = key
            )
        }
    }

    override fun status(): Flow<PlaybackStatus> = _status.asStateFlow()

    private fun startPolling(loadEpoch: Long? = activeLoadEpoch) {
        stopPolling()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val st = client.getMediaPlayerStatus(
                    host = device.ip,
                    port = device.port.takeIf { it > 0 } ?: 8060
                )
                if (st != null) {
                    val state = when (st.state.lowercase()) {
                        "play" -> PlaybackState.PLAYING
                        "pause" -> PlaybackState.PAUSED
                        "buffer" -> PlaybackState.BUFFERING
                        "none", "close" -> PlaybackState.STOPPED
                        else -> PlaybackState.PLAYING
                    }
                    _status.value = PlaybackStatus(
                        state = state,
                        positionMs = st.positionMs,
                        durationMs = st.durationMs,
                        loadEpoch = loadEpoch,
                    )
                    if (state == PlaybackState.STOPPED) {
                        break
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun release() {
        stopPolling()
    }
}
