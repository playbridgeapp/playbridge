package com.playbridge.sender.player

import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

/** Controls over video: a darkened layer with the top bar, centred transport and bottom bar. */
@Composable
internal fun PlayerControlsOverlay(
    state: PlayerScreenState,
    engine: PhonePlayerEngine,
    onClose: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.controlsVisible && !state.locked && state.errorMessage == null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        ) {
            val portrait = maxHeight > maxWidth
            PlayerTopBar(state, engine, portrait, onClose)
            PlayerCenterControls(state)
            PlayerBottomBar(state)
        }
    }
}

/**
 * Back and the title (up to two lines, with the queue position, player and repeat beneath),
 * plus Queue, Audio & subtitles and More. Portrait puts the title on its own row below the
 * icons so a long title isn't squeezed.
 */
@Composable
private fun BoxScope.PlayerTopBar(
    state: PlayerScreenState,
    engine: PhonePlayerEngine,
    portrait: Boolean,
    onClose: () -> Unit,
) {
    val actions: @Composable () -> Unit = {
        if (state.isPlaylist) {
            IconButton(onClick = { state.showEpisodes = true; state.markInteraction() }) {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, if (state.websiteQueue) "Queue" else "Episodes", tint = Color.White)
            }
        }
        IconButton(onClick = { state.showTracks = true; state.markInteraction() }) {
            Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = Color.White)
        }
        IconButton(onClick = { state.showMore = true; state.markInteraction() }) {
            Icon(Icons.Filled.MoreVert, "More", tint = Color.White)
        }
    }
    val heading: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = state.currentTitle ?: "",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TitleMeta(state, engine)
        }
    }
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            // The camera cutout stays clear even while the status bar is hidden.
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            if (portrait) Spacer(Modifier.weight(1f))
            else heading(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp, end = 8.dp))
            actions()
        }
        if (portrait) heading(Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp))
    }
}

/** "Queue · 3 of 12 · ExoPlayer", plus a tag while repeat is on. */
@Composable
private fun TitleMeta(state: PlayerScreenState, engine: PhonePlayerEngine) {
    val position = if (state.isPlaylist && state.episodeTitles.isNotEmpty()) {
        val noun = if (state.websiteQueue) "Queue" else "Episode"
        "$noun · ${state.currentIndex + 1} of ${state.episodeTitles.size} · "
    } else ""
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = position + engine.label,
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        val repeat = when (state.repeatMode) {
            Player.REPEAT_MODE_ONE -> "Repeat one"
            Player.REPEAT_MODE_ALL -> "Repeat all"
            else -> null
        }
        if (repeat != null) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                Text(
                    repeat,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Previous and next (queues only) around play/pause. Double-tap the sides to skip. */
@Composable
private fun BoxScope.PlayerCenterControls(state: PlayerScreenState) {
    Row(
        modifier = Modifier.align(Alignment.Center),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        if (state.isPlaylist) {
            GlassControl(
                onClick = { state.goToEpisode(state.currentIndex - 1); state.markInteraction() },
                diameter = 52.dp,
                background = Color.Transparent,
                enabled = state.currentIndex > 0
            ) {
                Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        GlassControl(
            onClick = { state.togglePlay() },
            diameter = 72.dp,
            background = Color.White.copy(alpha = 0.22f)
        ) {
            Crossfade(targetState = state.isPlaying, label = "PlayPauseAnimation") { playing ->
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
                diameter = 52.dp,
                background = Color.Transparent,
                enabled = state.currentIndex < state.episodeTitles.size - 1
            ) {
                Icon(Icons.Filled.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** The seek bar with its times, then lock, rotate and picture-in-picture, and status chips. */
@Composable
private fun BoxScope.PlayerBottomBar(state: PlayerScreenState) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        TimelineRow(state)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { state.locked = true; state.showUnlock = true }) {
                Icon(Icons.Filled.Lock, "Lock controls", tint = Color.White)
            }
            IconButton(onClick = {
                state.activity?.let { activity ->
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
            }) {
                Icon(Icons.Filled.ScreenRotation, "Rotate screen", tint = Color.White)
            }
            IconButton(onClick = { state.activity?.let(::enterPip); state.markInteraction() }) {
                Icon(Icons.Filled.PictureInPicture, "Picture in picture", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            AbLoopChip(state)
            ZoomChip(state)
            OverlayChip(
                text = speedLabel(state.speed),
                highlighted = state.speed != 1f,
                onClick = { state.showMore = true; state.markInteraction() },
            )
        }
    }
}

/** Elapsed time, the seek bar, and the duration (tap for time remaining). */
@Composable
private fun TimelineRow(state: PlayerScreenState) {
    val duration = state.playbackDuration.longValue
    val shown = if (state.isScrubbing) (state.scrubFraction * duration).toLong() else state.playbackProgress.longValue
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatTime(shown),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 44.dp).padding(start = 4.dp),
        )
        PlaybackTimeline(state, Modifier.weight(1f))
        TextButton(
            onClick = { state.showRemaining = !state.showRemaining; state.markInteraction() },
            modifier = Modifier.widthIn(min = 56.dp),
        ) {
            Text(
                text = if (state.showRemaining && duration > 0) "−${formatTime((duration - shown).coerceAtLeast(0))}" else formatTime(duration),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** While an A-B loop is set: its range, and an × to clear it. */
@Composable
private fun AbLoopChip(state: PlayerScreenState) {
    val start = state.abStartMs ?: return
    val end = state.abEndMs
    val text = if (end == null) "A ${formatTime(start)}" else "A-B ${formatTime(start)}–${formatTime(end)}"
    OverlayChip(text = text, highlighted = true, onClose = { state.clearAbLoop(); state.markInteraction() })
}

/** While zoomed in: the zoom level and Reset. */
@Composable
private fun ZoomChip(state: PlayerScreenState) {
    if (state.scale <= 1.01f) return
    OverlayChip(
        text = "${(state.scale * 100).toInt()}% · Reset",
        highlighted = true,
        onClick = { state.resetZoom(); state.markInteraction() },
    )
}

@Composable
private fun OverlayChip(
    text: String,
    highlighted: Boolean,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick ?: onClose ?: {},
        color = if (highlighted) colors.primaryContainer else Color.White.copy(alpha = 0.14f),
        contentColor = if (highlighted) colors.onPrimaryContainer else Color.White,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Row(
            modifier = Modifier.height(36.dp).padding(start = 14.dp, end = if (onClose != null) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (onClose != null) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
