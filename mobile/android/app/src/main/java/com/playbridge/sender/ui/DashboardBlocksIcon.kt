package com.playbridge.sender.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Dashboard "blocks" glyph (2×2 rounded tiles), drawn in Compose so the tiles can
 * animate. Every [pulseIntervalMs] the tiles do a quick staggered pop — a gentle
 * affordance cue that this is a tappable button, not static branding.
 *
 * Geometry matches `res/drawable/ic_dashboard_blocks.xml` (24-unit viewport, 7.5-unit
 * tiles at inset 3, corner radius 2), which remains the source for non-Compose surfaces.
 *
 * Pass [animated] = false where motion would be noise (e.g. inside dialogs/tooltips).
 */
@Composable
fun DashboardBlocksIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    animated: Boolean = true,
    pulseIntervalMs: Long = 8_000L,
) {
    // One scale per tile: TL, TR, BL, BR.
    val tileScales = remember { List(4) { Animatable(1f) } }

    if (animated) {
        LaunchedEffect(pulseIntervalMs) {
            while (isActive) {
                delay(pulseIntervalMs)
                // Staggered pop: each tile grows then springs back, 90ms apart.
                coroutineScope {
                    tileScales.forEachIndexed { index, scale ->
                        launch {
                            delay(index * 90L)
                            scale.animateTo(1.35f, tween(150, easing = FastOutSlowInEasing))
                            scale.animateTo(
                                1f,
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    Canvas(
        modifier = modifier.semantics { contentDescription = "Dashboard" }
    ) {
        val unit = size.minDimension / 24f
        val tileSize = 7.5f * unit
        val radius = 2f * unit
        // Top-left corners of the four tiles in viewport units.
        val origins = listOf(
            Offset(3f, 3f), Offset(13.5f, 3f),
            Offset(3f, 13.5f), Offset(13.5f, 13.5f),
        )
        origins.forEachIndexed { index, origin ->
            val scale = tileScales[index].value
            val cx = (origin.x * unit) + tileSize / 2f
            val cy = (origin.y * unit) + tileSize / 2f
            val half = (tileSize * scale) / 2f
            drawRoundRect(
                color = tint,
                topLeft = Offset(cx - half, cy - half),
                size = Size(tileSize * scale, tileSize * scale),
                cornerRadius = CornerRadius(radius * scale),
            )
        }
    }
}
