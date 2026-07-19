package com.playbridge.player.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** A soft, organic loading indicator designed to remain visible over dark video frames. */
@Composable
fun LoadingBlob(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "loading blob")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
        ),
        label = "blob morph",
    )
    val scale by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blob pulse",
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blob glow",
    )

    Canvas(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(Modifier.size(76.dp)),
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.29f
        val top = baseRadius * (1f + 0.13f * sin(phase))
        val right = baseRadius * (1f + 0.16f * sin(phase + 1.7f))
        val bottom = baseRadius * (1f + 0.14f * sin(phase + 3.2f))
        val left = baseRadius * (1f + 0.17f * sin(phase + 4.8f))

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF745CFF).copy(alpha = glowAlpha),
                    Color(0xFF302553).copy(alpha = glowAlpha * 0.55f),
                    Color.Transparent,
                ),
                center = center,
                radius = size.minDimension * 0.5f,
            ),
            radius = size.minDimension * 0.5f,
            center = center,
        )

        val blob = Path().apply {
            moveTo(center.x, center.y - top)
            cubicTo(
                center.x + right * 0.62f,
                center.y - top * 0.96f,
                center.x + right,
                center.y - right * 0.48f,
                center.x + right,
                center.y,
            )
            cubicTo(
                center.x + right,
                center.y + bottom * 0.6f,
                center.x + bottom * 0.55f,
                center.y + bottom,
                center.x,
                center.y + bottom,
            )
            cubicTo(
                center.x - left * 0.62f,
                center.y + bottom,
                center.x - left,
                center.y + left * 0.5f,
                center.x - left,
                center.y,
            )
            cubicTo(
                center.x - left,
                center.y - top * 0.62f,
                center.x - top * 0.52f,
                center.y - top,
                center.x,
                center.y - top,
            )
            close()
        }

        rotate(
            degrees = phase * 180f / PI.toFloat(),
            pivot = center,
        ) {
            drawPath(
                path = blob,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF332B48), Color(0xFF0B0A10), Color(0xFF20182F)),
                    start = Offset(center.x - baseRadius, center.y - baseRadius),
                    end = Offset(center.x + baseRadius, center.y + baseRadius),
                ),
            )
            drawPath(
                path = blob,
                color = Color(0xFF9B87FF).copy(alpha = 0.58f),
                style = Stroke(width = size.minDimension * 0.018f),
            )
        }

        drawCircle(
            color = Color.White.copy(alpha = 0.12f + glowAlpha * 0.12f),
            radius = baseRadius * 0.19f,
            center = Offset(center.x - baseRadius * 0.33f, center.y - baseRadius * 0.35f),
        )
    }
}
