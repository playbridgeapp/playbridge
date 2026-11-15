package com.playbridge.player.player

import com.playbridge.player.data.HistoryThumbnailMode

internal const val SMART_THUMBNAIL_DELAY_MS = 15_000L
internal const val LIVE_THUMBNAIL_INTERVAL_MS = 120_000L
internal const val EXIT_THUMBNAIL_MIN_PLAYBACK_MS = 5_000L
internal const val EXIT_THUMBNAIL_MAX_AGE_MS = 30_000L

internal fun shouldCaptureHistoryThumbnail(
    mode: HistoryThumbnailMode,
    hasThumbnail: Boolean,
    playbackElapsedMs: Long,
    lastCapturePlaybackMs: Long?,
    exitFallback: Boolean = false,
): Boolean {
    if (mode == HistoryThumbnailMode.ARTWORK_ONLY) return false

    if (exitFallback) {
        if (playbackElapsedMs < EXIT_THUMBNAIL_MIN_PLAYBACK_MS) return false
        return when (mode) {
            HistoryThumbnailMode.SMART -> !hasThumbnail
            HistoryThumbnailMode.LIVE -> lastCapturePlaybackMs == null ||
                playbackElapsedMs - lastCapturePlaybackMs >= EXIT_THUMBNAIL_MAX_AGE_MS
            HistoryThumbnailMode.ARTWORK_ONLY -> false
        }
    }

    if (playbackElapsedMs < SMART_THUMBNAIL_DELAY_MS) return false
    return when (mode) {
        HistoryThumbnailMode.SMART -> !hasThumbnail
        HistoryThumbnailMode.LIVE -> lastCapturePlaybackMs == null ||
            playbackElapsedMs - lastCapturePlaybackMs >= LIVE_THUMBNAIL_INTERVAL_MS
        HistoryThumbnailMode.ARTWORK_ONLY -> false
    }
}

private const val BLANK_FRAME_LUMA = 32
private const val BLANK_FRAME_MIN_LIT_PER_THOUSAND = 5

/**
 * True when a captured frame (ARGB pixels) is black or nearly so: the video has not drawn its
 * first picture yet, or sits on a fade. Such a frame is not kept as a preview; a later one is.
 */
internal fun isBlankFrame(pixels: IntArray): Boolean {
    if (pixels.isEmpty()) return true
    var lit = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        if ((r * 299 + g * 587 + b * 114) / 1000 > BLANK_FRAME_LUMA) lit++
    }
    return lit.toLong() * 1000 < pixels.size.toLong() * BLANK_FRAME_MIN_LIT_PER_THOUSAND
}
