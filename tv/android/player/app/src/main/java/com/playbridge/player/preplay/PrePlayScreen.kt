package com.playbridge.player.preplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.playbridge.player.ui.components.LoadingBlob
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.TvFocusSurface
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import playbridge.VisualMetadata

/**
 * Pre-play screen shown over the player while the stream buffers.
 *
 * Without a saved place ([resumePositionMs] null) it behaves as before: a status line, a countdown
 * caption and "Play now". With a saved place the countdown becomes a short choice window: "Resume
 * from 1:10" (fills as the window runs out, calls [onStartNow]) and "Start over" (calls [onStartOver]).
 * Both choice controls render only when [resumePositionMs] and [onStartOver] are non-null.
 *
 * @param launchCountdown seconds left (> 0 shows the buttons), 0 = starting, -1 = connecting.
 * @param choiceWindowSeconds length the host counts down from; only scales the Resume fill.
 * @param resumeDurationMs total length, when known, for the "1:10 of 2:26" line and bar.
 */
@Composable
fun PrePlayScreen(
    metadata: VisualMetadata,
    isLaunching: Boolean = false,
    launchCountdown: Int = 0,
    onStartNow: () -> Unit,
    onBack: () -> Unit,
    resumePositionMs: Long? = null,
    resumeDurationMs: Long? = null,
    onStartOver: (() -> Unit)? = null,
    choiceWindowSeconds: Int = 5,
) {
    val context = LocalContext.current
    val primaryFocus = remember { FocusRequester() }
    val showButtons = launchCountdown > 0
    val resumeChoice = resumePositionMs != null && onStartOver != null && resumePositionMs > 0L

    LaunchedEffect(showButtons) {
        if (showButtons) runCatching { primaryFocus.requestFocus() }
    }

    BackHandler(onBack = onBack)

    PlayBridgeTVTheme {
        val colors = TvTheme.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surface)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(metadata.backdrop_url ?: metadata.poster_url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Solid on the text side, fading out to the right. Painted in the draw phase only.
            val scrim = colors.surface
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to scrim.copy(alpha = 0.94f),
                            0.547f to scrim.copy(alpha = 0.94f),
                            0.64f to scrim.copy(alpha = 0.6f),
                            0.734f to scrim.copy(alpha = 0.3f),
                            0.83f to Color.Transparent,
                        ),
                    )
                },
            )

            Column(
                modifier = Modifier
                    .padding(start = TvSpacing.screenGutter, top = 130.dp)
                    .width(600.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                TitleBlock(metadata)
                MetaChips(metadata)
                val overview = metadata.overview
                if (!overview.isNullOrBlank()) {
                    Text(
                        text = overview,
                        style = TvText.settingTitle.copy(fontSize = 19.sp, lineHeight = 29.sp, fontWeight = FontWeight.Normal),
                        color = colors.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(540.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = TvSpacing.screenGutter, bottom = 48.dp)
                    .width(640.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (resumeChoice) {
                    SavedPlace(resumePositionMs, resumeDurationMs)
                }
                if (showButtons) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (resumeChoice) {
                            ResumeButton(
                                label = "Resume from ${formatPlaybackTime(resumePositionMs)}",
                                progress = choiceWindowProgress(launchCountdown, choiceWindowSeconds),
                                onClick = onStartNow,
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                            PillButton(
                                text = "Start over",
                                onClick = onStartOver,
                                style = PillStyle.Secondary,
                                height = 64.dp,
                                icon = rememberVectorPainter(Icons.Filled.Refresh),
                            )
                        } else {
                            PillButton(
                                text = "Play now",
                                onClick = onStartNow,
                                height = 64.dp,
                                icon = rememberVectorPainter(Icons.Filled.PlayArrow),
                                modifier = Modifier.focusRequester(primaryFocus),
                            )
                        }
                    }
                }
                StatusLine(isLaunching = isLaunching, countdown = launchCountdown, resuming = resumeChoice)
            }
        }
    }
}

