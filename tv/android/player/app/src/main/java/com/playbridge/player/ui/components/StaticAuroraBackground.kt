package com.playbridge.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme

/**
 * A static, multi-color gradient background inspired by Apple TV's Aurora look.
 *
 * The gradient is derived from the active theme's colours (not hardcoded), so it
 * adapts to Dark / AMOLED / Light instead of always showing the same purple. This
 * background sits behind the whole app and the panels above it are translucent,
 * so it is what makes theme changes visible across the UI.
 */
@Composable
fun StaticAuroraBackground(
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme

    // background → a lighter surface band → back to background gives a soft
    // "aurora" sweep in whatever tone the current theme uses.
    val auroraColors = listOf(
        cs.background,
        cs.surfaceVariant,
        cs.background,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = auroraColors))
    ) {
        // Subtle corner glow tinted by the theme's accent (simulated light source).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            cs.primary.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(0f, 0f),
                        radius = 2200f
                    )
                )
        )
    }
}
