package com.playbridge.sender.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.playbridge.shared.logging.redactUrlForLog
import com.playbridge.shared.player.MpvPlayerEngine
import com.playbridge.shared.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import playbridge.PlayPayload

/**
 * mpv presented as a Media3 [Player], so [PlayerActivity], [PagePlayerSession], the background
 * media notification and PlayerView drive it exactly as they drive ExoPlayer.
 *
 * mpv opens one item at a time; this class keeps the playlist and advances it. It renders into
 * the PlayerView's TextureView and draws subtitles itself.
 */
@UnstableApi
class MpvMediaPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()), MPVLib.EventObserver {
    private companion object {
        const val TAG = "PB_PLAYER"
        const val MPV_FORMAT_INT64 = 4
        const val ENGINE_RETRIES = 15
        const val ENGINE_RETRY_MS = 200L
        /** Open without a picture (audio only) if no surface arrives, e.g. while backgrounded. */
        const val SURFACE_WAIT_MS = 1_500L
        /** Rotation resizes the view several times; redraw once it settles. */
        const val RESIZE_REDRAW_MS = 150L

        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            Player.COMMAND_SET_VIDEO_SURFACE,
        ).build()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: MpvPlayerEngine? = null
    private var engineAttempts = 0
    private var released = false

    private val items = mutableListOf<MediaItem>()
    private val uids = mutableListOf<Any>()
    private var index = 0
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var speed = 1f
    private var trackParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
    private var tracks = Tracks.EMPTY
    private var videoWidth = 0
    private var videoHeight = 0
    private var durationMs = C.TIME_UNSET
    /** Fit, Zoom or Fill, and the pinch zoom; mpv applies them itself so its subtitles stay on screen. */
    private var scaleMode = "Fit"
    private var zoom = Triple(1.0, 0.0, 0.0)

    /** Index of the item mpv has open, or [C.INDEX_UNSET] when nothing is loaded. */
    private var loadedIndex = C.INDEX_UNSET
    /** Increments with every load so late subtitle fetches can't attach to the next item. */
    private var loadGeneration = 0
    /** mpv ignores seeks sent before FILE_LOADED, so a start position waits for it. */
    private var pendingSeekMs: Long? = null
    /** Shown until mpv reports the seek finished, so the seek bar doesn't jump back. */
    private var shownPositionMs: Long? = null
    private var idleStartPositionMs = 0L
    private var firstFrameReported = false
    private var newlyRenderedFirstFrame = false
    private var autoTransition = false
    /** A load waiting for the first surface; mpv drops the video track if it opens a file without one. */
    private var loadAwaitingSurface: Runnable? = null

    private var textureView: TextureView? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var surface: Surface? = null
    private var ownsSurface = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    // Declared before init: createEngine() reads it while the object is still being built.
    private var subtitleStyle = com.playbridge.shared.player.SubtitleStyle.DEFAULT

    init {
        createEngine()
    }

    // ── Engine ─────────────────────────────────────────────────────────────────────────────

    /** mpv draws its own subtitles, so the player's style is passed straight to it. */
    fun setSubtitleStyle(style: com.playbridge.shared.player.SubtitleStyle) {
        subtitleStyle = style
        engine?.setSubtitleStyle(style)
    }

    /** Only one mpv core exists per process; a previous player may still be releasing it. */
    private fun createEngine() {
        val created = MpvPlayerEngine(appContext, verifyTls = true)
        val busy = (created.state.value as? PlaybackState.Error)?.code == "mpv_instance_busy"
        if (busy && engineAttempts++ < ENGINE_RETRIES) {
            created.release()
            main.postDelayed({ if (!released) createEngine() }, ENGINE_RETRY_MS)
            return
        }
        engine = created
        created.setSubtitleStyle(subtitleStyle)
        created.registerObserver(this, mapOf("dwidth" to MPV_FORMAT_INT64, "dheight" to MPV_FORMAT_INT64))
        scope.launch { created.state.collect(::onEngineState) }
        scope.launch {
            created.duration.collect {
                durationMs = if (it > 0) it else C.TIME_UNSET
                invalidateState()
            }
        }
        surface?.let { attachToEngine(it) }
        if (playbackState != Player.STATE_IDLE && loadedIndex == C.INDEX_UNSET) load(idleStartPositionMs)
    }

