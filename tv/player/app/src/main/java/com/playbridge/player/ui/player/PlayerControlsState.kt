package com.playbridge.player.ui.player

import androidx.compose.runtime.Immutable

enum class SettingsTab {
    VIDEO, AUDIO, SUBTITLES, SPEED, SCALING
}

enum class ActiveOverlay {
    NONE, SETTINGS, VIDEO_FILTER, PLAYLIST_PICKER, SWITCH_PLAYER
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
    val engineType: String = "",
    val prePlayMetadata: com.playbridge.shared.protocol.VisualMetadata? = null,
    val prePlayCountdown: Int = 0,
    val isPrePlayLaunching: Boolean = false,
    
    // Unified Overlay State
    val activeOverlay: ActiveOverlay = ActiveOverlay.NONE,
    
    // Media Settings Panel State (used when activeOverlay == SETTINGS)
    val activeSettingsTab: SettingsTab = SettingsTab.AUDIO,
    val audioTracks: List<UnifiedTrack> = emptyList(),
    val subtitleTracks: List<UnifiedTrack> = emptyList(),
    val videoTracks: List<UnifiedTrack> = emptyList(),
    val playbackSpeed: Float = 1.0f,
    val videoScalingMode: String = "Fit",
    
    // Playlist Data
    val playlistItems: List<com.playbridge.shared.protocol.PlayPayload> = emptyList(),
    val playlistIndex: Int = 0,

    // Video Filter Data
    val currentFilter: com.playbridge.shared.player.VideoFilter = com.playbridge.shared.player.VideoFilter.NONE,
    val customBrightness: Float = 0f,
    val customContrast: Float = 1f,
    val customSaturation: Float = 1f,
    val previewFrame: android.graphics.Bitmap? = null
)
