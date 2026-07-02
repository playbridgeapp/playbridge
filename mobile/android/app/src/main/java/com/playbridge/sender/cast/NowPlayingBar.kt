package com.playbridge.sender.cast

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Persistent cast mini-bar shown across the main screens. It has two visual modes:
 *
 *  - **Playing**: a cast session is active with media loaded — shows the title + "on <device>";
 *    tapping opens the Remote. Shows a TV icon button on the right to open the connection sheet,
 *    an animated equalizer on the left (frozen into a pause glyph while [isPaused]), and a thin
 *    [progress] line along the bottom edge.
 *  - **Idle**: no media is playing — shows the current cast destination (a connected TV/renderer,
 *    or "This Device" when nothing is connected) and tapping opens the device picker.
 *
 * The bar is intentionally "dumb": the host computes [primaryText]/[secondaryText]/[leadingIcon]
 * and the mode flags from the live connection state.
 *
 * Styling: a glassy capsule — the container color ([accentColor] when the library detail poster
 * provides one, else primaryContainer) swept into a subtle horizontal gradient, with a
 * luminance-adaptive hairline border and content color.
 */
@Composable
fun NowPlayingBar(
    primaryText: String,
    secondaryText: String?,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isPaused: Boolean = false,
    /** 0..1 playback progress for the bottom edge line; null hides it (e.g. live streams). */
    progress: Float? = null,
    showTvIcon: Boolean = false,
    onTvIconClick: (() -> Unit)? = null,
    accentColor: Color? = null,
) {
    val container = accentColor ?: MaterialTheme.colorScheme.primaryContainer
    val content = when {
        accentColor != null -> if (accentColor.luminance() > 0.5f) Color.Black else Color.White
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    // Subtle depth: sweep the container toward its darker sibling instead of a flat fill.
    val gradient = Brush.horizontalGradient(
        colors = listOf(
            container,
            lerp(container, Color.Black, if (container.luminance() > 0.5f) 0.10f else 0.35f),
        )
    )
    val hairline = content.copy(alpha = 0.18f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, hairline),
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.background(gradient)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Leading slot in a soft rounded tile: equalizer while playing, a pause
                // glyph while paused (the equalizer must NOT keep dancing then), or the
                // destination icon when idle.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(content.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isPlaying && isPaused -> Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = "Paused",
                            tint = content,
                            modifier = Modifier.size(20.dp),
                        )
                        isPlaying -> PlayingEqualizer(
                            color = content,
                            modifier = Modifier.size(18.dp),
                        )
                        else -> Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = content,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = primaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (secondaryText != null) {
                        Text(
                            text = if (isPlaying && isPaused) "Paused · $secondaryText" else secondaryText,
                            style = MaterialTheme.typography.labelSmall,
                            color = content.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showTvIcon && onTvIconClick != null) {
                    IconButton(onClick = onTvIconClick) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(content.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = "Connection Sheet",
                                tint = content,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Thin progress line hugging the bottom edge while media is loaded.
            if (isPlaying && progress != null) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress.coerceIn(0f, 1f),
                    animationSpec = tween(600, easing = LinearEasing),
                    label = "miniBarProgress",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(content.copy(alpha = 0.20f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .background(content),
                    )
                }
            }
        }
    }
}

/**
 * A small "now playing" equalizer: a few bars that bounce up and down forever.
 * Purely decorative — signals active playback in the mini-bar. Hosts must swap it
 * out (not just let it run) when playback is paused; see [NowPlayingBar]'s leading slot.
 */
@Composable
private fun PlayingEqualizer(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    // Staggered phase per bar so they don't bounce in unison.
    val delays = listOf(0, 220, 90, 310)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        delays.forEach { delay ->
            val fraction by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 480,
                        delayMillis = delay,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}