    private fun onEngineState(state: PlaybackState) {
        if (released) return
        when (state) {
            PlaybackState.Buffering -> if (loadedIndex != C.INDEX_UNSET) playbackState = Player.STATE_BUFFERING
            PlaybackState.Ready, PlaybackState.Playing, PlaybackState.Paused ->
                if (loadedIndex != C.INDEX_UNSET) playbackState = Player.STATE_READY
            PlaybackState.Ended -> if (loadedIndex != C.INDEX_UNSET) onItemEnded()
            is PlaybackState.Error -> {
                Log.e(TAG, "mpv error ${state.code}")
                error = PlaybackException("mpv: ${state.msg}", null, errorCode(state.code))
                idleStartPositionMs = currentMpvPositionMs()
                loadedIndex = C.INDEX_UNSET
                playbackState = Player.STATE_IDLE
            }
            PlaybackState.Idle -> Unit
        }
        invalidateState()
    }

    private fun errorCode(code: String): Int = when {
        code.contains("network", ignoreCase = true) || code.contains("http", ignoreCase = true) ->
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        code.contains("format", ignoreCase = true) || code.contains("decod", ignoreCase = true) ->
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        else -> PlaybackException.ERROR_CODE_UNSPECIFIED
    }

    private fun onItemEnded() {
        loadedIndex = C.INDEX_UNSET
        val next = when {
            index < items.lastIndex -> index + 1
            repeatMode == Player.REPEAT_MODE_ALL && items.isNotEmpty() -> 0
            else -> null
        }
        if (next == null) {
            idleStartPositionMs = durationMs.takeIf { it != C.TIME_UNSET } ?: currentMpvPositionMs()
            playbackState = Player.STATE_ENDED
            return
        }
        index = next
        autoTransition = true
        load(0L)
    }

    private fun load(startPositionMs: Long) {
        val item = items.getOrNull(index) ?: return
        val uri = item.localConfiguration?.uri ?: return
        loadGeneration++
        loadedIndex = index
        firstFrameReported = false
        tracks = Tracks.EMPTY
        videoWidth = 0
        videoHeight = 0
        durationMs = C.TIME_UNSET
        pendingSeekMs = startPositionMs.takeIf { it > 0 }
        shownPositionMs = startPositionMs
        error = null
        playbackState = Player.STATE_BUFFERING
        val engine = engine ?: return // createEngine() loads once the previous core is gone.
        val url = openableUrl(uri)
        if (url == null) {
            error = PlaybackException("mpv can't open this file", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            loadedIndex = C.INDEX_UNSET
            playbackState = Player.STATE_IDLE
            return
        }
        Log.i(TAG, "mpv load ${redactUrlForLog(uri.toString())}")
        val payload = PlayPayload(
            url = url,
            title = item.mediaMetadata.title?.toString(),
            headers = PhoneMediaRequest.of(item).headers,
        )
        val open = Runnable {
            loadAwaitingSurface = null
            scope.launch {
                engine.setVideoScale(scaleMode)
                engine.setVideoZoom(zoom.first, zoom.second, zoom.third)
                engine.setLanguagePreferences(
                    audio = trackParameters.preferredAudioLanguages.flatMap(::languageSpellings),
                    subtitles = trackParameters.preferredTextLanguages.flatMap(::languageSpellings),
                    subtitleFallback = trackParameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT == 0,
                )
                engine.load(payload)
                if (playWhenReady) engine.play() else engine.pause()
            }
        }
        loadAwaitingSurface?.let(main::removeCallbacks)
        if (surface != null) {
            loadAwaitingSurface = null
            open.run()
        } else {
            loadAwaitingSurface = open
            main.postDelayed(open, SURFACE_WAIT_MS)
        }
    }

    private fun openableUrl(uri: Uri): String? = when (uri.scheme?.lowercase()) {
        // mpv reads content URIs through an inherited descriptor it closes itself.
        "content" -> runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.detachFd()?.let { "fdclose://$it" }
        }.getOrNull()
        "file" -> uri.path
        else -> uri.toString()
    }

