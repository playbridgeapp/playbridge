package com.playbridge.sender.player

import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.playbridge.sender.R
import com.playbridge.sender.cast.openInExternalPlayer

/** The glassy control layer: top bar, centred transport and bottom bar, over a scrim. */
@Composable
internal fun PlayerControlsOverlay(
    state: PlayerScreenState,
    externalHeaders: Map<String, String>,
    externalContentType: String?,
    onClose: () -> Unit,
    backgroundMode: MutableState<Boolean>,
    onEnterBackgroundMode: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.controlsVisible && !state.locked && state.errorMessage == null,
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
            PlayerTopBar(state, externalHeaders, externalContentType, onClose)
            PlayerCenterControls(state)
            PlayerBottomBar(state, backgroundMode, onEnterBackgroundMode)
        }
    }
}

/** Back, the title, and a translucent action pill. */
@Composable
private fun BoxScope.PlayerTopBar(
    state: PlayerScreenState,
    externalHeaders: Map<String, String>,
    externalContentType: String?,
    onClose: () -> Unit,
) {
    val player = state.player
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
            text = state.currentTitle ?: "",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        )
        Surface(color = Color.White.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isPlaylist) {
                    IconButton(onClick = { state.showEpisodes = true; state.markInteraction() }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, if (state.websiteQueue) "Queue" else "Episodes", tint = Color.White)
                    }
                }
                val subtitlesActive = state.currentTracks.groups.any {
                    it.type == C.TRACK_TYPE_TEXT && (0 until it.length).any { i -> it.isTrackSelected(i) }
                }
                IconButton(onClick = { state.trackSheet = TrackSheetMode.AUDIO; state.markInteraction() }) {
                    Icon(Icons.Filled.Audiotrack, "Audio track", tint = Color.White)
                }
                IconButton(onClick = { state.trackSheet = TrackSheetMode.SUBTITLE; state.markInteraction() }) {
                    Icon(
                        if (subtitlesActive) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionOff,
                        "Subtitles",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { state.showEngineSheet = true; state.markInteraction() }) {
                    Icon(Icons.Filled.SwapHoriz, "Switch player", tint = Color.White)
                }
                IconButton(onClick = { state.showStats = !state.showStats; state.markInteraction() }) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = "Stats",
                        tint = if (state.showStats) Color.Green else Color.White
                    )
                }
                IconButton(onClick = {
                    val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    if (uri != null) {
                        player.pause()
                        openInExternalPlayer(
                            state.context,
                            uri,
                            externalContentType,
                            externalHeaders.takeIf { it.isNotEmpty() }
                        )
                    }
                    state.markInteraction()
                }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in external player", tint = Color.White)
                }
            }
        }
    }
}

