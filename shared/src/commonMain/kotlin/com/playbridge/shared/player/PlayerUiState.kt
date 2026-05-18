package com.playbridge.shared.player

import playbridge.PlayPayload

/**
 * UI state machine for the player screen.
 *
 * Both Android (Compose for TV) and Apple (SwiftUI) observe this sealed class
 * via [PlayerViewModel.ui].
 */
sealed class PlayerUiState {
    /** No media loaded. */
    data object Idle : PlayerUiState()


    /**
     * A concrete [PlayPayload] has been submitted to the engine but it has not
     * yet reached the [Playing] state (still in [PlaybackState.Buffering]).
     */
    data class Loading(
        val payload: PlayPayload,
        val isPlaylist: Boolean = false,
        val playlistIndex: Int = 0,
        val playlistSize: Int = 0,
    ) : PlayerUiState()

    /**
     * Engine is [PlaybackState.Playing], [PlaybackState.Paused] or [PlaybackState.Ready].
     */
    data class Playing(
        val payload: PlayPayload,
        val isPaused: Boolean = false,
        val isPlaylist: Boolean = false,
        val playlistIndex: Int = 0,
        val playlistSize: Int = 0,
    ) : PlayerUiState()

    /**
     * Non-recoverable playback error. The UI should show a retry / next / back option.
     */
    data class Error(
        val code: String,
        val message: String,
        val canRetry: Boolean = true,
    ) : PlayerUiState()

    /**
     * Playback ended naturally. If [nextPayload] is non-null the UI may show
     * an auto-advance countdown or a "Play next" prompt.
     */
    data class Ended(
        val nextPayload: PlayPayload? = null,
    ) : PlayerUiState()
}
