package com.playbridge.player.player

import androidx.media3.ui.PlayerView

/**
 * Applies [VideoFilter] color matrices to an [androidx.media3.exoplayer.ExoPlayer] using Media3 GlEffects.
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

    /**
     * Tracks whether a GlEffect list is currently attached to the player.
     * We use this to avoid redundant expensive setVideoEffects() calls.
     */
    private var isEffectApplied = false

    fun setPlayer(player: androidx.media3.exoplayer.ExoPlayer?) {
        this.exoPlayer = player
        this.isEffectApplied = false // Reset for new player instance
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
        if (!isEffectApplied) {
            exoPlayer?.setVideoEffects(listOf(colorMatrixEffect))
            isEffectApplied = true
        }
        colorMatrixEffect.setMatrix(matrix)
    }

    /** Re-apply the current filter (call after player recreation). */
    fun reapplyFilter() {
        if (currentFilter == VideoFilter.NONE) {
            clearFilter()
            return
        }
        if (currentFilter == VideoFilter.CUSTOM) {
            val matrix = VideoFilter.customMatrix(customBrightness, customContrast, customSaturation)
            applyMatrixToPlayer(matrix)
        } else {
            val matrix = VideoFilter.matrixFor(currentFilter)
            applyMatrixToPlayer(matrix)
        }
    }

    fun clearFilter() {
        currentFilter = VideoFilter.NONE
        if (isEffectApplied) {
            // By setting an empty list, ExoPlayer bypasses the GL shader and decodes directly to the surface,
            // which saves significant GPU memory and prevents OOM crashes.
            exoPlayer?.setVideoEffects(emptyList())
            isEffectApplied = false
        }
    }
}
