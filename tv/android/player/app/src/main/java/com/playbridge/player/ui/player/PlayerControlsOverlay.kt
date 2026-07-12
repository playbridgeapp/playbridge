package com.playbridge.player.ui.player

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.playbridge.player.player.PlaylistPickerDialog
import com.playbridge.player.player.SwitchPlayerDialog
import com.playbridge.player.ui.theme.TvExpressiveMotion
import com.playbridge.player.player.StillWatchingState

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControlsOverlay(
    state: PlayerControlsState,
    stillWatchingState: StillWatchingState = StillWatchingState(),
    onContinueWatching: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onTrackSelection: () -> Unit,
    onSubtitles: () -> Unit,
    onPlaylist: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLoop: () -> Unit,
    onSwitchPlayer: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrePlayStartNow: () -> Unit = {},
    onPrePlayBack: () -> Unit = {},
    onSettingsTabSelected: (SettingsTab) -> Unit = {},
    onTrackSelected: (UnifiedTrack) -> Unit = {},
    onSpeedSelected: (Float) -> Unit = {},
    onScalingSelected: (String) -> Unit = {},
    onSettingsDismiss: () -> Unit = {},
    onOverlayDismiss: () -> Unit = {},
    onPlaylistItemPicked: (Int) -> Unit = {},
    onPlayerSwitched: (String) -> Unit = {},
    onToggleAudioBoost: () -> Unit = {},
    onAdjustSubtitleDelay: (Long) -> Unit = {},
    onPreloadSubtitles: (List<String>) -> Unit = {},
    onSkipSegment: () -> Unit = {},
    onSkipButtonFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Main Controls Overlay
        AnimatedVisibility(
            visible = state.isVisible,
            enter = fadeIn(TvExpressiveMotion.effects()),
            exit = fadeOut(TvExpressiveMotion.effects()),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (state.isFullControlsVisible) Color.Black.copy(alpha = 0.5f) else Color.Transparent)
            ) {
                // Settings Panel (Slides from right)
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.SETTINGS,
                    enter = slideInHorizontally(TvExpressiveMotion.spatial()) { it } + fadeIn(TvExpressiveMotion.effects()),
                    exit = slideOutHorizontally(TvExpressiveMotion.spatial()) { it } + fadeOut(TvExpressiveMotion.effects()),
                    modifier = Modifier.fillMaxSize()
                ) {
                    MediaSettingsPanel(
                        state = state,
                        onTabSelected = onSettingsTabSelected,
                        onTrackSelected = onTrackSelected,
                        onSpeedSelected = onSpeedSelected,
                        onScalingSelected = onScalingSelected,
                        onToggleAudioBoost = onToggleAudioBoost,
                        onDismiss = onSettingsDismiss
                    )
                }

                // Subtitle Overlay (Language → Options → Sync)
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.SUBTITLES,
                    enter = fadeIn(TvExpressiveMotion.effects()),
                    exit = fadeOut(TvExpressiveMotion.effects()),
                    modifier = Modifier.fillMaxSize()
                ) {
                    SubtitleSelectionOverlay(
                        subtitleTracks = state.subtitleTracks,
                        subtitleDelayMs = state.subtitleDelayMs,
                        previewPositionMs = state.currentPosition,
                        cuesVersion = state.subtitleCuesVersion,
                        onPreloadLanguage = onPreloadSubtitles,
                        onTrackSelected = onTrackSelected,
                        onAdjustDelay = onAdjustSubtitleDelay,
                        onDismiss = onOverlayDismiss,
                        activeMetadata = state.activeMetadata,
                    )
                }

                // Playlist Picker Overlay
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.PLAYLIST_PICKER,
                    enter = slideInHorizontally(TvExpressiveMotion.spatial()) { it } + fadeIn(TvExpressiveMotion.effects()),
                    exit = slideOutHorizontally(TvExpressiveMotion.spatial()) { it } + fadeOut(TvExpressiveMotion.effects()),
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
                    enter = fadeIn(TvExpressiveMotion.effects()),
                    exit = fadeOut(TvExpressiveMotion.effects()),
                    modifier = Modifier.fillMaxSize()
                ) {
                    SwitchPlayerDialog(
                        currentPlayer = state.engineType.lowercase(),
                        onPlayerSelected = onPlayerSwitched,
                        onDismiss = onOverlayDismiss
                    )
                }

                if (state.activeOverlay == ActiveOverlay.NONE) {
                    if (state.isFullControlsVisible) {
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
                    }

                    // Bottom Controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (state.isFullControlsVisible) {
                            BottomMetadata(
                                engineType = state.engineType,
                                streamInfo = state.streamInfo,
                                hdrFormat = state.hdrFormat
                            )
                        }

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
                                hasMultipleStreams = false,
                                onTogglePlay = onTogglePlay,
                                onTrackSelection = onTrackSelection,
                                onSubtitles = onSubtitles,
                                onPlaylist = onPlaylist,
                                onStreams = {},
                                onPrev = onPrev,
                                onNext = onNext,
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

        // Subtitle Overlay (Manual Parser)
        if (state.currentSubtitleText != null) {
            SubtitleOverlay(
                text = state.currentSubtitleText,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Skip Segment Button (Netflix style)
        state.activeSkipSegment?.let { segment ->
            val focusRequester = remember { FocusRequester() }
            
            // Request focus when the button becomes visible
            LaunchedEffect(segment) {
                focusRequester.requestFocus()
            }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 48.dp, bottom = 120.dp), // floats above seekbar / controls
                contentAlignment = Alignment.BottomEnd
            ) {
                Surface(
                    onClick = { onSkipSegment() },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            onSkipButtonFocusChanged(focusState.isFocused)
                        }
                        .width(140.dp)
                        .height(40.dp),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Black.copy(alpha = 0.55f),
                        focusedContainerColor = Color.Black.copy(alpha = 0.8f)
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                        ),
                        focusedBorder = Border(
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White)
                        )
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Skip ${segment.type.replaceFirstChar { it.uppercase() }}",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        )
                    }
                }
            }
        }

        // PrePlay Overlay — TOP layer, deliberately last: it must cover the
        // buffering spinner and the subtitle overlay (which otherwise bleed
        // through while the player pre-buffers a resume position underneath).
        state.prePlayMetadata?.let { metadata ->
            com.playbridge.player.preplay.PrePlayScreen(
                metadata = metadata,
                isLaunching = state.isPrePlayLaunching,
                launchCountdown = state.prePlayCountdown,
                onStartNow = onPrePlayStartNow,
                onBack = onPrePlayBack
            )
        }

        if (stillWatchingState.isPrompting) {
            StillWatchingDialog(
                title = state.title,
                secondsRemaining = stillWatchingState.secondsRemaining,
                onContinue = onContinueWatching,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StillWatchingDialog(
    title: String,
    secondsRemaining: Int,
    onContinue: () -> Unit,
) {
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { continueFocus.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(560.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Are you still watching?", color = MaterialTheme.colorScheme.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (title.isNotBlank()) Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
            Text(
                "Playback will stop in ${secondsRemaining.coerceAtLeast(0) / 60}:${(secondsRemaining.coerceAtLeast(0) % 60).toString().padStart(2, '0')}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                onClick = onContinue,
                modifier = Modifier.focusRequester(continueFocus),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            ) { Text("Continue watching", Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) }
        }
    }
}

@Composable
private fun SubtitleOverlay(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 72.dp) // Lifted slightly higher for better multi-line clearance
            .padding(horizontal = 64.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black.copy(alpha = 0.9f),
                    offset = androidx.compose.ui.geometry.Offset(3f, 3f),
                    blurRadius = 8f
                ),
                lineHeight = 34.sp // Explicit line height for 28sp text
            ),
            softWrap = true,
            overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
