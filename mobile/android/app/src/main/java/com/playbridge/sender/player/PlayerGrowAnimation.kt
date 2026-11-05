package com.playbridge.sender.player

import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/** Modifiers for opening from a thumbnail: one for the video frame, one for everything around it. */
internal class GrowFromThumbnail(val videoModifier: Modifier, val surroundingModifier: Modifier)

private const val GROW_MS = 380

/**
 * Animates the video frame from [from] (the tapped thumbnail, in screen pixels) to its place,
 * then fades the rest of the player in. Nothing is shown until the frame's own bounds are
 * known, so it never flashes at full size first. With no [from], both modifiers do nothing.
 */
@Composable
internal fun rememberGrowFromThumbnail(from: Rect?, onDone: () -> Unit): GrowFromThumbnail {
    val start = remember { from }
    if (start == null) return remember { GrowFromThumbnail(Modifier, Modifier) }
    val progress = remember { Animatable(0f) }
    var target by remember { mutableStateOf<ComposeRect?>(null) }
    LaunchedEffect(target != null) {
        if (target == null) return@LaunchedEffect
        progress.animateTo(1f, tween(GROW_MS, easing = FastOutSlowInEasing))
        onDone()
    }
    val videoModifier = Modifier
        .onGloballyPositioned { coordinates ->
            if (target == null) target = ComposeRect(coordinates.positionOnScreen(), coordinates.size.toSize())
        }
        .graphicsLayer {
            val to = target
            if (to == null || to.width <= 0f || to.height <= 0f) {
                alpha = 0f
                return@graphicsLayer
            }
            val p = progress.value
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = lerp(start.width() / to.width, 1f, p)
            scaleY = lerp(start.height() / to.height, 1f, p)
            translationX = lerp(start.left - to.left, 0f, p)
            translationY = lerp(start.top - to.top, 0f, p)
            clip = true
            shape = if (p < 1f) RoundedCornerShape(lerp(8f, 0f, p).dp) else RectangleShape
        }
    val surroundingModifier = Modifier.alpha(((progress.value - 0.55f) / 0.45f).coerceIn(0f, 1f))
    return GrowFromThumbnail(videoModifier, surroundingModifier)
}
