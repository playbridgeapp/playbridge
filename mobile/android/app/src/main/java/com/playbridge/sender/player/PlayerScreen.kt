package com.playbridge.sender.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.SwapHoriz
import android.widget.Toast
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.playbridge.sender.R
import com.playbridge.sender.cast.openInExternalPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class DragMode { NONE, SEEK, VOLUME, BRIGHTNESS }

/** Which track type the selection sheet is showing. */
internal enum class TrackSheetMode { AUDIO, SUBTITLE }

/** A full-width horizontal swipe scrubs this many milliseconds. */
internal const val SEEK_RANGE_MS = 120_000L

@Composable
internal fun PlayerScreen(
    player: Player,
    engine: PhonePlayerEngine,
    onSwitchEngine: (PhonePlayerEngine) -> Unit,
    title: String?,
    externalHeaders: Map<String, String>,
    externalContentType: String?,
    onClose: () -> Unit,
    onEnded: () -> Unit,
    websiteQueue: Boolean = false,
    waitingForWebsiteQueue: Boolean = false,
    isInPip: Boolean,
    /** Shared with the activity: onStop reads it, notification-return resets it. */
    backgroundMode: MutableState<Boolean>,
    /** Send the task home (background mode was just enabled synchronously). */
    onEnterBackgroundMode: () -> Unit = {}
) {
    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Playback state
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var isBuffering by remember { mutableStateOf(true) }
    // Once a frame is on screen, hide the spinner even if ExoPlayer briefly
    // stays in STATE_BUFFERING (seen on Samsung with local content:// MP4s).
    var hasRenderedFrame by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Controls / scrubbing
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var trackSheet by remember { mutableStateOf<TrackSheetMode?>(null) }
    var currentTracks by remember { mutableStateOf(player.currentTracks) }
    var currentTitle by remember { mutableStateOf(title) }
    var aspectLabel by remember { mutableStateOf<String?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var showEngineSheet by remember { mutableStateOf(false) }

    val activeVideoFormat = remember(currentTracks) {
        currentTracks.groups.find { it.type == C.TRACK_TYPE_VIDEO }?.let { group ->
            (0 until group.length).find { group.isTrackSelected(it) }?.let { index ->
                group.getTrackFormat(index)
            }
        }
    }

    val activeAudioFormat = remember(currentTracks) {
        currentTracks.groups.find { it.type == C.TRACK_TYPE_AUDIO }?.let { group ->
            (0 until group.length).find { group.isTrackSelected(it) }?.let { index ->
                group.getTrackFormat(index)
            }
        }
    }

    // New states
    var isBackgroundModeEnabled by backgroundMode
    var repeatMode by remember { mutableIntStateOf(player.repeatMode) }
    var abStartMs by remember { mutableStateOf<Long?>(null) }
    var abEndMs by remember { mutableStateOf<Long?>(null) }
    var isPanZoomEnabled by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // (Background toggle state is hoisted — the activity shares backgroundMode directly.)

    // Precision AB Loop segment repeat — only while playing, 200ms cadence.
    LaunchedEffect(abStartMs, abEndMs, isPlaying) {
        if (abStartMs != null && abEndMs != null && isPlaying) {
            while (isPlaying) {
                if (player.currentPosition >= abEndMs!!) {
                    player.seekTo(abStartMs!!)
                }
                delay(200)
            }
        }
    }

    // Force hide controls in PiP mode
    // The navigation bar shows with the controls and hides with them, so the gesture bar is
    // reachable whenever the controls are.
    val controlsShown = controlsVisible && !locked && errorMessage == null && !isInPip
    LaunchedEffect(controlsShown) {
        val w = window ?: return@LaunchedEffect
        val bars = WindowInsetsControllerCompat(w, w.decorView)
        if (controlsShown) bars.show(WindowInsetsCompat.Type.navigationBars())
        else bars.hide(WindowInsetsCompat.Type.navigationBars())
    }

    LaunchedEffect(isInPip) {
        if (isInPip) {
            controlsVisible = false
        }
    }

    // Website queues start with one item and grow as Streams supplies episodes.
    // Follow timeline changes rather than retaining the initial single-item list.
    fun readQueueTitles() = (0 until player.mediaItemCount).map {
        player.getMediaItemAt(it).mediaMetadata.title?.toString() ?: "Episode ${it + 1}"
    }
    var nativeEpisodeTitles by remember(player) { mutableStateOf(readQueueTitles()) }
    val episodeTitles = nativeEpisodeTitles
    val isPlaylist = websiteQueue || episodeTitles.size > 1
    var nativeIndex by remember { mutableIntStateOf(player.currentMediaItemIndex) }
    val currentIndex = nativeIndex
    var showEpisodes by remember { mutableStateOf(false) }

    /** Navigate to an episode: lazy resolve-and-swap, or a native timeline seek. */
    fun goToEpisode(index: Int) {
        if (index < 0 || index >= episodeTitles.size) return
        errorMessage = null
        player.seekTo(index, 0L)
        player.play()
    }

    // Gesture HUD state
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var volume by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var brightness by remember { mutableFloatStateOf(initialBrightness(window, context)) }
    var seekPreviewMs by remember { mutableLongStateOf(0L) }
    var seekDeltaMs by remember { mutableLongStateOf(0L) }
    var doubleTapSide by remember { mutableIntStateOf(0) }    // -1 = rewind, +1 = forward, 0 = none
    var doubleTapSeconds by remember { mutableIntStateOf(0) } // cumulative skip amount shown in the indicator

    fun markInteraction() { interactionTick++ }

    // Poll position/buffer while not actively scrubbing/seeking.
    // Perf: MutableLongState holders — Compose tracks .longValue reads per-composable,
    // so the 400ms tick recomposes only readers (SeekProgressRow, HUDs, stats below).
    val playbackProgress = remember { mutableLongStateOf(0L) }
    val playbackBuffered = remember { mutableLongStateOf(0L) }
    val playbackDuration = remember { mutableLongStateOf(0L) }
    // Snapshot aliases for event handlers (seekBy, drag end, scrub end) — reads below
    // in SeekProgressRow/HUDs/stats use the holders directly for scoped recomposition.
    // (Local `val x get()` properties are illegal here — this is a @Composable function
    // body, not a class — so call sites below read the holders directly.)

    fun seekBy(deltaMs: Long) {
        val d = player.duration.coerceAtLeast(0)
        val target = (player.currentPosition + deltaMs).coerceIn(0, if (d > 0) d else Long.MAX_VALUE)
        player.seekTo(target)
        playbackProgress.longValue = target
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        markInteraction()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                nativeEpisodeTitles = readQueueTitles()
                nativeIndex = player.currentMediaItemIndex
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                currentTracks = tracks
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Title + index follow the current playlist item (e.g. next episode on auto-advance).
                currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: title
                nativeIndex = player.currentMediaItemIndex

                // On media item transition (seeking to previous/next or auto-advance), certain hardware
                // decoders fail to hand off the surface correctly, causing a black screen.
                // Re-preparing the player forces codec recreation and clean rendering.
                if (player is ExoPlayer && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)) {
                    player.prepare()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    errorMessage = null
                    val d = player.duration
                    if (d > 0) playbackDuration.longValue = d
                }
                // End of the current item. In lazy mode advance to (and resolve) the next
                // episode; otherwise this is the end of all content, so exit.
                if (state == Player.STATE_ENDED) {
                    onEnded()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.message ?: "Playback error"
                isBuffering = false
            }
            override fun onRenderedFirstFrame() {
                hasRenderedFrame = true
                isBuffering = false
            }
        }
        player.addListener(listener)
        nativeEpisodeTitles = readQueueTitles()
        nativeIndex = player.currentMediaItemIndex
        onDispose { player.removeListener(listener) }
    }

    // Safety net after first frame: reinit only. Do NOT drop audio when FFmpeg is
    // available — silent video was the bug FFmpeg fixed; muting would reintroduce it.
    // Drop-audio is a last resort only if the extension AAR failed to load.
    LaunchedEffect(player, hasRenderedFrame) {
        val exo = player as? ExoPlayer ?: return@LaunchedEffect
        if (!hasRenderedFrame) return@LaunchedEffect
        delay(1_500)
        if (!isPlaybackStuck(exo, hasRenderedFrame = true)) return@LaunchedEffect
        Log.w(PB_PLAYER, "stuck BUFFERING after first frame — reinit")
        PhoneExoPlayerFactory.recoverAudio(exo, "reinit")
        delay(2_000)
        if (!isPlaybackStuck(exo, hasRenderedFrame = true)) return@LaunchedEffect
        if (PhoneExoPlayerFactory.isFfmpegAvailable()) {
            // FFmpeg path is the fix for no-audio; clear overrides and re-prepare once more.
            Log.w(PB_PLAYER, "still stuck with FFmpeg — clear-override (not mute)")
            PhoneExoPlayerFactory.recoverAudio(exo, "clear-override")
        } else {
            Log.w(PB_PLAYER, "still stuck, FFmpeg missing — drop audio last resort")
            PhoneExoPlayerFactory.recoverAudio(exo, "drop-audio")
        }
    }

    // Poll position/buffer while not actively scrubbing/seeking.
    LaunchedEffect(Unit) {
        while (true) {
            if (!isScrubbing && dragMode != DragMode.SEEK) {
                playbackProgress.longValue = player.currentPosition
                playbackBuffered.longValue = player.bufferedPosition
                val d = player.duration
                if (d > 0) playbackDuration.longValue = d
            }
            delay(400)
        }
    }
    // Auto-hide controls a few seconds after the last interaction while playing.
    LaunchedEffect(controlsVisible, isPlaying, interactionTick, dragMode) {
        if (controlsVisible && isPlaying && dragMode == DragMode.NONE) {
            delay(3500)
            controlsVisible = false
        }
    }

    // Clear the double-tap skip indicator shortly after the last tap.
    LaunchedEffect(doubleTapSide, doubleTapSeconds) {
        if (doubleTapSide != 0) {
            delay(800)
            doubleTapSide = 0
            doubleTapSeconds = 0
        }
    }

    // Auto-hide the unlock affordance shown while locked.
    LaunchedEffect(showUnlock) {
        if (showUnlock) {
            delay(2500)
            showUnlock = false
        }
    }

    // Auto-clear the aspect ratio HUD notification
    LaunchedEffect(aspectLabel) {
        if (aspectLabel != null) {
            delay(1500)
            aspectLabel = null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(locked, isInPip) {
                    detectTapGestures(
                        onTap = {
                            if (isInPip) return@detectTapGestures
                            if (locked) {
                                showUnlock = true
                            } else {
                                controlsVisible = !controlsVisible
                                if (controlsVisible) markInteraction()
                            }
                        },
                        onDoubleTap = { offset ->
                            if (isInPip) return@detectTapGestures
                            if (!locked) {
                                if (offset.x < size.width / 2f) {
                                    doubleTapSeconds = if (doubleTapSide == -1) doubleTapSeconds + 10 else 10
                                    doubleTapSide = -1
                                    seekBy(-10_000)
                                } else {
                                    doubleTapSeconds = if (doubleTapSide == 1) doubleTapSeconds + 10 else 10
                                    doubleTapSide = 1
                                    seekBy(10_000)
                                }
                            }
                        }
                    )
                }
                .pointerInput(locked, isInPip) {
                    val slop = viewConfiguration.touchSlop
                    var startX = 0f
                    var accDx = 0f
                    var accDy = 0f
                    var seekStartMs = 0L
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (isInPip) return@detectDragGestures
                            startX = offset.x
                            accDx = 0f; accDy = 0f
                            dragMode = DragMode.NONE
                            seekStartMs = player.currentPosition
                            // Re-sync in case volume was changed by hardware keys since last drag.
                            volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        },
                        onDragEnd = {
                            if (dragMode == DragMode.SEEK) {
                                player.seekTo(seekPreviewMs)
                                playbackProgress.longValue = seekPreviewMs
                            }
                            dragMode = DragMode.NONE
                        },
                        onDragCancel = { dragMode = DragMode.NONE },
                        onDrag = onDrag@{ change, amount ->
                            if (locked) return@onDrag
                            change.consume()
                            accDx += amount.x
                            accDy += amount.y
                            if (dragMode == DragMode.NONE) {
                                if (abs(accDx) > slop && abs(accDx) > abs(accDy)) {
                                    dragMode = DragMode.SEEK
                                } else if (abs(accDy) > slop) {
                                    dragMode = if (startX < size.width / 2f) DragMode.BRIGHTNESS else DragMode.VOLUME
                                }
                            }
                            when (dragMode) {
                                DragMode.SEEK -> {
                                    val d = playbackDuration.longValue
                                    if (d > 0) {
                                        val delta = ((accDx / size.width) * SEEK_RANGE_MS).toLong()
                                        seekPreviewMs = (seekStartMs + delta).coerceIn(0, d)
                                        seekDeltaMs = seekPreviewMs - seekStartMs
                                    }
                                }
                                DragMode.VOLUME -> {
                                    val prevVolume = volume
                                    val newVolume = (volume - (amount.y / size.height) * maxVolume)
                                        .coerceIn(0f, maxVolume.toFloat())
                                    volume = newVolume
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC, volume.roundToInt(), 0
                                    )
                                    if ((newVolume == 0f && prevVolume > 0f) || (newVolume == maxVolume.toFloat() && prevVolume < maxVolume.toFloat())) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                }
                                DragMode.BRIGHTNESS -> {
                                    val prevBrightness = brightness
                                    val newBrightness = (brightness - amount.y / size.height).coerceIn(0.01f, 1f)
                                    brightness = newBrightness
                                    window?.let {
                                        val lp = it.attributes
                                        lp.screenBrightness = brightness
                                        it.attributes = lp
                                    }
                                    if ((newBrightness == 0.01f && prevBrightness > 0.01f) || (newBrightness == 1f && prevBrightness < 1f)) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                }
                                DragMode.NONE -> {}
                            }
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Perf: pinch-zoom transform on the outer Box, not the AndroidView —
                    // per-gesture-frame TextureView re-layout dropped frames. Lambda
                    // overload applies values at draw time without recomposing.
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(isPanZoomEnabled) {
                        if (isPanZoomEnabled) {
                            detectTransformGestures { centroid, pan, zoom, rotation ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offset += pan
                                } else {
                                    offset = Offset.Zero
                                }
                            }
                        }
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        // TextureView-backed PlayerView — surface ready when attached
                        // (SurfaceView is async and left Compose in permanent BUFFERING).
                        (LayoutInflater.from(ctx).inflate(R.layout.player_view, FrameLayout(ctx), false) as PlayerView).apply {
                            this.player = player
                            this.resizeMode = resizeMode
                            ensurePrepared(player)
                            post { ensurePrepared(player) }

                            // Remove ugly dark background boxes from subtitles and use a high-contrast text outline
                            subtitleView?.apply {
                                setApplyEmbeddedStyles(false)
                                setApplyEmbeddedFontSizes(false)
                                val customStyle = androidx.media3.ui.CaptionStyleCompat(
                                    android.graphics.Color.WHITE,
                                    android.graphics.Color.TRANSPARENT,
                                    android.graphics.Color.TRANSPARENT,
                                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                    android.graphics.Color.BLACK,
                                    android.graphics.Typeface.DEFAULT_BOLD
                                )
                                setStyle(customStyle)
                                setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                            }
                        }
                    },
                    update = { view ->
                        if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
                        if (view.player !== player) {
                            view.player = player
                            ensurePrepared(player)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                )
            }

            // Buffering spinner — hide once a frame has painted even if ExoPlayer
            // still reports BUFFERING (local content:// edge case).
            if ((isBuffering && !hasRenderedFrame || waitingForWebsiteQueue) &&
                dragMode == DragMode.NONE &&
                errorMessage == null
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            // Double-tap skip indicator (cumulative: 10s, 20s, …).
            if (doubleTapSide != 0) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .align(if (doubleTapSide < 0) Alignment.CenterStart else Alignment.CenterEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (doubleTapSide < 0) Icons.Filled.Replay10 else Icons.Filled.Forward10,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${doubleTapSeconds}s",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Gesture HUDs.
            when (dragMode) {
                DragMode.VOLUME -> GestureHud(
                    icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White) },
                    fraction = volume / maxVolume,
                    label = "${(volume / maxVolume * 100).roundToInt()}%",
                    alignment = Alignment.CenterStart
                )
                DragMode.BRIGHTNESS -> GestureHud(
                    icon = { Icon(Icons.Filled.BrightnessHigh, null, tint = Color.White) },
                    fraction = brightness,
                    label = "${(brightness * 100).roundToInt()}%",
                    alignment = Alignment.CenterEnd
                )
                DragMode.SEEK -> SeekHud(seekDeltaMs, seekPreviewMs, playbackDuration.longValue)
                DragMode.NONE -> {}
            }

            // Aspect ratio confirmation HUD
            AnimatedVisibility(
                visible = aspectLabel != null,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = aspectLabel ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Stats overlay
            AnimatedVisibility(
                visible = showStats && errorMessage == null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                StatsOverlay(
                    player = player,
                    engine = engine,
                    activeVideoFormat = activeVideoFormat,
                    activeAudioFormat = activeAudioFormat,
                    positionMs = playbackProgress.longValue,
                    bufferedMs = playbackBuffered.longValue,
                    speed = speed,
                    onClose = { showStats = false }
                )
            }

            // Error overlay with recovery actions (transient timeouts shouldn't dead-end).
            if (errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Playback failed", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onClose) { Text("Close", color = Color.White.copy(alpha = 0.8f)) }
                            if (isPlaylist && currentIndex < episodeTitles.size - 1) {
                                TextButton(onClick = { goToEpisode(currentIndex + 1) }) {
                                    Text("Next episode", color = Color.White)
                                }
                            }
                            Button(onClick = {
                                errorMessage = null
                                player.prepare()
                            }) { Text("Retry") }
                        }
                    }
                }
            }

            // ── Control overlay: glassy "bubble" layout ─────────────────
            AnimatedVisibility(
                visible = controlsVisible && !locked && errorMessage == null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            remember {
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.5f),
                                    0.25f to Color.Transparent,
                                    0.75f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.6f)
                                )
                            }
                        )
                ) {
                    // Top bar: back, title, and a translucent action pill.
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            // The camera cutout stays clear even while the status bar is hidden.
                            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Text(
                            text = currentTitle ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                        Surface(color = Color.White.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPlaylist) {
                                    IconButton(onClick = { showEpisodes = true; markInteraction() }) {
                                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, if (websiteQueue) "Queue" else "Episodes", tint = Color.White)
                                    }
                                }
                                val subtitlesActive = currentTracks.groups.any {
                                    it.type == C.TRACK_TYPE_TEXT && (0 until it.length).any { i -> it.isTrackSelected(i) }
                                }
                                IconButton(onClick = { trackSheet = TrackSheetMode.AUDIO; markInteraction() }) {
                                    Icon(Icons.Filled.Audiotrack, "Audio track", tint = Color.White)
                                }
                                IconButton(onClick = { trackSheet = TrackSheetMode.SUBTITLE; markInteraction() }) {
                                    Icon(
                                        if (subtitlesActive) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionOff,
                                        "Subtitles",
                                        tint = Color.White
                                    )
                                }
                                IconButton(onClick = { showEngineSheet = true; markInteraction() }) {
                                    Icon(Icons.Filled.SwapHoriz, "Switch player", tint = Color.White)
                                }
                                IconButton(onClick = { showStats = !showStats; markInteraction() }) {
                                    Icon(
                                        imageVector = Icons.Default.BarChart,
                                        contentDescription = "Stats",
                                        tint = if (showStats) Color.Green else Color.White
                                    )
                                }
                                IconButton(onClick = {
                                    val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                                    if (uri != null) {
                                        player.pause()
                                        openInExternalPlayer(
                                            context,
                                            uri,
                                            externalContentType,
                                            externalHeaders.takeIf { it.isNotEmpty() }
                                        )
                                    }
                                    markInteraction()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in external player", tint = Color.White)
                                }
                            }
                        }
                    }

                    // Centered Transport Controls
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp)
                    ) {
                        if (isPlaylist) {
                            GlassControl(
                                onClick = { goToEpisode(currentIndex - 1); markInteraction() },
                                diameter = 56.dp,
                                enabled = currentIndex > 0
                            ) {
                                Icon(Icons.Filled.SkipPrevious, "Previous episode", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                        GlassControl(
                            onClick = { togglePlay() },
                            diameter = 72.dp,
                            background = Color.White.copy(alpha = 0.2f)
                        ) {
                            androidx.compose.animation.Crossfade(
                                targetState = isPlaying,
                                label = "PlayPauseAnimation"
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        if (isPlaylist) {
                            GlassControl(
                                onClick = { goToEpisode(currentIndex + 1); markInteraction() },
                                diameter = 56.dp,
                                enabled = currentIndex < episodeTitles.size - 1
                            ) {
                                Icon(Icons.Filled.SkipNext, "Next episode", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                        }
                    }

                    // Bottom controls: transport cluster (left) + resize (right),
                    // with the full-width seek bar underneath. Center stays clear.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        // The 11 fixed-size buttons total ~550dp with spacing — far wider
                        // than any portrait screen, which overflowed/clipped the single
                        // row (the broken portrait layout). The two clusters are extracted
                        // so portrait can stack them as two rows; landscape keeps one row
                        // with the flexible gap in the middle.
                        val transportControls: @Composable () -> Unit = {
                            // 1. Lock Controls
                            GlassControl(
                                onClick = { locked = true; showUnlock = true },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.Lock, "Lock controls", tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // 2. Background Playback Toggle. Enabling it acts immediately:
                            // the whole point is listening with the screen off / elsewhere,
                            // so go straight home and let the media notification take over.
                            // Tapping again (after returning) turns it off.
                            GlassControl(
                                onClick = {
                                    if (isBackgroundModeEnabled) {
                                        isBackgroundModeEnabled = false
                                        aspectLabel = "Background Playback Disabled"
                                        markInteraction()
                                    } else {
                                        isBackgroundModeEnabled = true
                                        // Sync the activity flag synchronously and background
                                        // the task — waiting for the LaunchedEffect would race
                                        // onStop, which reads the flag to decide pause-vs-notify.
                                        onEnterBackgroundMode()
                                    }
                                },
                                diameter = 40.dp,
                                background = if (isBackgroundModeEnabled) Color.Green.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Headphones,
                                    contentDescription = "Background mode",
                                    tint = if (isBackgroundModeEnabled) Color.Green else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 3. Screen Rotation Toggle
                            GlassControl(
                                onClick = {
                                    val activity = context as? Activity
                                    if (activity != null) {
                                        // Manual choice — stop auto-following the video aspect.
                                        (activity as? PlayerActivity)?.disableAutoOrientation()
                                        val isLandscapeNow =
                                            activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        activity.requestedOrientation = if (isLandscapeNow) {
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                        } else {
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        }
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.ScreenRotation, "Rotate screen", tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // 4. Playback Speed Button (Moved here)
                            GlassControl(
                                onClick = {
                                    speed = nextSpeed(speed)
                                    player.setPlaybackSpeed(speed)
                                    aspectLabel = "Speed: ${speedLabel(speed)}"
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = speedLabel(speed),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // 5. Repeat Mode Selector
                            GlassControl(
                                onClick = {
                                    val nextMode = when (player.repeatMode) {
                                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                        else -> Player.REPEAT_MODE_OFF
                                    }
                                    player.repeatMode = nextMode
                                    repeatMode = nextMode
                                    aspectLabel = when (nextMode) {
                                        Player.REPEAT_MODE_OFF -> "Repeat: Off"
                                        Player.REPEAT_MODE_ONE -> "Repeat: One"
                                        Player.REPEAT_MODE_ALL -> "Repeat: All"
                                        else -> "Repeat: Off"
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp,
                                background = if (repeatMode != Player.REPEAT_MODE_OFF) Color.Cyan.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Icon(
                                    imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                    contentDescription = "Repeat mode",
                                    tint = if (repeatMode != Player.REPEAT_MODE_OFF) Color.Cyan else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 6. AB Loop Repeat Selector
                            GlassControl(
                                onClick = {
                                    val currentPos = player.currentPosition
                                    if (abStartMs == null) {
                                        abStartMs = currentPos
                                        aspectLabel = "Loop Start [A] Set"
                                    } else if (abEndMs == null) {
                                        if (currentPos > abStartMs!!) {
                                            abEndMs = currentPos
                                            aspectLabel = "Loop End [B] Set: A-B Active"
                                        } else {
                                            aspectLabel = "End must be after Start"
                                        }
                                    } else {
                                        abStartMs = null
                                        abEndMs = null
                                        aspectLabel = "AB Loop Cleared"
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp,
                                background = if (abStartMs != null) Color.Yellow.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Loop,
                                        contentDescription = "AB Loop",
                                        tint = if (abStartMs != null) Color.Yellow else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    val badgeText = when {
                                        abStartMs != null && abEndMs != null -> "AB"
                                        abStartMs != null -> "A"
                                        else -> ""
                                    }
                                    if (badgeText.isNotEmpty()) {
                                        Text(
                                            text = badgeText,
                                            color = Color.Black,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .background(Color.Yellow, shape = RoundedCornerShape(4.dp))
                                                .padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }

                        }

                        val utilityControls: @Composable () -> Unit = {
                            // 8. Capture Frame (Photo)
                            GlassControl(
                                onClick = {
                                    val playerView = (context as? Activity)?.findViewById<PlayerView>(R.id.player_view)
                                    if (playerView != null) {
                                        captureFrame(playerView) { bitmap ->
                                            if (bitmap != null) {
                                                saveBitmapToGallery(context, bitmap, currentTitle ?: "PlayBridge")
                                            } else {
                                                (context as? Activity)?.runOnUiThread {
                                                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.CameraAlt, "Capture frame", tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // 9. Video Zoom (Pan and Zoom toggle)
                            GlassControl(
                                onClick = {
                                    isPanZoomEnabled = !isPanZoomEnabled
                                    if (!isPanZoomEnabled) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    }
                                    aspectLabel = if (isPanZoomEnabled) "Pan & Zoom Enabled (Pinch/Drag)" else "Pan & Zoom Disabled"
                                    markInteraction()
                                },
                                diameter = 40.dp,
                                background = if (isPanZoomEnabled) Color.Magenta.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ZoomIn,
                                    contentDescription = "Zoom and Pan",
                                    tint = if (isPanZoomEnabled) Color.Magenta else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 10. Picture-in-Picture Mode Button
                            GlassControl(
                                onClick = {
                                    val activity = context as? Activity
                                    if (activity != null) {
                                        enterPip(activity)
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.PictureInPicture, "Picture in picture", tint = Color.White, modifier = Modifier.size(20.dp))
                            }

                            // 11. Aspect Ratio Mode Toggle
                            GlassControl(
                                onClick = {
                                    val nextMode = nextResizeMode(resizeMode)
                                    resizeMode = nextMode
                                    aspectLabel = when (nextMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit to Screen"
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoomed"
                                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretched"
                                        else -> "Fit"
                                    }
                                    markInteraction()
                                },
                                diameter = 40.dp
                            ) {
                                Icon(Icons.Filled.AspectRatio, "Resize", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }

                        val isPortraitLayout =
                            LocalConfiguration.current.screenWidthDp < LocalConfiguration.current.screenHeightDp
                        if (isPortraitLayout) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) { transportControls() }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) { utilityControls() }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                transportControls()
                                Spacer(Modifier.weight(1f))
                                utilityControls()
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Perf: isolated so the 400ms position ticker recomposes only
                        // this row — the old inline reads pulled the whole overlay.
                        SeekProgressRow(
                            position = playbackProgress,
                            buffered = playbackBuffered,
                            duration = playbackDuration,
                            isScrubbing = isScrubbing,
                            scrubFraction = scrubFraction,
                            onScrubStart = { isScrubbing = true; markInteraction() },
                            onScrub = { scrubFraction = it; markInteraction() },
                            onScrubEnd = {
                                if (playbackDuration.longValue > 0) {
                                    val target = (it * playbackDuration.longValue).toLong()
                                    player.seekTo(target)
                                    playbackProgress.longValue = target
                                }
                                isScrubbing = false
                            },
                        )
                    }
                }
            }

            // ── Unlock affordance (shown briefly while locked) ──────────
            AnimatedVisibility(
                visible = locked && showUnlock,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassControl(
                        onClick = {
                            locked = false
                            showUnlock = false
                            controlsVisible = true
                            markInteraction()
                        },
                        diameter = 56.dp,
                        background = Color.Black.copy(alpha = 0.5f)
                    ) {
                        Icon(Icons.Filled.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }

            if (showEngineSheet) {
                SelectionSheet(
                    header = "Player",
                    rows = PhonePlayerEngine.entries.map { option ->
                        SheetRow(option.label, option == engine, true) {
                            showEngineSheet = false
                            onSwitchEngine(option)
                        }
                    },
                    emptyMessage = null,
                    onDismiss = { showEngineSheet = false }
                )
            }

            if (showEpisodes) {
                EpisodeSheet(
                    header = if (websiteQueue) "Queue" else "Episodes",
                    titles = episodeTitles,
                    currentIndex = currentIndex,
                    onSelect = { idx -> goToEpisode(idx) },
                    onDismiss = { showEpisodes = false }
                )
            }

            trackSheet?.let { mode ->
                TrackSelectionSheet(
                    mode = mode,
                    tracks = currentTracks,
                    onDismiss = { trackSheet = null },
                    onSelectAudio = { group, idx ->
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group, idx))
                            .build()
                    },
                    onSelectText = { group, idx ->
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group, idx))
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                    },
                    onDisableText = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    }
                )
            }
        }
    }
}
