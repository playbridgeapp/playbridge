package com.playbridge.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.playbridge.player.R
import com.playbridge.player.ui.components.IconCircleButton
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Progress and transport buttons on the music screen (mockup PlayerMusic), placed by
 * MusicPresentation under the track details. The progress bar is always shown. The buttons
 * appear with the full controls (OK, Up or Down) and keep their space while hidden, so nothing
 * shifts. Left and right seek while the buttons are hidden, as on video. Use inside
 * [PlayerDarkSurface].
 */
@Composable
fun MusicTransport(
    state: PlayerControlsState,
    progress: StateFlow<SeekProgress>,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = progress.collectAsState()
    val expanded = state.isVisible && state.isFullControlsVisible && state.activeOverlay == ActiveOverlay.NONE
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val track = TvTheme.colors.bright
            val fill = TvTheme.colors.accent
            Canvas(Modifier.fillMaxWidth().height(TvSize.seekBarHeight)) {
                val v = p.value
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(track, Offset.Zero, size, radius)
                val f = if (v.duration > 0L) (v.position.toFloat() / v.duration).coerceIn(0f, 1f) else 0f
                if (f > 0f) drawRoundRect(fill, Offset.Zero, Size(size.width * f, size.height), radius)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ClockText(p, color = TvTheme.colors.onSurfaceMuted) { formatClock(it.position) }
                ClockText(p, color = TvTheme.colors.onSurfaceMuted) {
                    if (it.duration > 0L) formatClock(it.duration) else ""
                }
            }
        }
        Box(Modifier.height(76.dp)) {
            if (expanded) {
                val playFocus = remember { FocusRequester() }
                val queueFocus = remember { FocusRequester() }
                val toQueue = state.lastClosedOverlay == ActiveOverlay.PLAYLIST_PICKER && state.hasPlaylist
                LaunchedEffect(toQueue) {
                    runCatching { (if (toQueue) queueFocus else playFocus).requestFocus() }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.hasPlaylist) {
                        IconCircleButton(
                            painterResource(R.drawable.ic_skip_previous), "Previous", onPrev, tone = TvTone.App,
                        )
                    }
                    IconCircleButton(
                        icon = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        onClick = onTogglePlay,
                        modifier = Modifier.focusRequester(playFocus),
                        size = 76.dp,
                        tone = TvTone.App,
                    )
                    if (state.hasPlaylist) {
                        IconCircleButton(
                            painterResource(R.drawable.ic_skip_next), "Next", onNext, tone = TvTone.App,
                        )
                        PillButton(
                            text = "Queue",
                            onClick = onPlaylist,
                            modifier = Modifier.focusRequester(queueFocus),
                            style = PillStyle.Secondary,
                            tone = TvTone.App,
                            height = TvSize.iconButton,
                        )
                    }
                }
            }
        }
    }
}
