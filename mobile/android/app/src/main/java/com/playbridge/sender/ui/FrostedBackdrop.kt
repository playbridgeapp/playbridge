package com.playbridge.sender.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.core.graphics.createBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.playbridge.sender.ui.theme.DockGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal data class FrostedBackdrop(
    val image: ImageBitmap,
    val screenOrigin: IntOffset,
    val size: IntSize,
)

/** A small, in-memory snapshot for devices without cross-window blur. Never saved to disk. */
@Composable
internal fun rememberFrostedBackdrop(
    enabled: Boolean,
    refreshIntervalMillis: Long? = null,
    refreshKey: Any? = null,
): FrostedBackdrop? {
    val activity = LocalActivity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var backdrop by remember(activity) { mutableStateOf<FrostedBackdrop?>(null) }
    LaunchedEffect(activity, enabled, refreshIntervalMillis, refreshKey, lifecycle, windowSize) {
        backdrop = null
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activity != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    backdrop = captureBackdrop(activity.window)
                    val interval = refreshIntervalMillis ?: break
                    delay(interval)
                }
            }
        }
    }
    return backdrop
}

/** Only the backdrop is blurred; the modifier's content is drawn sharply on top. */
@Composable
internal fun Modifier.frostedGlass(
    backdrop: FrostedBackdrop?,
    opacity: Float,
    shape: Shape,
    blurredByWindow: Boolean = false,
): Modifier {
    var screenPosition by remember { mutableStateOf(IntOffset.Zero) }
    val radius = with(LocalDensity.current) { 24.dp.toPx() }
    val effect = remember(radius) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).asComposeRenderEffect()
        } else null
    }
    val layer = rememberGraphicsLayer()
    val glass = remember(opacity, backdrop != null, effect, blurredByWindow) {
        DockGlass.background(if (blurredByWindow || (backdrop != null && effect != null)) opacity else 0.96f)
    }
    return clip(shape)
        .onGloballyPositioned { coordinates ->
            val position = coordinates.positionOnScreen()
            screenPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt())
        }
        .drawWithCache {
            val drawBackdrop = backdrop != null && effect != null && !blurredByWindow
            if (drawBackdrop) {
                val offset = screenPosition - backdrop.screenOrigin
                layer.renderEffect = effect
                layer.record {
                    drawImage(
                        image = backdrop.image,
                        dstOffset = IntOffset(-offset.x, -offset.y),
                        dstSize = backdrop.size,
                    )
                }
            }
            onDrawBehind {
                if (drawBackdrop) drawLayer(layer)
                drawRect(glass)
            }
        }
}

private suspend fun copyPixels(bitmap: Bitmap, request: (PixelCopy.OnPixelCopyFinishedListener) -> Unit): Boolean =
    suspendCancellableCoroutine { continuation ->
        try {
            request { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                else bitmap.recycle()
            }
        } catch (_: IllegalArgumentException) {
            if (continuation.isActive) continuation.resume(false)
        }
    }

private suspend fun captureBackdrop(window: Window): FrostedBackdrop? {
    val root = window.decorView
    if (root.width <= 0 || root.height <= 0) return null
    val origin = IntArray(2).also(root::getLocationOnScreen)
    val bitmap = createBitmap(
        (root.width / 4).coerceAtLeast(1), (root.height / 4).coerceAtLeast(1),
    )
    val handler = Handler(Looper.getMainLooper())
    if (!copyPixels(bitmap) { PixelCopy.request(window, bitmap, it, handler) }) {
        bitmap.recycle()
        return null
    }

    // Gecko renders into a separate SurfaceView; a window copy alone misses the web page.
    for (surface in visibleSurfaces(root)) {
        if (surface.width <= 0 || surface.height <= 0 || !surface.holder.surface.isValid) {
            bitmap.recycle()
            return null
        }
        val pixels = createBitmap(
            (surface.width / 4).coerceAtLeast(1), (surface.height / 4).coerceAtLeast(1),
        )
        if (!copyPixels(pixels) { PixelCopy.request(surface, pixels, it, handler) }) {
            pixels.recycle()
            bitmap.recycle()
            return null
        }
        val location = IntArray(2).also(surface::getLocationOnScreen)
        val left = (location[0] - origin[0]) / 4
        val top = (location[1] - origin[1]) / 4
        Canvas(bitmap).drawBitmap(
            pixels, null,
            Rect(left, top, left + surface.width / 4, top + surface.height / 4), null,
        )
        pixels.recycle()
    }
    return FrostedBackdrop(
        bitmap.asImageBitmap(), IntOffset(origin[0], origin[1]), IntSize(root.width, root.height),
    )
}

private fun visibleSurfaces(view: View): List<SurfaceView> = when {
    !view.isShown -> emptyList()
    view is SurfaceView -> listOf(view)
    view is ViewGroup -> (0 until view.childCount).flatMap { visibleSurfaces(view.getChildAt(it)) }
    else -> emptyList()
}
