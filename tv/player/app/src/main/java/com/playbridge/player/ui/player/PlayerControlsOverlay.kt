package com.playbridge.player.ui.player

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.playbridge.player.player.StreamSelectionDialog
import com.playbridge.player.player.VideoFilterDialog
import com.playbridge.player.player.PlaylistPickerDialog
import com.playbridge.player.player.SwitchPlayerDialog

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControlsOverlay(
    state: PlayerControlsState,
    onTogglePlay: () -> Unit,
    onTrackSelection: () -> Unit,
    onPlaylist: () -> Unit,
    onStreams: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFilter: () -> Unit,
    onLoop: () -> Unit,
    onSwitchPlayer: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrePlayStreamSelected: (com.playbridge.shared.stremio.ScoredStremioStream) -> Unit = {},
    onPrePlayBack: () -> Unit = {},
    onSettingsTabSelected: (SettingsTab) -> Unit = {},
    onTrackSelected: (UnifiedTrack) -> Unit = {},
    onSpeedSelected: (Float) -> Unit = {},
    onScalingSelected: (String) -> Unit = {},
    onSettingsDismiss: () -> Unit = {},
    onOverlayDismiss: () -> Unit = {},
    onStreamSelected: (com.playbridge.shared.stremio.ScoredStremioStream) -> Unit = {},
    onRefreshStreams: () -> Unit = {},
    onFilterSelected: (com.playbridge.shared.player.VideoFilter) -> Unit = {},
    onCustomFilterChanged: (brightness: Float, contrast: Float, saturation: Float) -> Unit = {_,_,_ ->},
    onPlaylistItemPicked: (Int) -> Unit = {},
    onPlayerSwitched: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // PrePlay Overlay (Bottom layer)
        state.prePlayPayload?.let { payload ->
            com.playbridge.player.preplay.PrePlayScreen(
                payload = payload,
                isLaunching = state.isPrePlayLaunching,
                launchCountdown = state.prePlayCountdown,
                onStreamSelected = onPrePlayStreamSelected,
                onBack = onPrePlayBack
            )
        }

        // Main Controls Overlay (Top layer)
        AnimatedVisibility(
            visible = state.isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)) // Dim background
            ) {
                // Settings Panel (Slides from right)
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.SETTINGS,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    MediaSettingsPanel(
                        state = state,
                        onTabSelected = onSettingsTabSelected,
                        onTrackSelected = onTrackSelected,
                        onSpeedSelected = onSpeedSelected,
                        onScalingSelected = onScalingSelected,
                        onDismiss = onSettingsDismiss
                    )
                }

                // Stream Selection Overlay
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.STREAM_PICKER,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    StreamSelectionDialog(
                        streams = state.availableStreams,
                        currentUrl = state.currentStreamUrl,
                        isLoading = state.isLoadingStreams,
                        preferredQuality = state.prePlayPayload?.defaultVideoQuality,
                        preferredAddonName = state.prePlayPayload?.preferredAddonName,
                        preferredSourceTypeKeys = state.prePlayPayload?.preferredSourceTypes,
                        onStreamSelected = onStreamSelected,
                        onRefresh = onRefreshStreams,
                        onDismiss = onOverlayDismiss
                    )
                }

                // Video Filter Overlay
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.VIDEO_FILTER,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    VideoFilterDialog(
                        currentFilter = state.currentFilter,
                        customBrightness = state.customBrightness,
                        customContrast = state.customContrast,
                        customSaturation = state.customSaturation,
                        previewFrame = state.previewFrame,
                        onFilterSelected = onFilterSelected,
                        onCustomChanged = onCustomFilterChanged,
                        onDismiss = onOverlayDismiss
                    )
                }

                // Playlist Picker Overlay
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.PLAYLIST_PICKER,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    PlaylistPickerDialog(
                        items = state.playlistItems,
                        currentIndex = state.playlistIndex,
                        onItemSelected = onPlaylistItemPicked,
                        onDismiss = onOverlayDismiss
                    )
                }

                // Switch Player Overlay
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.SWITCH_PLAYER,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    SwitchPlayerDialog(
                        currentPlayer = state.engineType.lowercase(),
                        onPlayerSelected = onPlayerSwitched,
                        onDismiss = onOverlayDismiss
                    )
                }

                if (state.activeOverlay == ActiveOverlay.NONE) {
                    // Top shadow
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.4f)
                            .align(Alignment.TopCenter)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                                )
                            )
                    )

                    // Bottom shadow
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.5f)
                            .align(Alignment.BottomCenter)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                )
                            )
                    )

                    // Top Metadata
                    TopMetadata(
                        title = state.title,
                        subtitle = state.subtitle,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    // Bottom Controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        BottomMetadata(
                            engineType = state.engineType,
                            streamInfo = state.streamInfo,
                            hdrFormat = state.hdrFormat
                        )

                        PlayerSeekbar(
                            position = state.currentPosition,
                            duration = state.duration,
                            bufferedPosition = state.bufferedPosition,
                            onSeek = { delta -> onSeek(delta) }
                        )

                        if (state.isFullControlsVisible) {
                            ControlActionButtons(
                                isPlaying = state.isPlaying,
                                isLooping = state.isLooping,
                                hasPlaylist = state.hasPlaylist,
                                hasMultipleStreams = state.hasMultipleStreams,
                                onTogglePlay = onTogglePlay,
                                onTrackSelection = onTrackSelection,
                                onPlaylist = onPlaylist,
                                onStreams = onStreams,
                                onPrev = onPrev,
                                onNext = onNext,
                                onFilter = onFilter,
                                onLoop = onLoop,
                                onSwitchPlayer = onSwitchPlayer,
                                isVisible = state.isVisible
                            )
                        }
                    }
                }
            }
        }

        // Buffering Spinner (Only show if not already showing full controls)
        if (state.isBuffering && !state.isVisible) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )
        }
    }
}
