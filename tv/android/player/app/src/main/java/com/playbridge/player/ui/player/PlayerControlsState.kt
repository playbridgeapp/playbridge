package com.playbridge.player.ui.player

import androidx.compose.runtime.Immutable

enum class SettingsTab {
    VIDEO, AUDIO, SPEED, SCALING
}

enum class ActiveOverlay {
    NONE, SETTINGS, SUBTITLES, PLAYLIST_PICKER, CHAPTERS, SWITCH_PLAYER
}

enum class ControlActionFocusTarget {
    PLAY_PAUSE,
    PLAYLIST,
    CHAPTERS,
    SUBTITLES,
    SWITCH_PLAYER,
    SETTINGS,
}

@Immutable
data class UnifiedTrack(
    val id: String,
    val name: String,
    val isSelected: Boolean,
    val type: String, // "video", "audio", "sub", "external_sub"
    val secondaryText: String? = null,
    val isEnabled: Boolean = true,
)

internal fun List<UnifiedTrack>.hasSelectableVideoQualities(): Boolean =
    count { it.type == "video" && it.id != "auto" } > 1

@Immutable
data class PlaybackCapabilities(
    val isLive: Boolean = false,
    val isSeekable: Boolean = true,
    val speedAvailable: Boolean = true,
    val scalingAvailable: Boolean = true,
    val audioBoostAvailable: Boolean = true,
    val qualityAvailable: Boolean = false,
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
    val playbackTransitionMessage: String? = null,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPosition: Long = 0,
    val title: String = "",
    val subtitle: String? = null,
    val streamInfo: String? = null,
    val hdrFormat: String? = null,
    val isLooping: Boolean = false,
    val hasPlaylist: Boolean = false,
    val canSwitchPlayer: Boolean = true,
    val engineType: String = "",
    val prePlayMetadata: playbridge.VisualMetadata? = null,
    val activeMetadata: playbridge.VisualMetadata? = null,
    val prePlayCountdown: Int = 0,
    val isPrePlayLaunching: Boolean = false,
    
    // Unified Overlay State
    val activeOverlay: ActiveOverlay = ActiveOverlay.NONE,
    val lastClosedOverlay: ActiveOverlay = ActiveOverlay.NONE,
    
    // Media Settings Panel State (used when activeOverlay == SETTINGS)
    val activeSettingsTab: SettingsTab = SettingsTab.AUDIO,
    val audioTracks: List<UnifiedTrack> = emptyList(),
    val subtitleTracks: List<UnifiedTrack> = emptyList(),
    val videoTracks: List<UnifiedTrack> = emptyList(),
    val playbackSpeed: Float = 1.0f,
    val videoScalingMode: String = "Fit",
    val videoQualityMaxHeight: Int = 0,
    val currentVideoHeight: Int = 0,
    val capabilities: PlaybackCapabilities = PlaybackCapabilities(),
    
    // Playlist Data
    val playlistItems: List<playbridge.PlayPayload> = emptyList(),
    val playlistIndex: Int = 0,

    // High Performance Features
    val subtitleDelayMs: Long = 0,
    val isAudioBoostEnabled: Boolean = false,
    val currentSubtitleText: String? = null,
    // Bumped whenever a subtitle preview finishes loading, so the overlay re-reads the
    // SubtitleCueLoader cache. The cues themselves live in the loader, not in state.
    val subtitleCuesVersion: Int = 0,
    val skipSegments: List<com.playbridge.player.player.SkipSegment> = emptyList(),
    val activeSkipSegment: com.playbridge.player.player.SkipSegment? = null,
    val isSkipButtonFocused: Boolean = false,
    val chapters: List<com.playbridge.shared.player.PlayerChapter> = emptyList(),
    val chapterOsd: String? = null,
)

internal fun PlayerControlsState.isPlaybackObscured(): Boolean =
    playbackTransitionMessage != null || isBuffering || prePlayMetadata != null

internal fun PlayerControlsState.hasSelectableChapters(mediaKind: String = "video"): Boolean =
    mediaKind != "image" &&
        !capabilities.isLive &&
        capabilities.isSeekable &&
        chapters.size >= 2

internal fun PlayerControlsState.controlActionFocusTarget(
    mediaKind: String = "video",
): ControlActionFocusTarget = when (lastClosedOverlay) {
    ActiveOverlay.PLAYLIST_PICKER ->
        if (hasPlaylist) ControlActionFocusTarget.PLAYLIST else ControlActionFocusTarget.PLAY_PAUSE
    ActiveOverlay.CHAPTERS ->
        if (hasSelectableChapters(mediaKind)) ControlActionFocusTarget.CHAPTERS else ControlActionFocusTarget.PLAY_PAUSE
    ActiveOverlay.SUBTITLES ->
        if (mediaKind == "video") ControlActionFocusTarget.SUBTITLES else ControlActionFocusTarget.PLAY_PAUSE
    ActiveOverlay.SWITCH_PLAYER ->
        if (canSwitchPlayer && mediaKind != "image") ControlActionFocusTarget.SWITCH_PLAYER else ControlActionFocusTarget.PLAY_PAUSE
    ActiveOverlay.SETTINGS ->
        if (mediaKind != "image") ControlActionFocusTarget.SETTINGS else ControlActionFocusTarget.PLAY_PAUSE
    ActiveOverlay.NONE -> ControlActionFocusTarget.PLAY_PAUSE
}

/**
 * Controls may auto-hide only when visible, no overlay/panel is open, and either the
 * lightweight seek strip is up or playback is actively running.
 */
internal fun PlayerControlsState.shouldAutoHideControls(): Boolean =
    isVisible &&
        activeOverlay == ActiveOverlay.NONE &&
        (!isFullControlsVisible || isPlaying)


