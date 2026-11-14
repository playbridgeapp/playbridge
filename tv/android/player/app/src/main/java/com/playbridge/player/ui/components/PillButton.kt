package com.playbridge.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/** Visual weight of a [PillButton]. */
enum class PillStyle { Primary, Secondary, Quiet }

/**
 * Fixed-height pill button (56dp by default, 48dp via [height] = [TvSize.pillHeightCompact]).
 * Width is whatever [modifier] gives it (e.g. `Modifier.width(200.dp)` or `Modifier.weight(1f)`),
 * otherwise it wraps its single-line label. Focus swaps colour and draws the ring only.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    tone: TvTone = TvTone.App,
    icon: Painter? = null,
    height: Dp = TvSize.pillHeight,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val c = tvControlColors(tone)
    val accent = if (tone == TvTone.App) TvTheme.colors.accent else TvTheme.player.onSurface
    val onAccent = if (tone == TvTone.App) TvTheme.colors.onAccent else TvTheme.player.onFocusFill
    val container = when (style) {
        PillStyle.Primary -> accent
        PillStyle.Secondary -> c.container
        PillStyle.Quiet -> Color.Transparent
    }
    val rest = when (style) {
        PillStyle.Primary -> onAccent
        PillStyle.Secondary -> c.content
        PillStyle.Quiet -> c.muted
    }
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = TvRadius.pill,
        container = container,
        focusedContainer = c.focusedContainer,
        ringColor = c.ring,
        enabled = enabled,
        contentDescription = contentDescription,
    ) { focused ->
        val fg = (if (focused) c.focusedContent else rest).let { if (enabled) it else it.copy(alpha = 0.4f) }
        Row(
            modifier = Modifier.padding(horizontal = TvSpacing.xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(fg))
            }
            Text(
                text = text,
                style = if (style == PillStyle.Quiet) TvText.value else TvText.button,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
