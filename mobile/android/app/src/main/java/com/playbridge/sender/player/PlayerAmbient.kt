package com.playbridge.sender.player

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Ambient mode's colour maths, on tiny ARGB frames (48 pixels wide). The video is read from the
 * TextureView both decoders draw into, so ExoPlayer and mpv share one path; the full-size picture
 * is never copied.
 */
internal object AmbientFrame {
    const val WIDTH = 48
    const val INTERVAL_MS = 1_000L
    /** How far the glow moves toward each new frame. */
    const val WEIGHT = 0.35f
    /** Below this average brightness a frame counts as black. */
    const val DARK_LUMA = 0.03

    fun heightFor(aspect: Float): Int = (WIDTH / aspect.coerceIn(0.2f, 5f)).roundToInt().coerceIn(1, WIDTH * 5)

    /**
     * Moves the glow part of the way toward a new frame. Fading straight between two very
     * different frames dims the screen halfway (red and blue mix to a dark purple), so the glow
     * follows the video in small steps. Without a glow of the same size, the frame is used as is.
     */
    fun blend(glow: IntArray?, frame: IntArray, weight: Float = WEIGHT): IntArray {
        if (glow == null || glow.size != frame.size) return frame.copyOf()
        val w = weight.coerceIn(0f, 1f)
        return IntArray(frame.size) { i ->
            val a = glow[i]
            val b = frame[i]
            fun mix(shift: Int): Int {
                val from = (a shr shift) and 0xFF
                val to = (b shr shift) and 0xFF
                return (from + (to - from) * w).roundToInt().coerceIn(0, 255)
            }
            (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
    }

    /** Average brightness, 0..1. */
    fun meanLuma(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0
        var sum = 0.0
        for (p in pixels) {
            sum += 0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)
        }
        return sum / pixels.size / 255.0
    }

    /**
     * A frame grab can come back black while the picture isn't (a seek, a surface change). A
     * single near-black frame is ignored while the glow has colour; a second one in a row is a
     * real dark scene.
     */
    fun accepts(frameLuma: Double, glowLuma: Double?, previousWasDark: Boolean): Boolean {
        if (frameLuma >= DARK_LUMA || glowLuma == null || glowLuma < DARK_LUMA) return true
        return previousWasDark
    }

    /**
     * A two-pass box blur on the tiny frame, so the stretched glow has no blocks even where
     * Compose can't blur (Android 11 and older).
     */
    fun soften(pixels: IntArray, width: Int, height: Int, radius: Int = 2): IntArray {
        if (width <= 0 || height <= 0 || pixels.size != width * height || radius <= 0) return pixels.copyOf()
        var current = pixels
        repeat(2) {
            current = boxPass(current, width, height, radius, horizontal = true)
            current = boxPass(current, width, height, radius, horizontal = false)
        }
        return current
    }

    private fun boxPass(src: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (d in -radius..radius) {
                val sx = if (horizontal) (x + d).coerceIn(0, width - 1) else x
                val sy = if (horizontal) y else (y + d).coerceIn(0, height - 1)
                val p = src[sy * width + sx]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
            out[y * width + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }
}

/**
 * Grabs a tiny frame once a second while playing (paused keeps the last glow) and returns the
 * glow to draw. Only while the player is on screen; a new player starts a new glow.
 */
@Composable
internal fun rememberAmbientGlow(state: PlayerScreenState, active: Boolean): ImageBitmap? {
    var glow by remember(state.player) { mutableStateOf<IntArray?>(null) }
    var shown by remember(state.player) { mutableStateOf<ImageBitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.player, active, state.layoutAspect) {
        if (!active) return@LaunchedEffect
        val height = AmbientFrame.heightFor(state.layoutAspect)
        val grab = Bitmap.createBitmap(AmbientFrame.WIDTH, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(AmbientFrame.WIDTH * height)
        var previousWasDark = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val texture = state.textureView
                if ((state.isPlaying || glow == null) && state.hasRenderedFrame && texture != null && grabFrame(texture, grab)) {
                    grab.getPixels(pixels, 0, AmbientFrame.WIDTH, 0, 0, AmbientFrame.WIDTH, height)
                    val frameLuma = AmbientFrame.meanLuma(pixels)
                    val accepted = AmbientFrame.accepts(frameLuma, glow?.let(AmbientFrame::meanLuma), previousWasDark)
                    previousWasDark = frameLuma < AmbientFrame.DARK_LUMA
                    if (accepted) {
                        val next = AmbientFrame.blend(glow, pixels)
                        glow = next
                        val soft = AmbientFrame.soften(next, AmbientFrame.WIDTH, height)
                        shown = Bitmap.createBitmap(soft, AmbientFrame.WIDTH, height, Bitmap.Config.ARGB_8888).asImageBitmap()
                    }
                }
                delay(AmbientFrame.INTERVAL_MS)
            }
        }
    }
    return if (active) shown else null
}

/** The TextureView's current frame, scaled into [into]; false when it has none yet. */
private fun grabFrame(texture: TextureView, into: Bitmap): Boolean =
    texture.isAvailable && runCatching { texture.getBitmap(into) }.getOrNull() != null

/**
 * The glow, stretched and blurred. Each new glow fades in over the previous one, which stays
 * fully drawn underneath, so the light never dips between frames.
 */
@Composable
internal fun AmbientGlow(image: ImageBitmap, modifier: Modifier = Modifier, alpha: Float = 0.65f) {
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    var over by remember { mutableStateOf(image) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(image) {
        if (image === over) return@LaunchedEffect
        under = over
        over = image
        fade.snapTo(0f)
        fade.animateTo(1f, tween(900))
        under = null
    }
    Canvas(modifier.blur(40.dp).graphicsLayer { this.alpha = alpha }) {
        val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
        under?.let { drawImage(it, dstSize = target, filterQuality = FilterQuality.Low) }
        drawImage(over, dstSize = target, alpha = fade.value, filterQuality = FilterQuality.Low)
    }
}

/**
 * Portrait layout: the glow sits behind the video box, wider than the screen and reaching down
 * into the panel, where it fades out.
 */
@Composable
internal fun PortraitAmbientGlow(image: ImageBitmap, videoTop: Dp, videoHeight: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(videoTop + videoHeight * 2.2f)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        AmbientGlow(image, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f })
    }
}
