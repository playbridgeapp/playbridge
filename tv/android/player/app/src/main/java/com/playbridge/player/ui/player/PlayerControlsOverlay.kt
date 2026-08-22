package com.playbridge.player.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.playbridge.player.player.StillWatchingState
import com.playbridge.player.player.SwitchPlayerDialog
import com.playbridge.player.ui.components.LoadingBlob
import com.playbridge.player.ui.theme.TvExpressiveMotion

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControlsOverlay(
    state: PlayerControlsState,
    mediaKind: String = "video",
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
    onResetSubtitleDelay: () -> Unit = {},
    onPreloadSubtitles: (List<String>) -> Unit = {},
    onSkipSegment: () -> Unit = {},
    onSkipButtonFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Music keeps a compact progress surface visible after the expanded controls time out.
        // Video controls continue to disappear completely for an unobstructed picture.
        val keepCompactMusicProgress = mediaKind == "audio"
        val showExpandedControls = state.isVisible && state.isFullControlsVisible

        // Main Controls Overlay
        AnimatedVisibility(
            visible = (state.isVisible || keepCompactMusicProgress) &&
                state.playbackTransitionMessage == null,
            enter = fadeIn(TvExpressiveMotion.effects()),
            exit = fadeOut(TvExpressiveMotion.effects()),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (showExpandedControls) Color.Black.copy(alpha = 0.5f) else Color.Transparent)
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
                        onResetDelay = onResetSubtitleDelay,
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
                    if (showExpandedControls) {
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
                        if (showExpandedControls) {
                            BottomMetadata(
                                engineType = state.engineType,
                                streamInfo = state.streamInfo,
                                hdrFormat = state.hdrFormat
                            )
                        }

                        if (mediaKind != "image") {
                            PlayerSeekbar(
                                position = state.currentPosition,
                                duration = state.duration,
                                bufferedPosition = state.bufferedPosition,
                                onSeek = { delta -> onSeek(delta) },
                            )
                        }

                        if (showExpandedControls) {
                            ControlActionButtons(
                                isPlaying = state.isPlaying,
                                isLooping = state.isLooping,
                                hasPlaylist = state.hasPlaylist,
                                hasMultipleStreams = false,
                                canSwitchPlayer = state.canSwitchPlayer,
                                mediaKind = mediaKind,
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

        if (state.prePlayMetadata == null &&
            (state.playbackTransitionMessage != null ||
                (state.isBuffering && !state.isVisible))
        ) {
            PlaybackTransitionOverlay(
                message = state.playbackTransitionMessage,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Subtitle Overlay (Manual Parser)
        if (state.currentSubtitleText != null && state.playbackTransitionMessage == null) {
            SubtitleOverlay(
                text = state.currentSubtitleText,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Skip Segment Button (Netflix style)
        state.activeSkipSegment
            ?.takeUnless { state.isPlaybackObscured() }
            ?.let { segment ->
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
private fun PlaybackTransitionOverlay(
    message: String?,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "loading text")
    val textAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loading text pulse",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (message != null) Color.Black else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((-16).dp),
        ) {
            LoadingBlob(modifier = Modifier.size(220.dp))
            message?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = textAlpha),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SubtitleOverlay(
    text: String,
    modifier: Modifier = Modifier,
) {
    var fontSize by remember(text) { mutableStateOf(30.sp) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.65f)
            .padding(start = 96.dp, end = 96.dp, top = 8.dp, bottom = 72.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = text,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black,
                    offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                    blurRadius = 5f,
                ),
                lineHeight = (fontSize.value * 1.25f).sp,
            ),
            softWrap = true,
            maxLines = 14,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            onTextLayout = { result ->
                if ((result.didOverflowHeight || result.didOverflowWidth) &&
                    fontSize.value > MIN_SUBTITLE_TEXT_SIZE_SP
                ) {
                    fontSize = (fontSize.value - SUBTITLE_TEXT_SIZE_STEP_SP)
                        .coerceAtLeast(MIN_SUBTITLE_TEXT_SIZE_SP)
                        .sp
                }
            },
            modifier = Modifier
                .widthIn(max = 1400.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

private const val MIN_SUBTITLE_TEXT_SIZE_SP = 18f
private const val SUBTITLE_TEXT_SIZE_STEP_SP = 2f
