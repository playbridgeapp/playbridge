package com.playbridge.player.player

/** Monotonic-clock policy, sampled even when the renderer has stopped sending changes. */
internal class PlaybackStallDetector {
    private var sinceMs: Long? = null
    private var lastPositionMs = 0L
    private var lastBufferedAheadMs = 0L
    private var reported = false

    fun reset() {
        sinceMs = null
        reported = false
    }

    fun sample(nowMs: Long, buffering: Boolean, playbackRequested: Boolean, hasFirstFrame: Boolean,
               positionMs: Long, bufferedAheadMs: Long, seeking: Boolean = false): Boolean {
        val progress = positionMs != lastPositionMs || bufferedAheadMs > lastBufferedAheadMs
        lastPositionMs = positionMs
        lastBufferedAheadMs = bufferedAheadMs
        if (!buffering || !playbackRequested || seeking || progress) reset()
        if (!buffering || !playbackRequested || seeking) return false
        val since = sinceMs ?: nowMs.also { sinceMs = it }
        val timeout = if (hasFirstFrame) MID_PLAY_TIMEOUT_MS else STARTUP_TIMEOUT_MS
        if (!reported && nowMs - since >= timeout) {
            reported = true
            return true
        }
        return false
    }

    companion object {
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val MID_PLAY_TIMEOUT_MS = 45_000L
        const val MESSAGE = "The video stopped loading"
    }
}
