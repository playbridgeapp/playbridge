package com.playbridge.shared.player

import android.graphics.ColorMatrix

/**
 * Android-specific extensions for [VideoFilter] to generate [ColorMatrix] instances.
 */
object VideoFilterAndroid {

    /** Build a ColorMatrix for brightness adjust. 0 = normal, -1..+1 range. */
    fun brightnessMatrix(value: Float): ColorMatrix {
        val v = value * 255f
        return ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, v,
            0f, 1f, 0f, 0f, v,
            0f, 0f, 1f, 0f, v,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    /** Build a ColorMatrix for contrast adjust. 1 = normal, 0..2 range. */
    fun contrastMatrix(value: Float): ColorMatrix {
        val t = (1f - value) / 2f * 255f
        return ColorMatrix(floatArrayOf(
            value, 0f,    0f,    0f, t,
            0f,    value, 0f,    0f, t,
            0f,    0f,    value, 0f, t,
            0f,    0f,    0f,    1f, 0f
        ))
    }

    /** Build a combined matrix for a preset filter. */
    fun matrixFor(filter: VideoFilter): ColorMatrix {
        return when (filter) {
            VideoFilter.NONE -> ColorMatrix() // identity

            VideoFilter.HDR -> ColorMatrix().apply {
                postConcat(contrastMatrix(1.3f))
                postConcat(ColorMatrix().apply { setSaturation(1.2f) })
                postConcat(brightnessMatrix(0.03f))
            }

            VideoFilter.NIGHT -> ColorMatrix().apply {
                postConcat(brightnessMatrix(-0.15f))
                postConcat(contrastMatrix(0.9f))
                // Warm tint: reduce blue, slight red boost
                postConcat(ColorMatrix(floatArrayOf(
                    1.1f, 0f,   0f,   0f, 10f,
                    0f,   1.0f, 0f,   0f, 0f,
                    0f,   0f,   0.8f, 0f, -20f,
                    0f,   0f,   0f,   1f, 0f
                )))
            }

            VideoFilter.MOVIE -> ColorMatrix().apply {
                postConcat(ColorMatrix().apply { setSaturation(0.85f) })
                postConcat(contrastMatrix(1.1f))
                // Warm tone
                postConcat(ColorMatrix(floatArrayOf(
                    1.05f, 0f,   0f,   0f, 5f,
                    0f,    1.0f, 0f,   0f, 0f,
                    0f,    0f,   0.95f,0f, -5f,
                    0f,    0f,   0f,   1f, 0f
                )))
            }

            VideoFilter.CINEMA -> ColorMatrix().apply {
                postConcat(contrastMatrix(1.25f))
                postConcat(ColorMatrix().apply { setSaturation(0.9f) })
                // Cool highlights, warm shadows via slight teal-orange split
                postConcat(ColorMatrix(floatArrayOf(
                    1.08f, 0f,    0f,   0f, 0f,
                    0f,    1.02f, 0f,   0f, 0f,
                    0f,    0f,    0.92f,0f, 5f,
                    0f,    0f,    0f,   1f, 0f
                )))
            }

            VideoFilter.ACTION -> ColorMatrix().apply {
                postConcat(contrastMatrix(1.35f))
                postConcat(ColorMatrix().apply { setSaturation(1.4f) })
                postConcat(brightnessMatrix(0.02f))
            }

            VideoFilter.DEEP_BLACK -> ColorMatrix().apply {
                postConcat(contrastMatrix(1.5f))
                postConcat(brightnessMatrix(-0.08f))
                postConcat(ColorMatrix().apply { setSaturation(1.05f) })
            }

            VideoFilter.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }

            VideoFilter.VIVID -> ColorMatrix().apply {
                postConcat(ColorMatrix().apply { setSaturation(1.6f) })
                postConcat(contrastMatrix(1.15f))
                postConcat(brightnessMatrix(0.02f))
            }

            VideoFilter.CUSTOM -> ColorMatrix() // handled separately via custom params
        }
    }

    /** Build a combined matrix for custom brightness/contrast/saturation values. */
    fun customMatrix(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
        return ColorMatrix().apply {
            postConcat(brightnessMatrix(brightness))
            postConcat(contrastMatrix(contrast))
            postConcat(ColorMatrix().apply { setSaturation(saturation) })
        }
    }
}
