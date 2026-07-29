package com.playbridge.sender.cast.browser

import android.content.Context
import android.util.Log
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastTarget
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.proxy.BrowserStreamRoute
import com.playbridge.sender.cast.proxy.CastableMedia
import com.playbridge.sender.cast.proxy.PackagedMedia
import com.playbridge.sender.cast.proxy.PhoneProxyUrls
import com.playbridge.sender.cast.proxy.PhoneSenderServices
import com.playbridge.sender.cast.proxy.StreamProxySettingsStore
import com.playbridge.sender.cast.proxy.StreamRouteMode
import com.playbridge.sender.cast.proxy.StreamRouteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Cast target backed by an approved browser session on the phone-hosted receiver.
 *
 * Packaging: local media always Via phone; remote honors the user's route with
 * silent fall back to Via phone when Via proxy is unavailable.
 * Live status is mirrored from host browser events for Remote / NOW.
 */
class BrowserCastTarget(
    private val context: Context,
    private val scope: CoroutineScope,
    val sessionId: String,
    override val name: String,
    private val routeMode: () -> StreamRouteMode = {
        StreamProxySettingsStore.load(context).defaultRoute
    },
    private val onRouteResolved: ((effective: StreamRouteMode, proxyFallback: Boolean) -> Unit)? = null,
) : CastTarget {

    override val id: String = sessionId
    override val kind: TargetKind = TargetKind.WEB_BROWSER

    override val capabilities: Set<Capability> = setOf(
        Capability.LOAD,
        Capability.PLAY_PAUSE,
        Capability.SEEK,
        Capability.STOP,
        Capability.VOLUME,
        Capability.NOW_PLAYING,
    )

    private val _status = MutableStateFlow(PlaybackStatus(PlaybackState.IDLE))
    private val routeService = StreamRouteService(context)
    private var statusJob: Job? = null
    private var volumeFraction: Double = 1.0

    /** Last packaging mode actually used for a load (for NOW / diagnostics). */
    @Volatile
    var lastEffectiveRoute: StreamRouteMode? = null
        private set

    @Volatile
    var lastProxyFallback: Boolean = false
        private set

    init {
        startStatusListener()
    }

    override suspend fun load(media: MediaItem) {
        val services = PhoneSenderServices.get()
            ?: error("Browser host unavailable")
        _status.value = PlaybackStatus(PlaybackState.BUFFERING)
        try {
            val packaged = packageMedia(media)
            services.loadBrowser(
                sessionId = sessionId,
                url = packaged.url,
                title = media.title,
                contentType = packaged.contentType ?: media.mimeType,
                posterUrl = media.artUrl,
                subtitleUrl = media.subtitles.firstOrNull()?.url,
                startPositionMs = media.startPositionMs.takeIf { it > 0L },
            )
            _status.value = PlaybackStatus(
                state = PlaybackState.PLAYING,
                positionMs = media.startPositionMs,
                durationMs = media.durationMs,
            )
        } catch (e: Exception) {
            Log.w(TAG, "browser load failed: ${e.message}")
            _status.value = PlaybackStatus(PlaybackState.ERROR)
            throw e
        }
    }

    override suspend fun play() {
        control("play")
        _status.value = _status.value.copy(state = PlaybackState.PLAYING)
    }

    override suspend fun pause() {
        control("pause")
        _status.value = _status.value.copy(state = PlaybackState.PAUSED)
    }

    override suspend fun stop() {
        control("stop")
        _status.value = PlaybackStatus(PlaybackState.STOPPED)
    }

    override suspend fun seekTo(positionMs: Long) {
        control("seek", positionMs.toDouble())
        _status.value = _status.value.copy(positionMs = positionMs)
    }

    override suspend fun setVolume(percent: Int) {
        volumeFraction = (percent.coerceIn(0, 100) / 100.0)
        control("set_volume", volumeFraction)
    }

    /** Relative volume change for Remote volume buttons (±0.05). */
    suspend fun adjustVolume(delta: Double) {
        volumeFraction = (volumeFraction + delta).coerceIn(0.0, 1.0)
        control("set_volume", volumeFraction)
    }

    override fun status(): Flow<PlaybackStatus> = _status.asStateFlow()

    override fun release() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun startStatusListener() {
        statusJob?.cancel()
        statusJob = scope.launch {
            val services = PhoneSenderServices.get() ?: return@launch
            services.events.collect { event ->
                applyHostEvent(event)
            }
        }
    }

    private fun applyHostEvent(event: JSONObject) {
        val kind = event.optString("event")
        val session = event.optJSONObject("session")
        val sessionIdFromEvent = session?.optString("sessionId")
            ?: event.optString("session_id").takeIf { it.isNotEmpty() }
            ?: event.optString("sessionId").takeIf { it.isNotEmpty() }
        if (sessionIdFromEvent != null && sessionIdFromEvent != sessionId) return

        when (kind) {
            "status", "ended", "error" -> {
                val statusObj = session?.optJSONObject("status")
                val stateRaw = statusObj?.optString("state")
                    ?: if (kind == "ended") "ended" else if (kind == "error") "error" else null
                if (stateRaw != null) {
                    val positionMs = statusObj?.optLong("positionMs", _status.value.positionMs)
                        ?: _status.value.positionMs
                    val durationMs = statusObj?.optLong("durationMs", _status.value.durationMs)
                        ?: _status.value.durationMs
                    val title = statusObj?.optString("title")?.takeIf { it.isNotEmpty() }
                    statusObj?.optDouble("volume", volumeFraction)?.let { volumeFraction = it }
                    _status.value = PlaybackStatus(
                        state = mapBrowserState(stateRaw),
                        positionMs = positionMs,
                        durationMs = durationMs,
                    )
                    if (title != null) {
                        // Title updates flow through CastSessionManager via media title on load;
                        // status position is the important signal for Remote.
                    }
                }
                if (kind == "error") {
                    _status.value = _status.value.copy(state = PlaybackState.ERROR)
                }
            }
            "disconnected" -> {
                _status.value = PlaybackStatus(PlaybackState.STOPPED)
            }
        }
    }

    private fun mapBrowserState(raw: String): PlaybackState = when (raw.lowercase()) {
        "playing" -> PlaybackState.PLAYING
        "paused" -> PlaybackState.PAUSED
        "buffering" -> PlaybackState.BUFFERING
        "stopped", "ended", "idle" -> PlaybackState.STOPPED
        "error", "autoplay_blocked" -> PlaybackState.ERROR
        else -> _status.value.state
    }

    private suspend fun control(action: String, value: Double? = null) {
        val services = PhoneSenderServices.get() ?: return
        runCatching {
            services.controlBrowser(sessionId = sessionId, action = action, value = value)
        }.onFailure {
            Log.w(TAG, "browser $action failed: ${it.message}")
        }
    }

    private suspend fun packageMedia(media: MediaItem): PackagedMedia {
        val isLocal = BrowserStreamRoute.isLocalMediaUrl(media.url)

        // Already a Via phone LAN URL (Rust `/s/...` or LocalProxy token).
        if (PhoneProxyUrls.isAnyPhoneProxyUrl(media.url)) {
            lastEffectiveRoute = StreamRouteMode.VIA_PHONE
            lastProxyFallback = false
            onRouteResolved?.invoke(StreamRouteMode.VIA_PHONE, false)
            return PackagedMedia(
                url = media.url,
                contentType = media.mimeType,
                headers = null,
            )
        }

        // Never Direct-cast raw origins to the browser (CORS/auth). Package Via phone
        // (Rust /s/ + JNI HttpURLConnection) unless Via proxy is selected and works.
        val requested = if (isLocal) StreamRouteMode.VIA_PHONE else routeMode()
        val effective = BrowserStreamRoute.effectiveMode(requested, isLocalMedia = isLocal)
        val castable = CastableMedia(
            url = media.url,
            headers = media.headers.takeIf { it.isNotEmpty() },
            contentType = media.mimeType,
            title = media.title,
            localUri = if (media.url.startsWith("content://") || media.url.startsWith("file://")) {
                android.net.Uri.parse(media.url)
            } else {
                null
            },
        )
        return try {
            val packaged = routeService.packageForCast(castable, effective)
            lastEffectiveRoute = effective
            lastProxyFallback = false
            onRouteResolved?.invoke(effective, false)
            packaged
        } catch (e: Exception) {
            if (effective == StreamRouteMode.VIA_PHONE) throw e
            Log.i(TAG, "Browser packaging fell back to Via phone: ${e.message}")
            val packaged = routeService.packageForCast(castable, StreamRouteMode.VIA_PHONE)
            lastEffectiveRoute = StreamRouteMode.VIA_PHONE
            lastProxyFallback = true
            onRouteResolved?.invoke(StreamRouteMode.VIA_PHONE, true)
            packaged
        }
    }

    companion object {
        private const val TAG = "BrowserCastTarget"
    }
}
