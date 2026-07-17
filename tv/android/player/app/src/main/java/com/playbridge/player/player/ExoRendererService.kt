package com.playbridge.player.player

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.net.Uri
import android.media.audiofx.LoudnessEnhancer
import android.view.Surface
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.playbridge.shared.player.ExoPlayerEngine
import com.playbridge.shared.protocol.decodePlayPayloadListJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import playbridge.PlayPayload
import kotlin.math.roundToInt

/**
 * Headless ExoPlayer renderer for the permanent player host. All binder calls are serialized
 * onto the main looper because Media3 and Surface ownership are main-thread confined.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExoRendererService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: ExoPlayerEngine? = null
    private var callback: IRendererCallback? = null
    private var surface: Surface? = null
    private var sessionId = 0L
    private var playerListener: Player.Listener? = null
    private var stateJob: kotlinx.coroutines.Job? = null
    private var currentTitle: String? = null
    private var playWhenReady = false
    private var readySessionId = 0L
    private var firstFrameSessionId = 0L
    private var endedSessionId = 0L
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSessionId = 0
    private var audioBoostEnabled = false

    private val binder = object : IRendererService.Stub() {
        override fun setCallback(callback: IRendererCallback?) = onMain {
            this@ExoRendererService.callback = callback
        }

        override fun prepare(request: Bundle?, requestedSessionId: Long) = onMain {
            if (request == null || requestedSessionId <= sessionId) return@onMain
            sessionId = requestedSessionId
            playWhenReady = false
            readySessionId = 0L
            firstFrameSessionId = 0L
            endedSessionId = 0L
            val payload = request.getString(RendererProtocol.KEY_PAYLOAD_JSON)
                ?.let(::decodePlayPayloadListJson)
                ?.firstOrNull()
            if (payload == null) {
                sendError("Missing or invalid PlayPayload")
                return@onMain
            }
            currentTitle = payload.title

            val renderer = engine ?: ExoPlayerEngine(applicationContext).also { engine = it }
            scope.launch {
                runCatching {
                    renderer.load(payload)
                    renderer.getExoPlayer()?.let(::installPlayerListener)
                    observeState(renderer)
                    surface?.let { renderer.getExoPlayer()?.setVideoSurface(it) }
                    if (playWhenReady) renderer.play()
                }.onFailure { error -> sendError(error.message ?: "Exo renderer failed") }
            }
        }

        override fun attachSurface(newSurface: Surface?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            surface = newSurface
            engine?.getExoPlayer()?.setVideoSurface(newSurface)
        }

        override fun detachSurface(requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            surface = null
            engine?.getExoPlayer()?.clearVideoSurface()
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
            if (isCurrent(requestedSessionId)) engine?.seek(positionMs)
        }

        override fun setPlaybackSpeed(speed: Float, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) engine?.setRate(speed.coerceIn(0.25f, 4f))
        }

        override fun setVideoScaling(mode: String?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            engine?.getExoPlayer()?.videoScalingMode = when (mode) {
                "Fill", "Zoom" -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                else -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
        }

        override fun setLooping(enabled: Boolean, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) {
                engine?.getExoPlayer()?.repeatMode = if (enabled) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
            }
        }

        override fun setAudioBoost(enabled: Boolean, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            audioBoostEnabled = enabled
            applyAudioBoost()
        }

        override fun setSubtitleDelay(delayMs: Long, requestedSessionId: Long) = Unit

        override fun setAudioTrack(trackId: String?, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) selectTrack(C.TRACK_TYPE_AUDIO, trackId)
        }

        override fun setSubtitleTrack(trackId: String?, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) selectTrack(C.TRACK_TYPE_TEXT, trackId)
        }

        override fun addExternalSubtitle(
            url: String?,
            language: String?,
            requestedSessionId: Long,
        ) = onMain {
            if (!isCurrent(requestedSessionId) || url.isNullOrBlank()) return@onMain
            attachExternalSubtitle(url, language)
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
        mainHandler.post { releaseEngine() }
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private fun installPlayerListener(player: androidx.media3.exoplayer.ExoPlayer) {
        playerListener?.let(player::removeListener)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (readySessionId == sessionId && firstFrameSessionId != sessionId) {
                    firstFrameSessionId = sessionId
                    sendEvent(RendererProtocol.EVENT_FIRST_FRAME)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                sendError(error.errorCodeName)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> sendReadyOnce()
                    Player.STATE_ENDED -> if (
                        readySessionId == sessionId && endedSessionId != sessionId
                    ) {
                        endedSessionId = sessionId
                        sendEvent(RendererProtocol.EVENT_ENDED)
                    }
                    else -> Unit
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                sendTracks(tracks)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                sendVideoSize(videoSize)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioBoostEnabled) applyAudioBoost(audioSessionId)
            }
        }
        playerListener = listener
        player.addListener(listener)
        if (player.playbackState == Player.STATE_READY) sendReadyOnce()
        sendVideoSize(player.videoSize)
        sendTracks(player.currentTracks)
    }

    private fun sendVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        val pixelRatio = videoSize.pixelWidthHeightRatio
            .takeIf { it.isFinite() && it > 0f }
            ?: 1f
        val displayWidth = (videoSize.width * pixelRatio).roundToInt().coerceAtLeast(1)
        sendEvent(RendererProtocol.EVENT_VIDEO_SIZE, Bundle().apply {
            putInt(RendererProtocol.KEY_VIDEO_WIDTH, displayWidth)
            putInt(RendererProtocol.KEY_VIDEO_HEIGHT, videoSize.height)
        })
    }

    private fun sendReadyOnce() {
        if (readySessionId == sessionId) return
        readySessionId = sessionId
        sendEvent(RendererProtocol.EVENT_READY)
    }

    private fun selectTrack(trackType: Int, trackId: String?) {
        val player = engine?.getExoPlayer() ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        when (trackId) {
            null, "off", "none" -> builder
                .setTrackTypeDisabled(trackType, true)
                .clearOverridesOfType(trackType)
            "auto" -> builder
                .setTrackTypeDisabled(trackType, false)
                .clearOverridesOfType(trackType)
            else -> {
                val parts = trackId.split(":")
                val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return
                val trackIndex = parts.getOrNull(1)?.toIntOrNull() ?: return
                val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
                if (group.type != trackType || trackIndex !in 0 until group.length) return
                builder
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
                    )
            }
        }
        player.trackSelectionParameters = builder.build()
        sendTracks(player.currentTracks)
    }

    private fun attachExternalSubtitle(url: String, language: String?) {
        val player = engine?.getExoPlayer() ?: return
        val mediaItem = player.currentMediaItem ?: return
        val existing = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setMimeType(subtitleMimeType(url))
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val position = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = playWhenReady
        player.setMediaItem(
            mediaItem.buildUpon().setSubtitleConfigurations(existing + subtitle).build(),
            position,
        )
        player.prepare()
        if (shouldPlay) player.play()
    }

    private fun applyAudioBoost(sessionId: Int = engine?.getExoPlayer()?.audioSessionId ?: 0) {
        if (!audioBoostEnabled) {
            loudnessEnhancer?.enabled = false
            return
        }
        if (sessionId == 0) return
        runCatching {
            if (loudnessEnhancer == null || loudnessSessionId != sessionId) {
                loudnessEnhancer?.release()
                loudnessEnhancer = LoudnessEnhancer(sessionId)
                loudnessSessionId = sessionId
            }
            loudnessEnhancer?.setTargetGain(2_000)
            loudnessEnhancer?.enabled = true
        }.onFailure { Log.w(TAG, "Audio boost unavailable", it) }
    }

    private fun subtitleMimeType(url: String): String = when (
        url.substringBefore('?').substringAfterLast('.', missingDelimiterValue = "").lowercase()
    ) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun sendTracks(tracks: Tracks) {
        val audio = arrayListOf<Bundle>()
        val subtitles = arrayListOf<Bundle>()
        audio += trackBundle("auto", "Auto / Default", null, selected = false)
        subtitles += trackBundle("off", "Off", null, selected = false)
        tracks.groups.forEachIndexed { groupIndex, group ->
            val target = when (group.type) {
                C.TRACK_TYPE_AUDIO -> audio
                C.TRACK_TYPE_TEXT -> subtitles
                else -> return@forEachIndexed
            }
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                target += trackBundle(
                    id = "$groupIndex:$trackIndex",
                    label = format.label ?: format.language ?: "Track ${trackIndex + 1}",
                    language = format.language,
                    selected = group.isTrackSelected(trackIndex),
                )
            }
        }
        audio.first().putBoolean(
            RendererProtocol.KEY_TRACK_SELECTED,
            audio.drop(1).none { it.getBoolean(RendererProtocol.KEY_TRACK_SELECTED) },
        )
        subtitles.first().putBoolean(
            RendererProtocol.KEY_TRACK_SELECTED,
            subtitles.drop(1).none { it.getBoolean(RendererProtocol.KEY_TRACK_SELECTED) },
        )
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
        engine?.getExoPlayer()?.let { player ->
            playerListener?.let(player::removeListener)
        }
        playerListener = null
        surface = null
        engine?.release()
        engine = null
        currentTitle = null
        playWhenReady = false
        readySessionId = 0L
        firstFrameSessionId = 0L
        endedSessionId = 0L
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        loudnessSessionId = 0
        audioBoostEnabled = false
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeState(renderer: ExoPlayerEngine) {
        stateJob?.cancel()
        stateJob = scope.launch {
            combine(renderer.state, renderer.position, renderer.duration) { state, position, duration ->
                Triple(state, position, duration)
            }.distinctUntilChanged().sample(250).collect { (state, position, duration) ->
                val stateName = when (state) {
                    com.playbridge.shared.player.PlaybackState.Buffering -> "buffering"
                    com.playbridge.shared.player.PlaybackState.Playing -> "playing"
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

    private companion object {
        const val TAG = "ExoRendererService"
    }
}
