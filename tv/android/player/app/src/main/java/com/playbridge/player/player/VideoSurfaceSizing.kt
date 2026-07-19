package com.playbridge.player.player

import kotlin.math.roundToInt

internal data class VideoSurfaceDimensions(
    val width: Int,
    val height: Int,
)

internal fun fittedVideoSurfaceDimensions(
    containerWidth: Int,
    containerHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
): VideoSurfaceDimensions {
    if (containerWidth <= 0 || containerHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
        return VideoSurfaceDimensions(containerWidth.coerceAtLeast(0), containerHeight.coerceAtLeast(0))
    }

    val videoAspect = videoWidth.toDouble() / videoHeight.toDouble()
    val containerAspect = containerWidth.toDouble() / containerHeight.toDouble()
    return if (videoAspect >= containerAspect) {
        VideoSurfaceDimensions(
            width = containerWidth,
            height = (containerWidth / videoAspect).roundToInt().coerceAtLeast(1),
        )
    } else {
        VideoSurfaceDimensions(
            width = (containerHeight * videoAspect).roundToInt().coerceAtLeast(1),
            height = containerHeight,
        )
    }
}
