package com.playbridge.player.player

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.view.Surface
import com.playbridge.shared.player.MpvPlayerEngine
import com.playbridge.shared.player.PlaybackState
import com.playbridge.shared.protocol.decodePlayPayloadListJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import playbridge.PlayPayload

/** Headless MPV renderer for the permanent host process boundary. */
class MpvRendererService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: MpvPlayerEngine? = null
    private var callback: IRendererCallback? = null
    private var surface: Surface? = null
    private var sessionId = 0L
    private var stateJob: kotlinx.coroutines.Job? = null
    private var firstFrameSession = 0L
    private var currentTitle: String? = null
    private var playWhenReady = false
    private var endedSessionId = 0L
    private var selectedAudioTrackId: String? = null
    private var selectedSubtitleTrackId: String? = null
    private var tracksJob: kotlinx.coroutines.Job? = null
    private var pendingPayload: PlayPayload? = null
    private var pendingPayloadSessionId = 0L
    private var fileReadySessionId = 0L
    private var pendingSeekMs: Long? = null

    private val binder = object : IRendererService.Stub() {
        override fun setCallback(callback: IRendererCallback?) = onMain {
            this@MpvRendererService.callback = callback
        }

        override fun prepare(request: Bundle?, requestedSessionId: Long) = onMain {
            if (request == null || requestedSessionId <= sessionId) return@onMain
            sessionId = requestedSessionId
            firstFrameSession = 0L
            playWhenReady = false
            endedSessionId = 0L
            selectedAudioTrackId = null
            selectedSubtitleTrackId = null
            fileReadySessionId = 0L
            pendingSeekMs = null
            val payload = request.getString(RendererProtocol.KEY_PAYLOAD_JSON)
                ?.let(::decodePlayPayloadListJson)
                ?.firstOrNull()
            if (payload == null) {
                sendError("Missing or invalid PlayPayload")
                return@onMain
            }
            currentTitle = payload.title
            pendingPayload = payload
            pendingPayloadSessionId = requestedSessionId

            val renderer = engine ?: MpvPlayerEngine(applicationContext).also {
                engine = it
                observeState(it)
                observeTracks(it)
            }
            surface?.let {
                renderer.attachSurface(it)
                startPendingLoad(renderer, requestedSessionId)
            }
        }

        override fun attachSurface(newSurface: Surface?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            surface = newSurface
            if (newSurface != null) {
                engine?.let { renderer ->
                    renderer.attachSurface(newSurface)
                    startPendingLoad(renderer, requestedSessionId)
                }
            } else {
                engine?.detachSurface()
            }
        }

        override fun detachSurface(requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            surface = null
            engine?.detachSurface()
        }

        override fun play(requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) {
                playWhenReady = true
                engine?.play()
            }
        }

        override fun pause(requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) {
                playWhenReady = false
                engine?.pause()
            }
        }

        override fun seekTo(positionMs: Long, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            if (fileReadySessionId == requestedSessionId) {
                engine?.seek(positionMs)
            } else {
                // libmpv ignores or misapplies seeks sent before FILE_LOADED. Retain only the
                // newest request so resume and rapid remote seeks land at the intended position.
                pendingSeekMs = positionMs
            }
        }

        override fun setPlaybackSpeed(speed: Float, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) engine?.setRate(speed.coerceIn(0.25f, 4f))
        }

        override fun setVideoScaling(mode: String?, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId) && mode != null) engine?.setVideoScale(mode)
        }

        override fun setLooping(enabled: Boolean, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) engine?.setLooping(enabled)
        }

        override fun setAudioBoost(enabled: Boolean, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            val filter = if (enabled) {
                "lavfi=[acompressor=threshold=-21dB:ratio=9:attack=5:release=50:makeup=8dB]"
            } else {
                ""
            }
            engine?.setAudioFilter(filter)
        }

        override fun setSubtitleDelay(delayMs: Long, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) engine?.setSubtitleDelay(delayMs)
        }

        override fun setAudioTrack(trackId: String?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            selectedAudioTrackId = trackId?.takeUnless { it == "auto" }
            engine?.setAudioTrack(selectedAudioTrackId)
            sendTracks()
        }

        override fun setSubtitleTrack(trackId: String?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            selectedSubtitleTrackId = trackId?.takeUnless { it == "off" || it == "none" }
            engine?.setSubtitleTrack(selectedSubtitleTrackId)
            sendTracks()
        }

        override fun addExternalSubtitle(
            url: String?,
            language: String?,
            requestedSessionId: Long,
        ) = onMain {
            if (!isCurrent(requestedSessionId) || url.isNullOrBlank()) return@onMain
            scope.launch { engine?.attachExternalSubtitle(url, language) }
        }

        override fun stop(requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            engine?.stop()
            sendEvent(RendererProtocol.EVENT_STOPPED)
        }

        override fun release(requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            releaseEngine()
            sendEvent(RendererProtocol.EVENT_STOPPED)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseEngine()
        scope.cancel()
        super.onDestroy()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeState(renderer: MpvPlayerEngine) {
        stateJob?.cancel()
        stateJob = scope.launch {
            // Lifecycle transitions must never be sampled: FILE_LOADED -> PLAYBACK_RESTART can
            // happen in under 250 ms, which previously dropped READY and made resume seeks race.
            launch {
                renderer.state.collectLatest { state ->
                    when (state) {
                        PlaybackState.Ready -> {
                            fileReadySessionId = sessionId
                            pendingSeekMs?.let(renderer::seek)
                            pendingSeekMs = null
                            sendEvent(RendererProtocol.EVENT_READY)
                        }
                        PlaybackState.Playing -> {
                            if (fileReadySessionId == sessionId && firstFrameSession != sessionId) {
                                firstFrameSession = sessionId
                                sendEvent(RendererProtocol.EVENT_FIRST_FRAME)
                            }
                        }
                        is PlaybackState.Error -> sendError(state.msg)
                        PlaybackState.Ended -> {
                            // loadfile replacement reports END_FILE for the outgoing file after
                            // prepare() has already assigned the incoming session id. Only let an
                            // end advance the playlist after this session reached FILE_LOADED.
                            if (fileReadySessionId == sessionId && endedSessionId != sessionId) {
                                endedSessionId = sessionId
                                sendEvent(RendererProtocol.EVENT_ENDED)
                            }
                        }
                        else -> Unit
                    }
                }
            }

            // Position/duration updates are high-frequency and remain sampled for Binder health.
            launch {
                combine(renderer.state, renderer.position, renderer.duration) { state, position, duration ->
                    Triple(state, position, duration)
                }.distinctUntilChanged().sample(250).collectLatest { (state, position, duration) ->
                    val stateName = when (state) {
                        PlaybackState.Buffering -> "buffering"
                        PlaybackState.Playing -> if (fileReadySessionId == sessionId) {
                            "playing"
                        } else {
                            "buffering"
                        }
                        else -> "paused"
                    }
                    sendEvent(RendererProtocol.EVENT_STATE, Bundle().apply {
                        putString(RendererProtocol.KEY_STATE, stateName)
                        putLong(RendererProtocol.KEY_POSITION_MS, position.coerceAtLeast(0L))
                        putLong(RendererProtocol.KEY_DURATION_MS, duration.coerceAtLeast(0L))
                        putString(RendererProtocol.KEY_TITLE, currentTitle)
                    })
                }
            }
        }
    }

    private fun observeTracks(renderer: MpvPlayerEngine) {
        tracksJob?.cancel()
        tracksJob = scope.launch {
            combine(renderer.audioTracks, renderer.subtitleTracks) { _, _ -> Unit }
                .collectLatest { sendTracks() }
        }
    }

    private fun sendTracks() {
        val renderer = engine ?: return
        val audio = arrayListOf(
            trackBundle("auto", "Auto / Default", null, selectedAudioTrackId == null),
        )
        audio += renderer.audioTracks.value.map { track ->
            trackBundle(
                track.id,
                track.label,
                track.language,
                selectedAudioTrackId == track.id,
            )
        }
        val subtitles = arrayListOf(
            trackBundle("off", "Off", null, selectedSubtitleTrackId == null),
        )
        subtitles += renderer.subtitleTracks.value.map { track ->
            trackBundle(
                track.id,
                track.label,
                track.language,
                selectedSubtitleTrackId == track.id,
            )
        }
        sendEvent(RendererProtocol.EVENT_TRACKS, Bundle().apply {
            putParcelableArrayList(RendererProtocol.KEY_AUDIO_TRACKS, audio)
            putParcelableArrayList(RendererProtocol.KEY_SUBTITLE_TRACKS, subtitles)
        })
    }

    private fun trackBundle(
        id: String,
        label: String,
        language: String?,
        selected: Boolean,
    ) = Bundle().apply {
        putString(RendererProtocol.KEY_TRACK_ID, id)
        putString(RendererProtocol.KEY_TRACK_LABEL, label)
        putString(RendererProtocol.KEY_TRACK_LANGUAGE, language)
        putBoolean(RendererProtocol.KEY_TRACK_SELECTED, selected)
    }

    private fun releaseEngine() {
        stateJob?.cancel()
        stateJob = null
        tracksJob?.cancel()
        tracksJob = null
        surface = null
        engine?.release()
        engine = null
        currentTitle = null
        playWhenReady = false
        endedSessionId = 0L
        selectedAudioTrackId = null
        selectedSubtitleTrackId = null
        pendingPayload = null
        pendingPayloadSessionId = 0L
        fileReadySessionId = 0L
        pendingSeekMs = null
    }

    /**
     * MPV's MediaCodec output must have a native surface before `loadfile` is queued. Loading
     * first makes mpv settle on `vo=null`; attaching the host surface afterward can then leave a
     * healthy decoder playing behind a permanently black host surface.
     */
    private fun startPendingLoad(renderer: MpvPlayerEngine, requestedSessionId: Long) {
        if (!isCurrent(requestedSessionId) || pendingPayloadSessionId != requestedSessionId) return
        val payload = pendingPayload ?: return
        pendingPayload = null
        pendingPayloadSessionId = 0L
        scope.launch {
            runCatching {
                renderer.load(payload)
                if (playWhenReady) renderer.play()
            }.onFailure { error -> sendError(error.message ?: "MPV renderer failed") }
        }
    }

    private fun isCurrent(requestedSessionId: Long): Boolean =
        requestedSessionId > 0L && requestedSessionId == sessionId

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun sendError(message: String) {
        sendEvent(RendererProtocol.EVENT_ERROR, Bundle().apply {
            putString(RendererProtocol.KEY_ERROR, message)
        })
    }

    private fun sendEvent(event: String, extras: Bundle = Bundle()) {
        val eventBundle = Bundle(extras).apply {
            putString(RendererProtocol.KEY_EVENT, event)
            putLong(RendererProtocol.KEY_SESSION_ID, sessionId)
        }
        try {
            callback?.onRendererEvent(eventBundle)
        } catch (_: RemoteException) {
            callback = null
        }
    }
}
