package com.playbridge.sender.cast.remote.ui

import android.util.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.palette.graphics.Palette
import com.playbridge.sender.cast.loadMediaThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Colours for the Up next sheet taken from the current item's artwork, like a music player's
 * queue: a dark gradient behind the list and a light card for what's playing. The hue comes
 * from the art; lightness is fixed so any artwork gives readable text.
 */
@Immutable
data class QueueTint(
    val top: Color,
    val middle: Color,
    val bottom: Color,
    val cardFill: Color,
    val cardBase: Color,
    val onCard: Color,
)

internal fun queueTint(seed: Color): QueueTint {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val hue = hsl[0]
    val sat = hsl[1]
    fun tone(saturation: Float, lightness: Float) =
        Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation.coerceIn(0f, 1f), lightness)))
    return QueueTint(
        top = tone(sat * 0.5f, 0.06f),
        middle = tone(sat * 0.6f, 0.15f),
        bottom = tone(sat * 0.5f, 0.05f),
        cardFill = tone(sat.coerceAtLeast(0.45f) * 0.85f, 0.82f),
        cardBase = tone(sat * 0.35f, 0.62f),
        onCard = tone(sat * 0.5f, 0.12f),
    )
}

/** The tint for [uri]'s artwork, easing from the old one when the item changes; [fallback] when there's no art. */
@Composable
internal fun rememberQueueTint(uri: String?, fallback: Color): QueueTint {
    val context = LocalContext.current
    val seed by produceState(initialValue = fallback, uri) {
        value = uri?.let { artworkColor(context, it) } ?: fallback
    }
    val animated by animateColorAsState(seed, tween(450), label = "queueTint")
    return queueTint(animated)
}

private suspend fun artworkColor(context: android.content.Context, uri: String): Color? {
    val bitmap = loadMediaThumbnail(context, uri.toUri(), Size(320, 320)) ?: return null
    return withContext(Dispatchers.Default) {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch
        swatch?.rgb?.let(::Color)
    }
}

/** The Remote's colours over a phone list's artwork gradient: light text, opaque tinted surfaces. */
internal fun ColorScheme.tinted(tint: QueueTint): ColorScheme {
    fun raised(amount: Float) = lerp(tint.middle, Color.White, amount)
    return copy(
        primary = tint.cardFill, onPrimary = tint.onCard,
        primaryContainer = tint.cardFill, onPrimaryContainer = tint.onCard,
        secondaryContainer = raised(0.16f), onSecondaryContainer = Color.White,
        surface = tint.top, onSurface = Color.White,
        surfaceVariant = raised(0.10f), onSurfaceVariant = Color.White.copy(alpha = 0.68f),
        surfaceContainerLowest = lerp(tint.middle, Color.Black, 0.3f),
        surfaceContainerLow = raised(0.04f), surfaceContainer = raised(0.07f),
        surfaceContainerHigh = raised(0.10f), surfaceContainerHighest = raised(0.14f),
        outline = Color.White.copy(alpha = 0.4f), outlineVariant = Color.White.copy(alpha = 0.18f),
    )
}

internal fun QueueTint.gradient() = Brush.verticalGradient(listOf(top, middle, bottom))
