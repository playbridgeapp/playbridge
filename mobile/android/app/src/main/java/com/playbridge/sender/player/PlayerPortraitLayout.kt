package com.playbridge.sender.player

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Back, plus Queue, Audio & subtitles and More, in the bar above the video. */
@Composable
internal fun PortraitTopBar(state: PlayerScreenState, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 4.dp)
            .height(PORTRAIT_TOP_BAR_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Spacer(Modifier.weight(1f))
        if (state.isPlaylist) {
            IconButton(onClick = { state.showEpisodes = true }) {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, if (state.websiteQueue) "Queue" else "Episodes", tint = Color.White)
            }
        }
        IconButton(onClick = { state.showTracks = true }) { Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = Color.White) }
        IconButton(onClick = { state.showMore = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
    }
}

/** The fullscreen button in the corner of the portrait video. */
@Composable
internal fun BoxScope.FullscreenButton(onFullscreen: (Boolean) -> Unit) {
    IconButton(onClick = { onFullscreen(true) }, modifier = Modifier.align(Alignment.BottomEnd)) {
        Icon(Icons.Filled.Fullscreen, "Fullscreen", tint = Color.White)
    }
}

/**
 * Below the portrait video: the title, seek bar, transport, a nudge to go fullscreen, and what
 * plays next in a queue.
 */
@Composable
internal fun PortraitPanel(
    state: PlayerScreenState,
    engine: PhonePlayerEngine,
    onFullscreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = state.context
    // With auto-rotate off, turning the phone does nothing, so the nudge becomes a tap.
    val rotationLocked = remember {
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 16.dp, bottom = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = state.currentTitle ?: "",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TitleMeta(state, engine)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.padding(horizontal = 8.dp)) { TimelineRow(state) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isPlaylist) {
                IconButton(onClick = { state.goToEpisode(state.currentIndex - 1) }, enabled = state.currentIndex > 0) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", modifier = Modifier.size(30.dp))
                }
            }
            FilledIconButton(
                onClick = state::togglePlay,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Crossfade(targetState = state.isPlaying, label = "PortraitPlayPause") { playing ->
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            if (state.isPlaylist) {
                IconButton(
                    onClick = { state.goToEpisode(state.currentIndex + 1) },
                    enabled = state.currentIndex < state.episodeTitles.size - 1,
                ) {
                    Icon(Icons.Filled.SkipNext, "Next", modifier = Modifier.size(30.dp))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { state.activity?.let(::enterPip) }) {
                Icon(Icons.Filled.PictureInPicture, "Picture in picture", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            AbLoopChip(state)
            ZoomChip(state)
            OverlayChip(text = speedLabel(state.speed), highlighted = state.speed != 1f, onClick = { state.showMore = true })
        }
        Surface(
            onClick = { onFullscreen(true) },
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (rotationLocked) Icons.Filled.Fullscreen else Icons.Filled.ScreenRotation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (rotationLocked) "Tap for fullscreen" else "Turn your phone for fullscreen",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        UpNext(state)
    }
}

/** The rest of a playlist or website queue after the current item; tap one to play it. */
@Composable
private fun UpNext(state: PlayerScreenState) {
    val upcoming = state.episodeTitles.withIndex().drop(state.currentIndex + 1)
    if (!state.isPlaylist || upcoming.isEmpty()) return
    HorizontalDivider(Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        "UP NEXT",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
    upcoming.forEach { (index, title) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { state.goToEpisode(index) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal val PORTRAIT_TOP_BAR_HEIGHT = 56.dp
