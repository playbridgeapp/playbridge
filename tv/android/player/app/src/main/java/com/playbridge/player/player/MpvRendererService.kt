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

internal fun findAddedSubtitleTrackId(
    currentTracks: Iterable<Pair<String, String>>,
    existingTrackIds: Set<String>,
    expectedLabel: String,
): String? = currentTracks.firstOrNull { (id, label) ->
    id !in existingTrackIds && label == expectedLabel
}?.first

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
    private var selectedVideoTrackId: String? = null
    private var selectedAudioTrackId: String? = null
    private var selectedSubtitleTrackId: String? = null
    private var tracksJob: kotlinx.coroutines.Job? = null
    private var pendingPayload: PlayPayload? = null
    private var pendingPayloadSessionId = 0L
    private var fileReadySessionId = 0L
    private var pendingSeekMs: Long? = null
    private var pendingExternalSubtitle: PendingExternalSubtitle? = null
    private var externalSubtitleTimeout: Runnable? = null
    private val externalSubtitleTrackIds = linkedSetOf<String>()
    private var externalSubtitleRequestNumber = 0L
    private var videoQualityMaxHeight = 0
    private var desiredPlaybackSpeed = 1f
    private var desiredScalingMode = "Fit"
    private var desiredLooping = false
    private var audioBoostEnabled = false
    private var lastCapabilitiesLive: Boolean? = null

    private val binder = object : IRendererService.Stub() {
        override fun setCallback(callback: IRendererCallback?) = onMain {
            this@MpvRendererService.callback = callback
        }

        override fun prepare(request: Bundle?, requestedSessionId: Long) = onMain {
            if (request == null || requestedSessionId <= sessionId) return@onMain
            clearPendingExternalSubtitle()
            externalSubtitleTrackIds.clear()
            sessionId = requestedSessionId
            firstFrameSession = 0L
            playWhenReady = false
            endedSessionId = 0L
            selectedVideoTrackId = null
            selectedAudioTrackId = null
            selectedSubtitleTrackId = null
            fileReadySessionId = 0L
            pendingSeekMs = null
            lastCapabilitiesLive = null
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
            if (!isCurrent(requestedSessionId)) return@onMain
            desiredPlaybackSpeed = speed.coerceIn(0.25f, 4f)
            engine?.setRate(desiredPlaybackSpeed)
        }

        override fun setVideoScaling(mode: String?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId) || mode == null) return@onMain
            desiredScalingMode = mode
            engine?.setVideoScale(mode)
        }

        override fun setLooping(enabled: Boolean, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            desiredLooping = enabled
            engine?.setLooping(enabled)
        }

        override fun setAudioBoost(enabled: Boolean, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            audioBoostEnabled = enabled
            applyAudioBoost()
        }

        override fun setSubtitleDelay(delayMs: Long, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) engine?.setSubtitleDelay(delayMs)
        }

        override fun setVideoQuality(maxHeight: Int, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            videoQualityMaxHeight = maxHeight.coerceAtLeast(0)
            applyVideoQuality()
            sendTracks()
        }

        override fun setVideoTrack(trackId: String?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            selectedVideoTrackId = trackId?.takeUnless { it == "auto" }
            engine?.setVideoTrack(if (trackId == "auto") "auto" else selectedVideoTrackId)
            sendTracks()
        }

        override fun setAudioTrack(trackId: String?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            selectedAudioTrackId = trackId?.takeUnless { it == "auto" }
            engine?.setAudioTrack(if (trackId == "auto") "auto" else selectedAudioTrackId)
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
            val renderer = engine ?: return@onMain
            clearPendingExternalSubtitle()
            val trackLabel = "playbridge-external-${requestedSessionId}-${++externalSubtitleRequestNumber}"
            pendingExternalSubtitle = PendingExternalSubtitle(
                uri = url,
                sessionId = requestedSessionId,
                existingTrackIds = renderer.subtitleTracks.value.mapTo(linkedSetOf()) { it.id },
                trackLabel = trackLabel,
            )
            scope.launch {
                try {
                    renderer.attachExternalSubtitle(url, trackLabel)
                    if (pendingExternalSubtitle?.uri == url &&
                        pendingExternalSubtitle?.sessionId == requestedSessionId
                    ) {
                        scheduleExternalSubtitleTimeout(url, requestedSessionId)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    completeExternalSubtitleAttachment(url, requestedSessionId, trackId = null)
                }
            }
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
                            applyDesiredSettings(renderer)
                            sendCapabilities(renderer)
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
                    val isLive = duration <= 0L
                    if (lastCapabilitiesLive != isLive) {
                        lastCapabilitiesLive = isLive
                        sendCapabilities(renderer)
                    }
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
            combine(renderer.videoTracks, renderer.audioTracks, renderer.subtitleTracks) { _, _, _ -> Unit }
                .collectLatest {
                    completePendingExternalSubtitleIfPresent(renderer)
                    applyVideoQuality()
                    sendTracks()
                }
        }
    }

    private fun sendTracks() {
        val renderer = engine ?: return
        val tracksByHeight = renderer.videoTracks.value
            .filter { qualityHeight(it) > 0 }
            .groupBy(::qualityHeight)
            .mapValues { (_, tracks) -> tracks.maxByOrNull { it.bitrate ?: 0L }!! }
        val currentHeight = renderer.videoTracks.value
            .firstOrNull { it.id == selectedVideoTrackId }
            ?.let(::qualityHeight)
            ?: renderer.videoTracks.value.firstOrNull { it.selected }
            ?.let(::qualityHeight)
        val video = arrayListOf(
            trackBundle(
                "auto",
                currentHeight?.let { "Auto · ${it}p" } ?: "Auto",
                null,
                videoQualityMaxHeight == 0,
            ),
        )
        video += tracksByHeight.keys.sortedDescending().map { height ->
            trackBundle(
                "max:$height",
                "Up to ${height}p",
                null,
                videoQualityMaxHeight == height,
            )
        }
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
        subtitles += renderer.subtitleTracks.value
            .filterNot { it.id in externalSubtitleTrackIds }
            .map { track ->
                trackBundle(
                    track.id,
                    buildSubtitleTrackLabel(
                        label = track.label,
                        language = track.language,
                        fallback = "Subtitle ${track.id}",
                    ),
                    track.language,
                    selectedSubtitleTrackId == track.id,
                )
            }
        sendEvent(RendererProtocol.EVENT_TRACKS, Bundle().apply {
            putParcelableArrayList(RendererProtocol.KEY_VIDEO_TRACKS, video)
            putParcelableArrayList(RendererProtocol.KEY_AUDIO_TRACKS, audio)
            putParcelableArrayList(RendererProtocol.KEY_SUBTITLE_TRACKS, subtitles)
        })
        sendCapabilities(renderer)
    }

    private fun applyDesiredSettings(renderer: MpvPlayerEngine) {
        renderer.setRate(desiredPlaybackSpeed)
        renderer.setVideoScale(desiredScalingMode)
        renderer.setLooping(desiredLooping)
        applyAudioBoost()
        applyVideoQuality()
    }

    private fun applyAudioBoost() {
        engine?.setAudioFilter(if (audioBoostEnabled) "lavfi=[volume=6dB]" else "")
    }

    private fun applyVideoQuality() {
        val renderer = engine ?: return
        if (videoQualityMaxHeight == 0) {
            selectedVideoTrackId = null
            renderer.setVideoTrack("auto")
            return
        }
        val selected = renderer.videoTracks.value
            .filter { qualityHeight(it) <= videoQualityMaxHeight }
            .maxWithOrNull(compareBy<com.playbridge.shared.player.Track>(::qualityHeight)
                .thenBy { it.bitrate ?: 0L })
            ?: return
        if (selectedVideoTrackId != selected.id) {
            selectedVideoTrackId = selected.id
            renderer.setVideoTrack(selected.id)
        }
    }

    private fun sendCapabilities(renderer: MpvPlayerEngine) {
        val heights = renderer.videoTracks.value.map(::qualityHeight).filter { it > 0 }.distinct()
        val isLive = renderer.duration.value <= 0L
        sendEvent(RendererProtocol.EVENT_CAPABILITIES, Bundle().apply {
            putBoolean(RendererProtocol.KEY_IS_LIVE, isLive)
            putBoolean(RendererProtocol.KEY_IS_SEEKABLE, !isLive)
            putBoolean(RendererProtocol.KEY_SPEED_AVAILABLE, !isLive)
            putBoolean(RendererProtocol.KEY_SCALING_AVAILABLE, renderer.videoTracks.value.isNotEmpty())
            putBoolean(RendererProtocol.KEY_AUDIO_BOOST_AVAILABLE, renderer.audioTracks.value.isNotEmpty())
            putBoolean(RendererProtocol.KEY_QUALITY_AVAILABLE, heights.size > 1)
            putInt(
                RendererProtocol.KEY_CURRENT_VIDEO_HEIGHT,
                (renderer.videoTracks.value.firstOrNull { it.id == selectedVideoTrackId }
                    ?: renderer.videoTracks.value.firstOrNull { it.selected })
                    ?.let(::qualityHeight)
                    ?: 0,
            )
            putInt(RendererProtocol.KEY_QUALITY_MAX_HEIGHT, videoQualityMaxHeight)
        })
    }

    private fun qualityHeight(track: com.playbridge.shared.player.Track): Int =
        listOfNotNull(track.width, track.height).filter { it > 0 }.minOrNull() ?: 0

    private fun completePendingExternalSubtitleIfPresent(renderer: MpvPlayerEngine): Boolean {
        val pending = pendingExternalSubtitle ?: return false
        val trackId = findAddedSubtitleTrackId(
            renderer.subtitleTracks.value.map { it.id to it.label },
            pending.existingTrackIds,
            pending.trackLabel,
        ) ?: return false
        completeExternalSubtitleAttachment(pending.uri, pending.sessionId, trackId)
        return true
    }

    private fun scheduleExternalSubtitleTimeout(uri: String, requestedSessionId: Long) {
        externalSubtitleTimeout?.let(mainHandler::removeCallbacks)
        externalSubtitleTimeout = Runnable {
            val attached = engine?.let(::completePendingExternalSubtitleIfPresent) == true
            if (!attached) {
                completeExternalSubtitleAttachment(uri, requestedSessionId, trackId = null)
            }
        }.also { timeout ->
            mainHandler.postDelayed(timeout, EXTERNAL_SUBTITLE_ATTACH_TIMEOUT_MS)
        }
    }

    private fun completeExternalSubtitleAttachment(
        uri: String,
        requestedSessionId: Long,
        trackId: String?,
    ) {
        val pending = pendingExternalSubtitle ?: return
        if (pending.uri != uri || pending.sessionId != requestedSessionId) return
        externalSubtitleTimeout?.let(mainHandler::removeCallbacks)
        externalSubtitleTimeout = null
        pendingExternalSubtitle = null
        if (!isCurrent(requestedSessionId)) return
        if (trackId != null) {
            externalSubtitleTrackIds += trackId
            selectedSubtitleTrackId = trackId
        }
        sendEvent(RendererProtocol.EVENT_EXTERNAL_SUBTITLE_RESULT, Bundle().apply {
            putString(RendererProtocol.KEY_SUBTITLE_URI, uri)
            putBoolean(RendererProtocol.KEY_SUCCESS, trackId != null)
        })
    }

    private fun clearPendingExternalSubtitle() {
        externalSubtitleTimeout?.let(mainHandler::removeCallbacks)
        externalSubtitleTimeout = null
        pendingExternalSubtitle = null
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
        clearPendingExternalSubtitle()
        externalSubtitleTrackIds.clear()
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
        selectedVideoTrackId = null
        selectedAudioTrackId = null
        selectedSubtitleTrackId = null
        pendingPayload = null
        pendingPayloadSessionId = 0L
        fileReadySessionId = 0L
        pendingSeekMs = null
        lastCapabilitiesLive = null
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

    private data class PendingExternalSubtitle(
        val uri: String,
        val sessionId: Long,
        val existingTrackIds: Set<String>,
        val trackLabel: String,
    )

    private companion object {
        const val EXTERNAL_SUBTITLE_ATTACH_TIMEOUT_MS = 8_000L
    }
}
