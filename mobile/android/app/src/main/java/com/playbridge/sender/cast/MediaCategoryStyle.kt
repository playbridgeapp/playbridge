package com.playbridge.sender.cast

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Stable semantic accents shared by detected-media tabs, cards, and toolbar badges. */
@Composable
internal fun mediaCategoryAccent(kind: DetectedMediaKind): Color {
    val dark = isSystemInDarkTheme()
    return when (kind) {
        DetectedMediaKind.VIDEO -> if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
        DetectedMediaKind.AUDIO -> if (dark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)
        DetectedMediaKind.IMAGE -> if (dark) Color(0xFF80CBC4) else Color(0xFF00796B)
        DetectedMediaKind.SUBTITLE -> if (dark) Color(0xFFFFCC80) else Color(0xFFEF6C00)
    }
}

internal fun mediaCategoryContentColor(accent: Color): Color =
    if (accent.luminance() > 0.52f) Color(0xFF101418) else Color.White
