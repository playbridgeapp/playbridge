package com.playbridge.player.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.playbridge.player.R
import com.playbridge.player.ui.theme.DarkTvColors
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * The confirmations for what a phone does (PhonePaused, PhoneSeeking, PhoneChange,
 * PhoneWhileControls). Nothing here is focusable, so it can never take focus from the TV remote.
 *
 * With the controls or a side panel up ([controlsUp]) the controls stay as they are and the
 * confirmations sit alongside, top right: a light "Paused from ..." pill, then the change card.
 * With nothing up, "Paused from ..." is a dark pill top left with a thin bottom bar, a phone seek
 * is the seek strip, and the change card is top right.
 *
 * @param rightTopInset extra room at the top right for something else drawn there (party members).
 */
@Composable
internal fun BoxScope.PhoneConfirmations(
    state: PlayerControlsState,
    progress: StateFlow<SeekProgress>,
    controlsUp: Boolean,
    seekCaption: String?,
    rightTopInset: Dp = 0.dp,
) {
    val paused = state.phonePaused
    val seek = state.phoneSeek
    val card = state.phoneCard
    if (paused == null && seek == null && card == null) return

    val showStrip = seek != null && !controlsUp
    val showMiniBar = paused != null && !controlsUp && !showStrip

    if (showStrip) {
        EdgeScrim(top = false, height = 190.dp, alpha = TvTheme.player.scrimTopAlpha, modifier = Modifier.align(Alignment.BottomCenter))
        SeekStrip(
            progress = progress,
            caption = seekCaption,
            showHints = false,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
        )
    } else if (showMiniBar) {
        EdgeScrim(top = false, height = 130.dp, alpha = TvTheme.player.scrimTopAlpha, modifier = Modifier.align(Alignment.BottomCenter))
        PausedMiniBar(
            title = state.title,
            progress = progress,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = TvSpacing.screenGutter, end = TvSpacing.screenGutter, bottom = 40.dp),
        )
    }

    // Top left: the dark pill when nothing else is up.
    if (paused != null && !controlsUp) {
        PhonePill(
            text = pausedFromText(paused.name), light = false,
            modifier = Modifier.align(Alignment.TopStart).padding(start = TvSpacing.screenGutter, top = TvSpacing.playerEdge),
        )
    }

    // Top right: with the controls up the pill moves here (light), with a seek pill and the card beneath it.
    val rightPill = when {
        !controlsUp -> null
        paused != null -> pausedFromText(paused.name)
        seek != null -> seekingText(seek.name)
        else -> null
    }
    if (rightPill != null || card != null) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(end = TvSpacing.screenGutter, top = TvSpacing.playerEdge + rightTopInset),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            rightPill?.let { PhonePill(text = it, light = true, showPauseIcon = paused != null) }
            card?.let { PhoneChangeCardView(it) }
        }
    }
}

@Composable
private fun PhonePill(text: String, light: Boolean, modifier: Modifier = Modifier, showPauseIcon: Boolean = true) {
    val bg = if (light) DarkTvColors.onSurface else TvTheme.player.panel
    val fg = if (light) DarkTvColors.surface else TvTheme.player.onSurface
    Row(
        modifier
            .height(52.dp)
            .background(bg, TvRadius.pill)
            .padding(start = 18.dp, end = 22.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPauseIcon) {
            Image(painterResource(R.drawable.ic_pause), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(fg))
        }
        Text(text, style = TvText.value.copy(fontSize = 18.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PhoneChangeCardView(card: PhoneChangeCard, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(TvTheme.player.panel, TvRadius.cardShape)
            .padding(start = 18.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(48.dp).background(TvTheme.player.control, TvRadius.circle), contentAlignment = Alignment.Center) {
            Image(
                painterResource(phoneChangeIcon(card.kind)), null, Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(TvTheme.player.onSurface),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                card.title, style = TvText.row.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = TvTheme.player.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(card.detail, style = TvText.caption, color = TvTheme.player.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun phoneChangeIcon(kind: PhoneChangeKind): Int = when (kind) {
    PhoneChangeKind.SUBTITLES -> R.drawable.ic_player_subtitles
    PhoneChangeKind.AUDIO -> R.drawable.ic_player_audio
    PhoneChangeKind.SPEED -> R.drawable.ic_play
    PhoneChangeKind.QUEUE -> R.drawable.ic_player_queue
}

/** Title, "6:42 / 14:48" and a thin bar along the bottom while a phone has paused (PhonePaused). */
@Composable
private fun PausedMiniBar(title: String, progress: StateFlow<SeekProgress>, modifier: Modifier = Modifier) {
    val state = progress.collectAsState()
    val track = TvTheme.player.track
    val played = TvTheme.player.onSurface
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(
                title, style = TvText.row.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = TvTheme.player.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            ClockText(state, style = TvText.caption.copy(fontSize = 17.sp), color = TvTheme.player.onSurfaceMuted) { p ->
                if (p.duration > 0L) "${formatClock(p.position)} / ${formatClock(p.duration)}" else formatClock(p.position)
            }
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).drawBehind {
                val r = CornerRadius(3.dp.toPx())
                drawRoundRect(track, size = size, cornerRadius = r)
                val p = state.value
                val fraction = if (p.duration > 0L) (p.position.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
                drawRoundRect(played, size = Size(size.width * fraction, size.height), cornerRadius = r)
            },
        )
    }
}
