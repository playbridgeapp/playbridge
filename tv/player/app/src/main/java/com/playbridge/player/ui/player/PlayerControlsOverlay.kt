package com.playbridge.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
