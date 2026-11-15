package com.playbridge.shared.player

/** END_FILE data is absent in mpv-android, so only infer failures with strong evidence. */
object MpvEndFilePolicy {
    private const val PREMATURE_EOF_MIN_DURATION_MS = 15_000L
    private const val PREMATURE_EOF_MIN_REMAINING_MS = 10_000L
    private const val PREMATURE_EOF_MAX_PROGRESS_RATIO = 0.92

    fun classify(
        fileLoaded: Boolean,
        transitioning: Boolean,
        positionMs: Long,
        durationMs: Long,
        hadTruncationOrNetworkError: Boolean = true,
    ): PlaybackState? {
        if (transitioning) return null
        if (!fileLoaded) return PlaybackState.Error("mpv_load_failed", "mpv_load_failed")
        val remainingMs = durationMs - positionMs
        val progressRatio = if (durationMs > 0L) positionMs.toDouble() / durationMs else 1.0
        if (hadTruncationOrNetworkError &&
            durationMs >= PREMATURE_EOF_MIN_DURATION_MS &&
            remainingMs > PREMATURE_EOF_MIN_REMAINING_MS &&
            progressRatio < PREMATURE_EOF_MAX_PROGRESS_RATIO
        ) {
            return PlaybackState.Error("mpv_playback_failed", "mpv_playback_failed")
        }
        return PlaybackState.Ended
    }
}
