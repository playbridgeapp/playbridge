package com.playbridge.shared.player

/** END_FILE data is absent in mpv-android, so only infer failures with strong evidence. */
object MpvEndFilePolicy {
    fun classify(
        fileLoaded: Boolean,
        transitioning: Boolean,
        positionMs: Long,
        durationMs: Long,
    ): PlaybackState? {
        if (transitioning) return null
        if (!fileLoaded) return PlaybackState.Error("mpv_load_failed", "mpv_load_failed")
        val remainingMs = durationMs - positionMs
        if (durationMs > 0L && remainingMs > 10_000L && remainingMs.toDouble() > durationMs * 0.02) {
            return PlaybackState.Error("mpv_playback_failed", "mpv_playback_failed")
        }
        return PlaybackState.Ended
    }
}
