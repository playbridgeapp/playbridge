package com.playbridge.player.ui.player

import androidx.compose.runtime.Immutable

/**
 * Represents the state of the video player controls overlay.
 */
@Immutable
data class PlayerControlsState(
    val isVisible: Boolean = false,
    val isFullControlsVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPosition: Long = 0,
    val title: String = "",
    val subtitle: String? = null,
    val streamInfo: String? = null,
    val hdrFormat: String? = null,
    val isLooping: Boolean = false,
    val hasPlaylist: Boolean = false,
    val hasMultipleStreams: Boolean = false,
    val engineType: String = "",
    val prePlayPayload: com.playbridge.shared.protocol.ContentPlayPayload? = null,
    val prePlayCountdown: Int = 0,
    val isPrePlayLaunching: Boolean = false
)
