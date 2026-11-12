package com.playbridge.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize

/**
 * Circular icon button with a fixed [size] (60dp default; 72dp for play/pause; 48dp in headers).
 * [contentDescription] is required because the icon is the only label. [active] keeps the
 * button filled (e.g. bookmarked) when it is not focused. Focus never changes the size.
 */
@Composable
fun IconCircleButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = TvSize.iconButton,
    tone: TvTone = TvTone.Player,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val c = tvControlColors(tone)
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = TvRadius.circle,
        container = if (active) c.focusedContainer else c.container,
        focusedContainer = c.focusedContainer,
        ringColor = c.ring,
        enabled = enabled,
        contentDescription = contentDescription,
    ) { focused ->
        val tint = if (focused || active) c.focusedContent else c.content
        Image(
            icon, contentDescription = null,
            modifier = Modifier.size(size * 0.4f),
            colorFilter = ColorFilter.tint(if (enabled) tint else tint.copy(alpha = 0.4f)),
        )
    }
}