@Composable
private fun TitleBlock(metadata: VisualMetadata) {
    val colors = TvTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        episodeLabel(metadata.season, metadata.episode)?.let {
            Text(it, style = TvText.settingTitle, color = colors.accent)
        }
        if (metadata.logo_url != null) {
            AsyncImage(
                model = metadata.logo_url,
                contentDescription = metadata.title,
                modifier = Modifier.height(110.dp).width(420.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
        } else {
            Text(
                text = metadata.title,
                style = TvText.pageTitle.copy(fontSize = 64.sp, lineHeight = 68.sp),
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (metadata.season != null && metadata.episode != null && !metadata.episode_title.isNullOrBlank()) {
            Text(
                text = metadata.episode_title!!,
                style = TvText.cardHeadline.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetaChips(metadata: VisualMetadata) {
    val items = listOfNotNull(
        metadata.year,
        metadata.rating?.let { "IMDb $it" },
        metadata.runtime,
    ).filter { it.isNotBlank() }
    if (items.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { text ->
            Box(
                Modifier
                    .height(34.dp)
                    .background(TvTheme.colors.containerHigh, TvRadius.pill)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = TvText.value, color = TvTheme.colors.onSurface, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SavedPlace(positionMs: Long, durationMs: Long?) {
    val colors = TvTheme.colors
    Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (durationMs != null && durationMs > 0L) {
            val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth().height(6.dp).drawBehind {
                    val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                    drawRoundRect(colors.outline, size = size, cornerRadius = r)
                    drawRoundRect(colors.accent, size = Size(size.width * fraction, size.height), cornerRadius = r)
                },
            )
        }
        val text = if (durationMs != null && durationMs > 0L) {
            "You stopped at ${formatPlaybackTime(positionMs)} of ${formatPlaybackTime(durationMs)}"
        } else {
            "You stopped at ${formatPlaybackTime(positionMs)}"
        }
        Text(text, style = TvText.caption, color = colors.onSurfaceMuted)
    }
}

/** The primary choice: a pill whose accent fill grows as the choice window runs out. */
@Composable
private fun ResumeButton(
    label: String,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TvTheme.colors
    val fill = animateFloatAsState(progress, tween(1000, easing = LinearEasing), label = "resume window")
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = TvRadius.pill,
        container = colors.accent,
        focusedContainer = colors.focusFill,
        ringColor = colors.focusRing,
    ) { focused ->
        Box(
            Modifier
                .matchParentSize()
                .clip(TvRadius.pill)
                .drawBehind {
                    drawRect(
                        if (focused) colors.accent else colors.onAccent.copy(alpha = 0.18f),
                        topLeft = Offset.Zero,
                        size = Size(size.width * fill.value, size.height),
                    )
                },
        )
        Row(
            Modifier.padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.Image(
                rememberVectorPainter(Icons.Filled.PlayArrow), null, Modifier.size(22.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (focused) colors.onFocusFill else colors.onAccent),
            )
            Text(
                label,
                style = TvText.button.copy(fontSize = 20.sp),
                color = if (focused) colors.onFocusFill else colors.onAccent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatusLine(isLaunching: Boolean, countdown: Int, resuming: Boolean) {
    val colors = TvTheme.colors
    when {
        countdown > 0 -> {
            val seconds = if (countdown == 1) "1 second" else "$countdown seconds"
            Text(
                text = if (resuming) "Resuming in $seconds. Press OK to go now." else "Starting in $seconds. Press OK to go now.",
                style = TvText.caption,
                color = colors.onSurfaceMuted,
            )
        }
        countdown == -1 && isLaunching -> StatusSpinner("Connecting to stream…")
        isLaunching || countdown == 0 -> StatusSpinner("Starting playback…")
    }
}

@Composable
private fun StatusSpinner(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LoadingBlob(modifier = Modifier.size(32.dp))
        Text(text, style = TvText.row, color = TvTheme.colors.onSurface)
    }
}
