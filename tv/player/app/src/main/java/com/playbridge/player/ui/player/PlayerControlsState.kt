package com.playbridge.player.ui.player

import androidx.compose.runtime.Immutable

enum class SettingsTab {
    VIDEO, AUDIO, SUBTITLES, SPEED, SCALING
}

@Immutable
data class UnifiedTrack(
    val id: String,
    val name: String,
    val isSelected: Boolean,
    val type: String // "video", "audio", "sub", "external_sub"
)

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
    val isPrePlayLaunching: Boolean = false,
    
    // Media Settings Panel State
    val activeSettingsTab: SettingsTab? = null,
    val audioTracks: List<UnifiedTrack> = emptyList(),
    val subtitleTracks: List<UnifiedTrack> = emptyList(),
    val videoTracks: List<UnifiedTrack> = emptyList(),
    val playbackSpeed: Float = 1.0f,
    val videoScalingMode: String = "Fit"
)
