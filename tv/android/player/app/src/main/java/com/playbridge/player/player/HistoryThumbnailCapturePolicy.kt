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
