package com.playbridge.player.ui.player

import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * Title and one context line at the top left of the full controls (mockup PlayerControls):
 * 30sp title, 16sp muted line under it ("From Pixel 9 · 2 of 4 in the queue", "Paused · 8 min left").
 * [contextContent] replaces the line with other content (the Watch Together party label).
 */
@Composable
fun TopMetadata(
    title: String,
    context: String?,
    modifier: Modifier = Modifier,
    contextContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TvSpacing.screenGutter, end = TvSpacing.screenGutter, top = TvSpacing.playerEdge),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = TvText.panelTitle,
            color = TvTheme.player.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (contextContent != null) {
            contextContent()
        } else if (!context.isNullOrBlank()) {
            Text(
                text = context,
                style = TvText.caption,
                color = TvTheme.player.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
