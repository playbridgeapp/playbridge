package com.playbridge.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * Small non-focusable status chip, 34dp tall, e.g. "Pixel 9 connected". [onTile] uses the darker
 * on-thumbnail variant (28dp, 14sp). Not an input: it never takes focus.
 */
@Composable
fun TvChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    onTile: Boolean = false,
    contentDescription: String = text,
) {
    val bg = if (onTile) TvTheme.colors.surface else TvTheme.colors.accentContainer
    val fg = if (onTile) TvTheme.colors.onSurface else TvTheme.colors.onAccentContainer
    Row(
        modifier = modifier
            .height(if (onTile) 28.dp else TvSize.chip)
            .background(bg, TvRadius.pill)
            .padding(horizontal = if (onTile) 10.dp else 14.dp)
            .semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (onTile) 6.dp else 8.dp),
    ) {
        if (icon != null) Image(icon, null, Modifier.size(if (onTile) 16.dp else 18.dp), colorFilter = ColorFilter.tint(fg))
        Text(text, style = if (onTile) TvText.micro else TvText.value.copy(fontSize = TvText.caption.fontSize), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