    private fun onFileLoaded() {
        val engine = engine ?: return
        pendingSeekMs?.let(engine::seek)
        pendingSeekMs = null
        attachSubtitles(loadGeneration, items.getOrNull(index) ?: return)
    }

    private fun attachSubtitles(generation: Int, item: MediaItem) {
        val subtitles = item.localConfiguration?.subtitleConfigurations.orEmpty()
        if (subtitles.isEmpty()) return
        val request = PhoneMediaRequest.of(item)
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                subtitles.map { MpvSubtitleFiles.prepare(appContext, it.uri, request.subtitleHeaders[it.uri.toString()].orEmpty()) }
            }
            val engine = engine ?: return@launch
            if (generation != loadGeneration) return@launch
            // Each add selects its track; adding last to first leaves the first one on, as in ExoPlayer.
            subtitles.indices.reversed().forEach { i ->
                val path = files[i] ?: return@forEach
                engine.attachExternalSubtitle(path, subtitles[i].label ?: subtitles[i].language)
            }
        }
    }

    private fun currentMpvPositionMs(): Long =
        shownPositionMs ?: engine?.position?.value?.coerceAtLeast(0) ?: idleStartPositionMs

    // ── mpv events (mpv's event thread) ───────────────────────────────────────────────────

    override fun event(eventId: Int, data: MPVNode) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> main.post { if (!released) onFileLoaded() }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post {
                if (released || loadedIndex == C.INDEX_UNSET) return@post
                if (pendingSeekMs == null) shownPositionMs = null
                if (!firstFrameReported) {
                    firstFrameReported = true
                    newlyRenderedFirstFrame = true
                }
                invalidateState()
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "dwidth" -> main.post { videoWidth = value.toInt(); invalidateState() }
            "dheight" -> main.post { videoHeight = value.toInt(); invalidateState() }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property != "track-list") return
        val parsed = MpvTrackList.toTracks(MpvTrackList.parse(value))
        main.post {
            if (released) return@post
            tracks = parsed
            invalidateState()
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Boolean) = Unit
    override fun eventProperty(property: String, value: Double) = Unit
    override fun eventProperty(property: String, value: MPVNode) = Unit

    // ── SimpleBasePlayer ──────────────────────────────────────────────────────────────────

    override fun getState(): State {
        val state = if (items.isEmpty() && playbackState != Player.STATE_IDLE) Player.STATE_ENDED else playbackState
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder(uids[i])
                .setMediaItem(item)
                .setIsSeekable(true)
                .setDurationUs(if (i == index && durationMs != C.TIME_UNSET) durationMs * 1000 else C.TIME_UNSET)
                .setTracks(if (i == index) tracks else Tracks.EMPTY)
                .build()
        }
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlayerError(error)
            .setIsLoading(state == Player.STATE_BUFFERING)
            .setRepeatMode(repeatMode)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(trackParameters)
            .setVideoSize(if (videoWidth > 0 && videoHeight > 0) VideoSize(videoWidth, videoHeight) else VideoSize.UNKNOWN)
            .setNewlyRenderedFirstFrame(newlyRenderedFirstFrame)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs(PositionSupplier { if (loadedIndex == C.INDEX_UNSET) idleStartPositionMs else currentMpvPositionMs() })
            .setContentBufferedPositionMs(PositionSupplier {
                maxOf(currentMpvPositionMs(), engine?.bufferAhead?.value ?: 0L)
            })
        if (autoTransition) builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0L)
        newlyRenderedFirstFrame = false
        autoTransition = false
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (loadedIndex != C.INDEX_UNSET) engine?.let { if (playWhenReady) it.play() else it.pause() }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        error = null
        if (items.isEmpty()) {
            playbackState = Player.STATE_ENDED
        } else if (loadedIndex == C.INDEX_UNSET) {
            load(idleStartPositionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        idleStartPositionMs = if (loadedIndex == C.INDEX_UNSET) idleStartPositionMs else currentMpvPositionMs()
        engine?.stop()
        loadedIndex = C.INDEX_UNSET
        playbackState = Player.STATE_IDLE
        error = null
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        textureView?.let { if (it.surfaceTextureListener === textureListener) it.surfaceTextureListener = null }
        surfaceHolder?.removeCallback(holderCallback)
        textureView = null
        surfaceHolder = null
        surface = null
        engine?.let {
            it.unregisterObserver(this)
            it.release()
        }
        engine = null
        Thread { MpvSubtitleFiles.clear(appContext) }.start()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        engine?.setLooping(repeatMode == Player.REPEAT_MODE_ONE)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        engine?.setRate(speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        val previous = trackParameters
        trackParameters = trackSelectionParameters
        val engine = engine ?: return Futures.immediateVoidFuture()
        val textDisabled = C.TRACK_TYPE_TEXT in trackSelectionParameters.disabledTrackTypes
        if (textDisabled && C.TRACK_TYPE_TEXT !in previous.disabledTrackTypes) engine.setSubtitleTrack(null)
        val before = previous.overrides.values.toSet()
        trackSelectionParameters.overrides.values.filter { it !in before }.forEach { override: TrackSelectionOverride ->
            val (type, id) = MpvTrackList.parseGroupId(override.mediaTrackGroup.id) ?: return@forEach
            when (type) {
                C.TRACK_TYPE_VIDEO -> engine.setVideoTrack(id)
                C.TRACK_TYPE_AUDIO -> engine.setAudioTrack(id)
                C.TRACK_TYPE_TEXT -> if (!textDisabled) engine.setSubtitleTrack(id)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items.clear()
        uids.clear()
        items += mediaItems
        mediaItems.forEach { _ -> uids += Any() }
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val start = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs.coerceAtLeast(0)
        loadedIndex = C.INDEX_UNSET
        when {
            items.isEmpty() -> engine?.stop()
            playbackState != Player.STATE_IDLE -> load(start)
            else -> {
                idleStartPositionMs = start
                shownPositionMs = start
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val at = index.coerceIn(0, items.size)
        val wasEmpty = items.isEmpty()
        items.addAll(at, mediaItems)
        uids.addAll(at, mediaItems.map { Any() })
        if (!wasEmpty && at <= this.index) {
            this.index += mediaItems.size
            if (loadedIndex != C.INDEX_UNSET) loadedIndex += mediaItems.size
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val to = toIndex.coerceAtMost(items.size)
        if (fromIndex >= to) return Futures.immediateVoidFuture()
        val currentRemoved = index in fromIndex until to
        repeat(to - fromIndex) {
            items.removeAt(fromIndex)
            uids.removeAt(fromIndex)
        }
        when {
            currentRemoved -> {
                index = fromIndex.coerceAtMost((items.size - 1).coerceAtLeast(0))
                loadedIndex = C.INDEX_UNSET
                if (items.isEmpty()) engine?.stop() else if (playbackState != Player.STATE_IDLE) load(0L)
            }
            to <= index -> {
                index -= to - fromIndex
                if (loadedIndex != C.INDEX_UNSET) loadedIndex = index
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val currentUid = uids.getOrNull(index)
        val movedItems = items.subList(fromIndex, toIndex).toList()
        val movedUids = uids.subList(fromIndex, toIndex).toList()
        repeat(toIndex - fromIndex) {
            items.removeAt(fromIndex)
            uids.removeAt(fromIndex)
        }
        val at = newIndex.coerceIn(0, items.size)
        items.addAll(at, movedItems)
        uids.addAll(at, movedUids)
        index = uids.indexOf(currentUid).coerceAtLeast(0)
        if (loadedIndex != C.INDEX_UNSET) loadedIndex = index
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0)
        val targetIndex = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex.coerceIn(0, items.lastIndex)
        if (targetIndex == index && loadedIndex == index) {
            shownPositionMs = target
            engine?.seek(target)
        } else {
            index = targetIndex
            loadedIndex = C.INDEX_UNSET
            if (playbackState == Player.STATE_IDLE) {
                idleStartPositionMs = target
                shownPositionMs = target
            } else {
                load(target)
            }
        }
        return Futures.immediateVoidFuture()
    }

    // ── Video output ──────────────────────────────────────────────────────────────────────

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        clearVideoOutput()
        when (videoOutput) {
            is TextureView -> {
                textureView = videoOutput
                videoOutput.surfaceTextureListener = textureListener
                videoOutput.surfaceTexture?.takeIf { videoOutput.isAvailable }?.let {
                    useSurface(Surface(it), owned = true, videoOutput.width, videoOutput.height)
                }
            }
            is SurfaceView -> watchHolder(videoOutput.holder)
            is SurfaceHolder -> watchHolder(videoOutput)
            is Surface -> useSurface(videoOutput, owned = false, 0, 0)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        val current = textureView ?: surfaceHolder ?: surface
        val matches = videoOutput == null || videoOutput === current ||
            (videoOutput is SurfaceView && videoOutput.holder === surfaceHolder)
        if (matches) clearVideoOutput()
        return Futures.immediateVoidFuture()
    }

    private fun watchHolder(holder: SurfaceHolder) {
        surfaceHolder = holder
        holder.addCallback(holderCallback)
        holder.surface?.takeIf { it.isValid }?.let {
            val frame = holder.surfaceFrame
            useSurface(it, owned = false, frame.width(), frame.height())
        }
    }

    /** Fit, Zoom (fill the screen) or Fill (stretch), as in [androidx.media3.ui.AspectRatioFrameLayout]. */
    fun setScaleMode(mode: String) {
        if (mode == scaleMode) return
        scaleMode = mode
        engine?.setVideoScale(mode)
    }

    /** Pinch-zoom: [scale] 1 is the fitted picture; the pan is in fractions of the zoomed picture. */
    fun setZoom(scale: Double, panX: Double, panY: Double) {
        val next = Triple(scale, panX, panY)
        if (next == zoom) return
        zoom = next
        engine?.setVideoZoom(scale, panX, panY)
    }

    private fun clearVideoOutput() {
        textureView?.let { if (it.surfaceTextureListener === textureListener) it.surfaceTextureListener = null }
        textureView = null
        surfaceHolder?.removeCallback(holderCallback)
        surfaceHolder = null
        detachSurface(releaseTexture = null)
    }

    private fun useSurface(newSurface: Surface, owned: Boolean, width: Int, height: Int) {
        surface = newSurface
        ownsSurface = owned
        surfaceWidth = width
        surfaceHeight = height
        attachToEngine(newSurface)
    }

    private fun attachToEngine(target: Surface) {
        val engine = engine ?: return
        engine.attachSurface(target)
        if (surfaceWidth > 0 && surfaceHeight > 0) engine.setSurfaceSize(surfaceWidth, surfaceHeight)
        // Queued after the surface, so mpv opens the file with a video output in place.
        loadAwaitingSurface?.let {
            main.removeCallbacks(it)
            it.run()
        }
    }

    /** mpv stops rendering on its control thread; the surface is released only after that. */
    private fun detachSurface(releaseTexture: SurfaceTexture?) {
        val old = surface ?: run {
            releaseTexture?.release()
            return
        }
        val owned = ownsSurface
        surface = null
        ownsSurface = false
        val engine = engine
        if (engine == null) {
            if (owned) old.release()
            releaseTexture?.release()
            return
        }
        engine.detachSurface {
            main.post {
                if (owned) old.release()
                releaseTexture?.release()
            }
        }
    }

    private fun resize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (surface != null && width > 0 && height > 0) engine?.setSurfaceSize(width, height)
        main.removeCallbacks(redrawAfterResize)
        main.postDelayed(redrawAfterResize, RESIZE_REDRAW_MS)
    }

    /**
     * A TextureView's new buffer size reaches mpv one frame late, so the first frame after a
     * resize is drawn at the old size. While playing the next frame fixes it; while paused,
     * re-seeking to the current position draws the frame again at the new size.
     */
    private val redrawAfterResize = Runnable {
        val engine = engine ?: return@Runnable
        val playing = playWhenReady && playbackState == Player.STATE_READY
        if (loadedIndex != C.INDEX_UNSET && pendingSeekMs == null && !playing) engine.seek(currentMpvPositionMs())
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            useSurface(Surface(texture), owned = true, width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = resize(width, height)

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            // Returning false keeps the texture alive until mpv has let go of it.
            detachSurface(releaseTexture = texture)
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val frame = holder.surfaceFrame
            useSurface(holder.surface, owned = false, frame.width(), frame.height())
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = resize(width, height)

        override fun surfaceDestroyed(holder: SurfaceHolder) = detachSurface(releaseTexture = null)
    }
}
