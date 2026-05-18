package com.playbridge.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A static, multi-color gradient background inspired by Apple TV's Aurora look.
 * Provides a premium aesthetic without the performance cost of animations.
 */
@Composable
fun StaticAuroraBackground(
    modifier: Modifier = Modifier
) {
    // A sophisticated dark gradient palette
    val auroraColors = listOf(
        Color(0xFF0F0C29), // Deep Blue/Black
        Color(0xFF302B63), // Purple/Navy
        Color(0xFF24243E), // Indigo focus
        Color(0xFF0F0C29)  // Back to dark
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = auroraColors
                )
            )
    ) {
        // Subtle secondary radial overlay for depth (simulating light source)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF4A4E69).copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(0f, 0f),
                        radius = 2000f
                    )
                )
        )
    }
}
