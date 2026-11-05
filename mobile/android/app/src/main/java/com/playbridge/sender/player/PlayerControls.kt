package com.playbridge.sender.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** A circular translucent "glass" button used for the transport controls. */
@Composable
internal fun GlassControl(
    onClick: () -> Unit,
    diameter: Dp,
    modifier: Modifier = Modifier,
    background: Color = Color.White.copy(alpha = 0.14f),
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(diameter)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.matchParentSize()) { content() }
    }
}

internal fun speedLabel(s: Float): String {
    val n = if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0').trimEnd('.')
    return "${n}×"
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
