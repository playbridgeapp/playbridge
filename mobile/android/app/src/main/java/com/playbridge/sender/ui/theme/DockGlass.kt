package com.playbridge.sender.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Nuvio-inspired charcoal glass shared by the browser shortcut and cast picker. */
internal object DockGlass {
    val tint = Color(0xFF1C1C1E)
    val foreground = Color.White.copy(alpha = 0.9f)
    val mutedForeground = Color.White.copy(alpha = 0.74f)
    val accent = Primary
    val rim = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.02f)),
    )

    /** Translucent tint with a quiet reflection, without capturing the underlying page. */
    fun background(opacity: Float): Brush {
        val base = tint.copy(alpha = opacity)
        return Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.035f).compositeOver(base), base),
        )
    }

    // Scoped to the picker: brighter labels and distinct rows over the charcoal glass.
    val pickerColors = darkColorScheme(
        primary = accent,
        onPrimary = OnPrimary,
        primaryContainer = accent.copy(alpha = 0.14f),
        onPrimaryContainer = foreground,
        secondary = accent,
        onSecondary = OnSecondary,
        secondaryContainer = accent.copy(alpha = 0.12f),
        onSecondaryContainer = foreground,
        tertiary = accent,
        onTertiary = OnTertiary,
        tertiaryContainer = accent.copy(alpha = 0.12f),
        onTertiaryContainer = foreground,
        background = Color.Transparent,
        onBackground = foreground,
        surface = Color.Transparent,
        onSurface = Color.White.copy(alpha = 0.96f),
        surfaceVariant = Color.White.copy(alpha = 0.16f),
        onSurfaceVariant = Color.White.copy(alpha = 0.8f),
        surfaceContainerLowest = Color.Transparent,
        surfaceContainerLow = Color.White.copy(alpha = 0.035f),
        surfaceContainer = Color.White.copy(alpha = 0.08f),
        surfaceContainerHigh = Color.White.copy(alpha = 0.1f),
        surfaceContainerHighest = Color.White.copy(alpha = 0.12f),
        surfaceTint = Color.Transparent,
        outline = Color.White.copy(alpha = 0.2f),
        outlineVariant = Color.White.copy(alpha = 0.1f),
        error = Color(0xFFFFB4AB),
        onError = tint,
        errorContainer = Color(0xFFFFB4AB).copy(alpha = 0.14f),
        onErrorContainer = Color(0xFFFFDAD6),
    )
}
