package com.playbridge.player.player

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.media.audiofx.LoudnessEnhancer
import android.view.Surface
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
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

internal fun isExternalSubtitleTrackId(id: String?): Boolean =
    id == EXTERNAL_SUBTITLE_TRACK_ID ||
        id?.substringAfterLast(':') == EXTERNAL_SUBTITLE_TRACK_ID

private const val EXTERNAL_SUBTITLE_TRACK_ID = "playbridge-external-subtitle"

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
    private var pendingPayload: PlayPayload? = null
    private var pendingPayloadSessionId = 0L
    private var pendingInitialSubtitleUri: String? = null
    private var pendingInitialSubtitleLabel: String? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessSessionId = 0
    private var audioBoostEnabled = false
    private var selectedVideoTrackId: String? = null
    private var videoQualityMaxHeight = 0
    private var desiredPlaybackSpeed = 1f
    private var desiredScalingMode = "Fit"
    private var desiredLooping = false
    private var pendingExternalSubtitle: PendingExternalSubtitle? = null
    private var externalSubtitleTimeout: Runnable? = null

    private val binder = object : IRendererService.Stub() {
        override fun setCallback(callback: IRendererCallback?) = onMain {
            this@ExoRendererService.callback = callback
        }

        override fun prepare(request: Bundle?, requestedSessionId: Long) = onMain {
            if (request == null || requestedSessionId <= sessionId) return@onMain
            clearPendingExternalSubtitle()
            sessionId = requestedSessionId
            playWhenReady = false
            readySessionId = 0L
            firstFrameSessionId = 0L
            endedSessionId = 0L
            selectedVideoTrackId = null
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
            pendingInitialSubtitleUri = request.getString(RendererProtocol.KEY_INITIAL_SUBTITLE_URI)
            pendingInitialSubtitleLabel = request.getString(RendererProtocol.KEY_INITIAL_SUBTITLE_LABEL)

            val renderer = engine ?: ExoPlayerEngine(applicationContext).also { engine = it }
            surface?.let {
                renderer.setVideoSurface(it)
                startPendingLoad(renderer, requestedSessionId)
            }
        }

        override fun attachSurface(newSurface: Surface?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            surface = newSurface
            engine?.let { renderer ->
                renderer.setVideoSurface(newSurface)
                if (newSurface != null) startPendingLoad(renderer, requestedSessionId)
            }
        }

        override fun detachSurface(requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            surface = null
            engine?.setVideoSurface(null)
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
            if (!isCurrent(requestedSessionId)) return@onMain
            desiredPlaybackSpeed = speed.coerceIn(0.25f, 4f)
            engine?.setRate(desiredPlaybackSpeed)
        }

        override fun setVideoScaling(mode: String?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            desiredScalingMode = mode ?: "Fit"
            engine?.getExoPlayer()?.videoScalingMode = when (mode) {
                "Zoom" -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                else -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
        }

        override fun setLooping(enabled: Boolean, requestedSessionId: Long) = onMain {
            if (isCurrent(requestedSessionId)) {
                desiredLooping = enabled
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

        override fun setVideoQuality(maxHeight: Int, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            videoQualityMaxHeight = maxHeight.coerceAtLeast(0)
            applyVideoQuality()
        }

        override fun setVideoTrack(trackId: String?, requestedSessionId: Long) = onMain {
            if (!isCurrent(requestedSessionId)) return@onMain
            selectedVideoTrackId = trackId?.takeUnless { it == "auto" }
            selectTrack(C.TRACK_TYPE_VIDEO, trackId)
        }

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
            clearPendingExternalSubtitle()
            pendingExternalSubtitle = PendingExternalSubtitle(url, requestedSessionId)
            scope.launch {
                try {
                    val renderer = engine ?: error("Exo renderer is unavailable")
                    renderer.attachExternalSubtitle(url, language)
                    if (pendingExternalSubtitle == PendingExternalSubtitle(url, requestedSessionId)) {
                        scheduleExternalSubtitleTimeout(url, requestedSessionId)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to attach external subtitle", error)
                    completeExternalSubtitleAttachment(url, requestedSessionId, success = false)
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
        mainHandler.post { releaseEngine() }
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private fun installPlayerListener(renderer: ExoPlayerEngine) {
        playerListener?.let(renderer::removePlayerListener)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (readySessionId == sessionId && firstFrameSessionId != sessionId) {
                    firstFrameSessionId = sessionId
                    Log.d(TAG, "First video frame rendered for session $sessionId")
                    sendEvent(RendererProtocol.EVENT_FIRST_FRAME)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                pendingExternalSubtitle?.let { pending ->
                    completeExternalSubtitleAttachment(
                        pending.uri,
                        pending.sessionId,
                        success = false,
                    )
                }
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
                val pending = pendingExternalSubtitle
                if (pending != null && selectExternalSubtitleTrack(tracks)) {
                    completeExternalSubtitleAttachment(
                        pending.uri,
                        pending.sessionId,
                        success = true,
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                sendVideoSize(videoSize)
            }

            override fun onCues(cueGroup: CueGroup) {
                sendEvent(RendererProtocol.EVENT_CUES, Bundle().apply {
                    putBundle(RendererProtocol.KEY_CUE_GROUP, cueGroup.toBundle())
                })
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioBoostEnabled) applyAudioBoost(audioSessionId)
            }
        }
        playerListener = listener
        renderer.addPlayerListener(listener)
    }

    private fun sendCurrentPlayerSnapshot(renderer: ExoPlayerEngine) {
        val player = renderer.getExoPlayer() ?: return
        if (player.playbackState == Player.STATE_READY) sendReadyOnce()
        sendVideoSize(player.videoSize)
        sendTracks(player.currentTracks)
        sendCapabilities(player)
        sendEvent(RendererProtocol.EVENT_CUES, Bundle().apply {
            putBundle(RendererProtocol.KEY_CUE_GROUP, player.currentCues.toBundle())
        })
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
        engine?.getExoPlayer()?.let(::sendCapabilities)
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

    private fun applyDesiredSettings(renderer: ExoPlayerEngine) {
        renderer.setRate(desiredPlaybackSpeed)
        val player = renderer.getExoPlayer() ?: return
        player.videoScalingMode = when (desiredScalingMode) {
            "Zoom" -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            else -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
        player.repeatMode = if (desiredLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        applyVideoQuality()
        applyAudioBoost()
    }

    private fun applyVideoQuality() {
        val player = engine?.getExoPlayer() ?: return
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (videoQualityMaxHeight > 0) {
            val maxLongEdge = ((videoQualityMaxHeight * 16L) / 9L).toInt().coerceAtLeast(1)
            // A square upper bound applies the same quality ceiling to landscape and portrait.
            builder.setMaxVideoSize(maxLongEdge, maxLongEdge)
        } else {
            builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        selectedVideoTrackId = null
        player.trackSelectionParameters = builder.build()
        sendTracks(player.currentTracks)
        sendCapabilities(player)
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
            loudnessEnhancer?.setTargetGain(600)
            loudnessEnhancer?.enabled = true
        }.onFailure { Log.w(TAG, "Audio boost unavailable", it) }
    }

    private fun sendTracks(tracks: Tracks) {
        val video = arrayListOf<Bundle>()
        val audio = arrayListOf<Bundle>()
        val subtitles = arrayListOf<Bundle>()
        val player = engine?.getExoPlayer()
        val currentHeight = player?.videoSize?.let { size ->
            listOf(size.width, size.height).filter { it > 0 }.minOrNull()
        }
        video += trackBundle(
            "auto",
            currentHeight?.let { "Auto · ${it}p" } ?: "Auto",
            null,
            videoQualityMaxHeight == 0,
        )
        audio += trackBundle("auto", "Auto / Default", null, selected = false)
        subtitles += trackBundle("off", "Off", null, selected = false)
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_VIDEO) return@forEachIndexed
            val target = when (group.type) {
                C.TRACK_TYPE_AUDIO -> audio
                C.TRACK_TYPE_TEXT -> subtitles
                else -> return@forEachIndexed
            }
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                if (!group.isTrackSupported(trackIndex)) continue
                if (group.type == C.TRACK_TYPE_TEXT && isExternalSubtitleTrackId(format.id)) {
                    continue
                }
                target += trackBundle(
                    id = "$groupIndex:$trackIndex",
                    label = when (group.type) {
                        C.TRACK_TYPE_AUDIO -> buildAudioTrackLabel(
                            label = format.label,
                            language = format.language,
                            codec = format.codecs ?: format.sampleMimeType?.substringAfter('/'),
                            channelCount = format.channelCount,
                            fallback = "Audio ${trackIndex + 1}",
                        )
                        C.TRACK_TYPE_TEXT -> buildSubtitleTrackLabel(
                            label = format.label,
                            language = format.language,
                            fallback = "Subtitle ${trackIndex + 1}",
                        )
                        else -> format.label ?: format.language ?: "Track ${trackIndex + 1}"
                    },
                    language = format.language,
                    selected = group.isTrackSelected(trackIndex),
                )
            }
        }
        val heights = tracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter(group::isTrackSupported)
                    .map { qualityHeight(group.getTrackFormat(it).width, group.getTrackFormat(it).height) }
            }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
            .toList()
        heights.forEach { height ->
            video += trackBundle(
                id = "max:$height",
                label = "Up to ${height}p",
                language = null,
                selected = videoQualityMaxHeight == height,
            )
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
            putParcelableArrayList(RendererProtocol.KEY_VIDEO_TRACKS, video)
            putParcelableArrayList(RendererProtocol.KEY_AUDIO_TRACKS, audio)
            putParcelableArrayList(RendererProtocol.KEY_SUBTITLE_TRACKS, subtitles)
        })
        player?.let(::sendCapabilities)
    }

    private fun sendCapabilities(player: Player) {
        val supportedVideoHeights = player.currentTracks.groups.asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter(group::isTrackSupported)
                    .map { qualityHeight(group.getTrackFormat(it).width, group.getTrackFormat(it).height) }
            }
            .filter { it > 0 }
            .distinct()
            .count()
        val hasVideo = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        val hasAudio = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
        sendEvent(RendererProtocol.EVENT_CAPABILITIES, Bundle().apply {
            putBoolean(RendererProtocol.KEY_IS_LIVE, player.isCurrentMediaItemLive)
            putBoolean(RendererProtocol.KEY_IS_SEEKABLE, player.isCurrentMediaItemSeekable)
            putBoolean(RendererProtocol.KEY_SPEED_AVAILABLE, !player.isCurrentMediaItemLive)
            putBoolean(RendererProtocol.KEY_SCALING_AVAILABLE, hasVideo)
            putBoolean(RendererProtocol.KEY_AUDIO_BOOST_AVAILABLE, hasAudio)
            putBoolean(RendererProtocol.KEY_QUALITY_AVAILABLE, supportedVideoHeights > 1)
            putInt(
                RendererProtocol.KEY_CURRENT_VIDEO_HEIGHT,
                qualityHeight(player.videoSize.width, player.videoSize.height),
            )
            putInt(RendererProtocol.KEY_QUALITY_MAX_HEIGHT, videoQualityMaxHeight)
        })
    }

    private fun qualityHeight(width: Int, height: Int): Int =
        listOf(width, height).filter { it > 0 }.minOrNull() ?: 0

    private fun buildAudioTrackLabel(
        label: String?,
        language: String?,
        codec: String?,
        channelCount: Int,
        fallback: String,
    ): String {
        val channelLabel = when (channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            in 3..Int.MAX_VALUE -> "$channelCount channels"
            else -> null
        }
        return listOfNotNull(
            label?.takeIf { it.isNotBlank() } ?: language?.takeIf { it.isNotBlank() } ?: fallback,
            codec?.takeIf { it.isNotBlank() }?.uppercase(),
            channelLabel,
        ).distinct().joinToString(" • ")
    }

    private fun selectExternalSubtitleTrack(tracks: Tracks): Boolean {
        val player = engine?.getExoPlayer() ?: return false
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (trackIndex in 0 until group.length) {
                if (!isExternalSubtitleTrackId(group.getTrackFormat(trackIndex).id)) continue
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(
                            group.mediaTrackGroup,
                            trackIndex,
                        ),
                    )
                    .build()
                return true
            }
        }
        return false
    }

    private fun scheduleExternalSubtitleTimeout(uri: String, requestedSessionId: Long) {
        externalSubtitleTimeout?.let(mainHandler::removeCallbacks)
        externalSubtitleTimeout = Runnable {
            val attached = engine?.getExoPlayer()?.currentTracks?.let(::selectExternalSubtitleTrack) == true
            completeExternalSubtitleAttachment(uri, requestedSessionId, attached)
        }.also { timeout ->
            mainHandler.postDelayed(timeout, EXTERNAL_SUBTITLE_ATTACH_TIMEOUT_MS)
        }
    }

    private fun completeExternalSubtitleAttachment(
        uri: String,
        requestedSessionId: Long,
        success: Boolean,
    ) {
        val pending = pendingExternalSubtitle ?: return
        if (pending.uri != uri || pending.sessionId != requestedSessionId) return
        externalSubtitleTimeout?.let(mainHandler::removeCallbacks)
        externalSubtitleTimeout = null
        pendingExternalSubtitle = null
        if (!isCurrent(requestedSessionId)) return
        if (success) {
            Log.i(TAG, "External subtitle track attached")
        } else {
            Log.w(TAG, "External subtitle track is unavailable")
        }
        sendEvent(RendererProtocol.EVENT_EXTERNAL_SUBTITLE_RESULT, Bundle().apply {
            putString(RendererProtocol.KEY_SUBTITLE_URI, uri)
            putBoolean(RendererProtocol.KEY_SUCCESS, success)
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
        stateJob?.cancel()
        stateJob = null
        engine?.let { renderer ->
            playerListener?.let(renderer::removePlayerListener)
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
        pendingPayload = null
        pendingPayloadSessionId = 0L
        pendingInitialSubtitleUri = null
        pendingInitialSubtitleLabel = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        loudnessSessionId = 0
        audioBoostEnabled = false
        selectedVideoTrackId = null
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

    private fun startPendingLoad(renderer: ExoPlayerEngine, requestedSessionId: Long) {
        if (!isCurrent(requestedSessionId) || pendingPayloadSessionId != requestedSessionId) return
        val payload = pendingPayload ?: return
        val rendererSurface = surface ?: return
        val initialSubtitleUri = pendingInitialSubtitleUri
        val initialSubtitleLabel = pendingInitialSubtitleLabel
        pendingPayload = null
        pendingPayloadSessionId = 0L
        pendingInitialSubtitleUri = null
        pendingInitialSubtitleLabel = null
        renderer.setVideoSurface(rendererSurface)
        scope.launch {
            runCatching {
                installPlayerListener(renderer)
                if (initialSubtitleUri != null) {
                    pendingExternalSubtitle = PendingExternalSubtitle(
                        initialSubtitleUri,
                        requestedSessionId,
                    )
                }
                renderer.load(payload, initialSubtitleUri, initialSubtitleLabel)
                applyDesiredSettings(renderer)
                if (initialSubtitleUri != null) {
                    if (pendingExternalSubtitle == PendingExternalSubtitle(
                            initialSubtitleUri,
                            requestedSessionId,
                        )
                    ) {
                        scheduleExternalSubtitleTimeout(initialSubtitleUri, requestedSessionId)
                    }
                }
                sendCurrentPlayerSnapshot(renderer)
                observeState(renderer)
                if (playWhenReady) renderer.play() else renderer.pause()
            }.onFailure { error -> sendError(error.message ?: "Exo renderer failed") }
        }
    }

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
        const val EXTERNAL_SUBTITLE_ATTACH_TIMEOUT_MS = 8_000L
    }

    private data class PendingExternalSubtitle(
        val uri: String,
        val sessionId: Long,
    )
}
