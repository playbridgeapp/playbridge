package com.playbridge.sender.cast

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Persistent cast mini-bar shown across the main screens. It has two visual modes:
 *
 *  - **Playing**: a cast session is active with media loaded — shows the title + "on <device>";
 *    tapping opens the Remote. Shows a TV icon button on the right to open the connection sheet.
 *  - **Idle**: no media is playing — shows the current cast destination (a connected TV/renderer,
 *    or "This Device" when nothing is connected) and tapping opens the device picker.
 *
 * The bar is intentionally "dumb": the host computes [primaryText]/[secondaryText]/[leadingIcon]
 * and the mode flags from the live connection state.
 *
 * Styling follows the old remote FAB it replaced: [accentColor] (e.g. the library detail
 * poster's dominant color) fills the bar with a luminance-picked content color; without an
 * accent it uses the FAB's primaryContainer look.
 */
@Composable
fun NowPlayingBar(
    primaryText: String,
    secondaryText: String?,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    showTvIcon: Boolean = false,
    onTvIconClick: (() -> Unit)? = null,
    accentColor: Color? = null,
) {
    val container = accentColor ?: MaterialTheme.colorScheme.primaryContainer
    val content = when {
        accentColor != null -> if (accentColor.luminance() > 0.5f) Color.Black else Color.White
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = container,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // When media is playing, the left slot becomes an animated equalizer so the
            // bar reads as "now playing" at a glance — and so it's visually distinct from
            // the TV/connection-sheet icon on the right.
            if (isPlaying) {
                PlayingEqualizer(
                    color = content,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondaryText != null) {
                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showTvIcon && onTvIconClick != null) {
                IconButton(onClick = onTvIconClick) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = "Connection Sheet",
                        tint = content,
                    )
                }
            }
        }
    }
}

/**
 * A small "now playing" equalizer: a few bars that bounce up and down forever.
 * Purely decorative — signals active playback in the mini-bar.
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
