package com.playbridge.receiver.player

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
        
        // Hook up the effect exactly once when setting the player.
        // ExoPlayer will build the VideoFrameProcessor with this persistent effect instance.
        player?.setVideoEffects(listOf(colorMatrixEffect))

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
        colorMatrixEffect.setMatrix(matrix)
    }

    /** Re-apply the current filter (call after player recreation). */
    fun reapplyFilter() {
        if (currentFilter == VideoFilter.NONE) return
        if (currentFilter == VideoFilter.CUSTOM) {
            applyCustom(customBrightness, customContrast, customSaturation)
        } else {
            applyFilter(currentFilter)
        }
    }

    fun clearFilter() {
        currentFilter = VideoFilter.NONE
        val identityMatrix = android.graphics.ColorMatrix()
        applyMatrixToPlayer(identityMatrix)
    }
}
