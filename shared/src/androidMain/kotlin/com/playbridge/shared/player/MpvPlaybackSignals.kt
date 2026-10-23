package com.playbridge.shared.player

/** Keep native pause/cache observations even while the published state is terminal. */
internal class MpvPlaybackSignals {
    @Volatile var isPaused = false
    var cacheStalled = false

    fun propertyChanged(property: String, value: Boolean, currentState: PlaybackState): PlaybackState? {
        when (property) {
            "pause" -> isPaused = value
            "paused-for-cache" -> cacheStalled = value
            else -> return null
        }
        if (currentState is PlaybackState.Error || currentState == PlaybackState.Ended) return null
        return when {
            isPaused -> PlaybackState.Paused
            cacheStalled -> PlaybackState.Buffering
            else -> PlaybackState.Playing
        }
    }

    fun playbackRestarted(): PlaybackState {
        cacheStalled = false
        return if (isPaused) PlaybackState.Paused else PlaybackState.Playing
    }
}
