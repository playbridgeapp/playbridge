package com.playbridge.player.player

/**
 * Interface to abstract engine-specific playback controls for the unified UI.
 * This allows UnifiedControlsManager to interact with ExoPlayer, VLC, or MPV
 * without direct dependencies on their internal classes.
 */
interface PlayerEngineAdapter {
    /** Whether the player is currently in a playing state. */
    val isPlaying: Boolean

    /** Current position in milliseconds. */
    val currentPosition: Long

    /** Total duration in milliseconds. */
    val duration: Long

    /** Current buffered position in milliseconds. */
    val bufferedPosition: Long

    /** Formatted stream information string (e.g., "1080p • H.264 • stereo"). */
    val streamInfo: String?

    /** Video frame rate in fps. 0.0 means unknown. */
    val frameRate: Float
    /** HDR format like "HDR10", "HLG", "Dolby Vision". null if SDR. */
    val hdrFormat: String?

    fun setLoudnessEnhancer(enabled: Boolean)

    /** Start/resume playback. */
    fun play()

    /** Pause playback. */
    fun pause()

    /** Seek to the specified position in milliseconds. */
    fun seekTo(positionMs: Long)
}
