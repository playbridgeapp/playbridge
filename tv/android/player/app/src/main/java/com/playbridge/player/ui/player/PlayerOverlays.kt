package com.playbridge.player.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.playbridge.player.R
import com.playbridge.player.player.SkipSegment
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.theme.DarkTvColors
import com.playbridge.player.ui.theme.ErrorContainer
import com.playbridge.player.ui.theme.LocalTvColors
import com.playbridge.player.ui.theme.OnErrorContainer
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * Full-screen player surfaces that are not drawn over video (music, error, Still Watching) always
 * use the dark brand palette, whatever theme the app screens use.
 */
@Composable
fun PlayerDarkSurface(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTvColors provides DarkTvColors, content = content)
}

/** A short message pill. [PlayerNotice.emphasis] draws the light pill used for the bookmark confirmations. */
@Composable
fun NoticePill(notice: PlayerNotice, modifier: Modifier = Modifier) {
    val bg = if (notice.emphasis) DarkTvColors.onSurface else TvTheme.player.panel
    val fg = if (notice.emphasis) DarkTvColors.surface else TvTheme.player.onSurface
    Row(
        modifier = modifier
            .height(46.dp)
            .background(bg, TvRadius.pill)
            .padding(horizontal = 18.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        notice.icon?.let { icon ->
            Image(
                painterResource(icon), null,
                Modifier.size(18.dp), colorFilter = ColorFilter.tint(fg),
            )
        }
        Text(notice.text, style = TvText.value, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small play or pause badge for a media key pressed with nothing else on screen. */
@Composable
fun TransportBadgeView(badge: TransportBadge, modifier: Modifier = Modifier) {
    if (badge == TransportBadge.NONE) return
    Box(
        modifier = modifier
            .size(56.dp)
            .background(TvTheme.player.background.copy(alpha = 0.72f), TvRadius.circle),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(if (badge == TransportBadge.PAUSED) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = if (badge == TransportBadge.PAUSED) "Paused" else "Playing",
            modifier = Modifier.size(28.dp),
            colorFilter = ColorFilter.tint(TvTheme.player.onSurface),
        )
    }
}

/**
 * "Skip intro" and friends (mockup PlayerSkip). Takes focus when it appears so OK skips.
 * Behaviour is unchanged: the skip-service decides when a segment is active, and auto-skip
 * (a setting) hides the button entirely. The mockup's countdown fill and "Skips by itself in
 * 4 seconds" line are not drawn: auto-skip here skips at once, with no countdown.
 */
@Composable
fun SkipSegmentButton(
    segment: SkipSegment,
    onSkip: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(segment) { runCatching { focus.requestFocus() } }
    PillButton(
        text = skipButtonLabel(segment.type),
        onClick = onSkip,
        style = PillStyle.Primary,
        tone = TvTone.Player,
        icon = painterResource(R.drawable.ic_player_skip),
        height = 68.dp,
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { onFocusChanged(it.isFocused) },
    )
}

/**
 * "Still watching?" (mockup PlayerStillWatching). The controller has already paused playback and
 * stops it when the countdown ends, so the line says "Stopping in ...". Any other key also
 * continues (handled by the host). [onStop] is the controller's stopWatching().
 */
@Composable
fun StillWatchingPrompt(
    title: String,
    secondsRemaining: Int,
    onContinue: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerDarkSurface {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        // The count at the moment the prompt appeared is the full bar.
        var total by remember { mutableIntStateOf(secondsRemaining.coerceAtLeast(1)) }
        if (secondsRemaining > total) total = secondsRemaining
        Box(
            modifier.fillMaxSize().background(TvTheme.player.background.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .background(TvTheme.colors.container, TvRadius.cardShape)
                    .padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Still watching?",
                        style = TvText.cardHeadline.copy(fontSize = 32.sp, lineHeight = 38.sp),
                        color = TvTheme.colors.onSurface,
                    )
                    if (title.isNotBlank()) {
                        Text(
                            title,
                            style = TvText.row.copy(fontWeight = FontWeight.Normal, fontSize = 18.sp),
                            color = TvTheme.colors.onSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fraction = stillWatchingFraction(secondsRemaining, total)
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(TvRadius.pill).background(TvTheme.colors.bright)) {
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(TvTheme.colors.accent, TvRadius.pill))
                    }
                    Text(
                        stillWatchingCountdownText(secondsRemaining),
                        style = TvText.caption,
                        color = TvTheme.colors.onSurfaceMuted,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(
                        text = "Keep watching",
                        onClick = onContinue,
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        style = PillStyle.Primary,
                        tone = TvTone.App,
                        height = 60.dp,
                    )
                    PillButton(
                        text = "Stop",
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        style = PillStyle.Secondary,
                        tone = TvTone.App,
                        height = 60.dp,
                    )
                }
            }
        }
    }
}

/**
 * Terminal playback failure (mockup PlayerError). The host leaves the player a moment after
 * showing this, as before, so there are no Try again / Try the other player / Go to Home buttons
 * and no "<phone> has been told" line: those need behaviour the app does not have.
 */
@Composable
fun PlaybackErrorScreen(title: String, reason: String, modifier: Modifier = Modifier) {
    PlayerDarkSurface {
        Box(
            modifier = modifier.fillMaxSize().background(TvTheme.colors.surface),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = TvSpacing.screenGutter).width(720.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Box(
                    Modifier.size(64.dp).background(ErrorContainer, TvRadius.circle),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painterResource(R.drawable.ic_player_error), null,
                        Modifier.size(30.dp), colorFilter = ColorFilter.tint(OnErrorContainer),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (title.isNotBlank()) "$title won't play" else "This video won't play",
                        style = TvText.pageTitle.copy(fontSize = 44.sp, lineHeight = 49.sp),
                        color = TvTheme.colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (reason.isNotBlank()) {
                        Text(
                            reason,
                            style = TvText.settingTitle.copy(fontWeight = FontWeight.Normal, lineHeight = 30.sp),
                            color = TvTheme.colors.onSurfaceMuted,
                        )
                    }
                }
            }
        }
    }
}

/** Dark gradient that keeps text readable over video: [alpha] at the screen edge, clear toward the middle. */
@Composable
fun EdgeScrim(top: Boolean, height: androidx.compose.ui.unit.Dp, alpha: Float, modifier: Modifier = Modifier) {
    val base = TvTheme.player.background
    val colors = if (top) listOf(base.copy(alpha = alpha), base.copy(alpha = alpha), Color.Transparent)
    else listOf(Color.Transparent, base.copy(alpha = alpha), base.copy(alpha = alpha))
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(colors, startY = 0f)),
    )
}
