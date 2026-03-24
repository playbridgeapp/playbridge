package com.playbridge.player.player

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.media3.ui.PlayerView

/**
 * Applies [VideoFilter] color matrices to a [PlayerView] using hardware layer paint.
 * This is GPU-accelerated and adds zero overhead to the decode pipeline.
 */
class VideoFilterManager(private val playerView: PlayerView) {

    var currentFilter: VideoFilter = VideoFilter.NONE
        private set

    // Custom slider values (0-centered for brightness, 1-centered for contrast/saturation)
    var customBrightness: Float = 0f
        private set
    var customContrast: Float = 1f
        private set
    var customSaturation: Float = 1f
        private set

    private var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    val colorMatrixEffect = ColorMatrixEffect()

    fun setPlayer(player: androidx.media3.exoplayer.ExoPlayer?) {
        this.exoPlayer = player
        reapplyFilter()
    }

    fun applyFilter(filter: VideoFilter) {
        currentFilter = filter
        if (filter == VideoFilter.NONE) {
            clearFilter()
            return
        }
        if (filter == VideoFilter.CUSTOM) {
            applyCustom(customBrightness, customContrast, customSaturation)
            return
        }
        val matrix = VideoFilter.matrixFor(filter)
        applyMatrixToPlayer(matrix)
    }

    fun applyCustom(brightness: Float, contrast: Float, saturation: Float) {
        customBrightness = brightness
        customContrast = contrast
        customSaturation = saturation
        currentFilter = VideoFilter.CUSTOM

        val matrix = VideoFilter.customMatrix(brightness, contrast, saturation)
        applyMatrixToPlayer(matrix)
    }

    private fun applyMatrixToPlayer(matrix: android.graphics.ColorMatrix) {
        // Only engage the ExoPlayer VideoFrameProcessor pipeline if we actually have an active filter.
        // Using the pipeline unconditionally causes `glError: out of memory` on low-end TV devices
        // because it allocates a 1080p GL texture buffer.
        exoPlayer?.setVideoEffects(listOf(colorMatrixEffect))
        colorMatrixEffect.setMatrix(matrix)
    }

    /** Re-apply the current filter (call after player recreation). */
    fun reapplyFilter() {
        if (currentFilter == VideoFilter.NONE) {
            exoPlayer?.setVideoEffects(emptyList())
            return
        }
        if (currentFilter == VideoFilter.CUSTOM) {
            applyCustom(customBrightness, customContrast, customSaturation)
        } else {
            applyFilter(currentFilter)
        }
    }

    fun clearFilter() {
        currentFilter = VideoFilter.NONE
        // By setting an empty list, ExoPlayer bypasses the GL shader and decodes directly to the surface,
        // which saves significant GPU memory and prevents OOM crashes.
        exoPlayer?.setVideoEffects(emptyList())
    }
}