/** Previous, play/pause and next, centred. */
@Composable
private fun BoxScope.PlayerCenterControls(state: PlayerScreenState) {
    Row(
        modifier = Modifier.align(Alignment.Center),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        if (state.isPlaylist) {
            GlassControl(
                onClick = { state.goToEpisode(state.currentIndex - 1); state.markInteraction() },
                diameter = 56.dp,
                enabled = state.currentIndex > 0
            ) {
                Icon(Icons.Filled.SkipPrevious, "Previous episode", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        GlassControl(
            onClick = { state.togglePlay() },
            diameter = 72.dp,
            background = Color.White.copy(alpha = 0.2f)
        ) {
            androidx.compose.animation.Crossfade(
                targetState = state.isPlaying,
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
        if (state.isPlaylist) {
            GlassControl(
                onClick = { state.goToEpisode(state.currentIndex + 1); state.markInteraction() },
                diameter = 56.dp,
                enabled = state.currentIndex < state.episodeTitles.size - 1
            ) {
                Icon(Icons.Filled.SkipNext, "Next episode", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/**
 * Transport cluster (left) + utilities (right), with the full-width seek bar underneath.
 * The centre stays clear.
 */
@Composable
private fun BoxScope.PlayerBottomBar(
    state: PlayerScreenState,
    backgroundMode: MutableState<Boolean>,
    onEnterBackgroundMode: () -> Unit,
) {
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
        val isPortraitLayout =
            LocalConfiguration.current.screenWidthDp < LocalConfiguration.current.screenHeightDp
        if (isPortraitLayout) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) { TransportControls(state, backgroundMode, onEnterBackgroundMode) }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) { UtilityControls(state) }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransportControls(state, backgroundMode, onEnterBackgroundMode)
                Spacer(Modifier.weight(1f))
                UtilityControls(state)
            }
        }

        Spacer(Modifier.height(4.dp))

        // Perf: isolated so the 400ms position ticker recomposes only
        // this row — the old inline reads pulled the whole overlay.
        SeekProgressRow(
            position = state.playbackProgress,
            buffered = state.playbackBuffered,
            duration = state.playbackDuration,
            isScrubbing = state.isScrubbing,
            scrubFraction = state.scrubFraction,
            onScrubStart = { state.isScrubbing = true; state.markInteraction() },
            onScrub = { state.scrubFraction = it; state.markInteraction() },
            onScrubEnd = {
                if (state.playbackDuration.longValue > 0) {
                    val target = (it * state.playbackDuration.longValue).toLong()
                    state.player.seekTo(target)
                    state.playbackProgress.longValue = target
                }
                state.isScrubbing = false
            },
        )
    }
}

/** Lock, background playback, rotation, speed, repeat and A-B loop. */
@Composable
private fun RowScope.TransportControls(
    state: PlayerScreenState,
    backgroundMode: MutableState<Boolean>,
    onEnterBackgroundMode: () -> Unit,
) {
    val player = state.player
    var isBackgroundModeEnabled by backgroundMode

    // 1. Lock Controls
    GlassControl(
        onClick = { state.locked = true; state.showUnlock = true },
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
                state.statusMessage = "Background Playback Disabled"
                state.markInteraction()
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
            val activity = state.activity
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
            state.markInteraction()
        },
        diameter = 40.dp
    ) {
        Icon(Icons.Filled.ScreenRotation, "Rotate screen", tint = Color.White, modifier = Modifier.size(20.dp))
    }

    // 4. Playback Speed Button (Moved here)
    GlassControl(
        onClick = {
            state.speed = nextSpeed(state.speed)
            player.setPlaybackSpeed(state.speed)
            state.statusMessage = "Speed: ${speedLabel(state.speed)}"
            state.markInteraction()
        },
        diameter = 40.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = speedLabel(state.speed),
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
            state.repeatMode = nextMode
            state.statusMessage = when (nextMode) {
                Player.REPEAT_MODE_OFF -> "Repeat: Off"
                Player.REPEAT_MODE_ONE -> "Repeat: One"
                Player.REPEAT_MODE_ALL -> "Repeat: All"
                else -> "Repeat: Off"
            }
            state.markInteraction()
        },
        diameter = 40.dp,
        background = if (state.repeatMode != Player.REPEAT_MODE_OFF) Color.Cyan.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
    ) {
        Icon(
            imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            contentDescription = "Repeat mode",
            tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) Color.Cyan else Color.White,
            modifier = Modifier.size(20.dp)
        )
    }

    // 6. AB Loop Repeat Selector
    GlassControl(
        onClick = {
            val currentPos = player.currentPosition
            val start = state.abStartMs
            if (start == null) {
                state.abStartMs = currentPos
                state.statusMessage = "Loop Start [A] Set"
            } else if (state.abEndMs == null) {
                if (currentPos > start) {
                    state.abEndMs = currentPos
                    state.statusMessage = "Loop End [B] Set: A-B Active"
                } else {
                    state.statusMessage = "End must be after Start"
                }
            } else {
                state.abStartMs = null
                state.abEndMs = null
                state.statusMessage = "AB Loop Cleared"
            }
            state.markInteraction()
        },
        diameter = 40.dp,
        background = if (state.abStartMs != null) Color.Yellow.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Loop,
                contentDescription = "AB Loop",
                tint = if (state.abStartMs != null) Color.Yellow else Color.White,
                modifier = Modifier.size(20.dp)
            )
            val badgeText = when {
                state.abStartMs != null && state.abEndMs != null -> "AB"
                state.abStartMs != null -> "A"
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

/** Frame capture, pan & zoom, picture-in-picture and aspect ratio. */
@Composable
private fun RowScope.UtilityControls(state: PlayerScreenState) {
    val context = state.context

    // 8. Capture Frame (Photo)
    GlassControl(
        onClick = {
            val playerView = state.activity?.findViewById<PlayerView>(R.id.player_view)
            if (playerView != null) {
                captureFrame(playerView) { bitmap ->
                    if (bitmap != null) {
                        saveBitmapToGallery(context, bitmap, state.currentTitle ?: "PlayBridge")
                    } else {
                        state.activity?.runOnUiThread {
                            Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            state.markInteraction()
        },
        diameter = 40.dp
    ) {
        Icon(Icons.Filled.CameraAlt, "Capture frame", tint = Color.White, modifier = Modifier.size(20.dp))
    }

    // 9. Video Zoom (Pan and Zoom toggle)
    GlassControl(
        onClick = {
            state.isPanZoomEnabled = !state.isPanZoomEnabled
            if (!state.isPanZoomEnabled) {
                state.scale = 1f
                state.panOffset = Offset.Zero
            }
            state.statusMessage = if (state.isPanZoomEnabled) "Pan & Zoom Enabled (Pinch/Drag)" else "Pan & Zoom Disabled"
            state.markInteraction()
        },
        diameter = 40.dp,
        background = if (state.isPanZoomEnabled) Color.Magenta.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.14f)
    ) {
        Icon(
            imageVector = Icons.Filled.ZoomIn,
            contentDescription = "Zoom and Pan",
            tint = if (state.isPanZoomEnabled) Color.Magenta else Color.White,
            modifier = Modifier.size(20.dp)
        )
    }

    // 10. Picture-in-Picture Mode Button
    GlassControl(
        onClick = {
            state.activity?.let(::enterPip)
            state.markInteraction()
        },
        diameter = 40.dp
    ) {
        Icon(Icons.Filled.PictureInPicture, "Picture in picture", tint = Color.White, modifier = Modifier.size(20.dp))
    }

    // 11. Aspect Ratio Mode Toggle
    GlassControl(
        onClick = {
            val nextMode = nextResizeMode(state.resizeMode)
            state.resizeMode = nextMode
            state.statusMessage = when (nextMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit to Screen"
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoomed"
                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretched"
                else -> "Fit"
            }
            state.markInteraction()
        },
        diameter = 40.dp
    ) {
        Icon(Icons.Filled.AspectRatio, "Resize", tint = Color.White, modifier = Modifier.size(20.dp))
    }
}
