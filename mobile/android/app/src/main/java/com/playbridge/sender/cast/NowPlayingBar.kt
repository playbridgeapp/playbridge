package com.playbridge.sender.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp),
            )
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
