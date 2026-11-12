package com.playbridge.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.playbridge.player.player.ChapterPickerDialog
import com.playbridge.player.player.PlaylistPickerDialog
import com.playbridge.player.player.StillWatchingState
import com.playbridge.player.together.TvTogether
import com.playbridge.player.ui.TogetherPartyLabel
import com.playbridge.player.ui.TogetherPlayerChrome
import com.playbridge.player.ui.components.LoadingBlob
import com.playbridge.player.ui.toPartyUi
import com.playbridge.player.ui.theme.TvExpressiveMotion
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything drawn over the video: the seek strip (Left/Right), the full controls (OK, Up, Down),
 * the side panels, skip button, notices, Still Watching, the loading spinner and the error screen.
 * Which surface shows follows [PlayerControlsState]; the keys that move between them are decided
 * by [resolveKeyAction] and applied in PlayerHostActivity.
 *
 * [state] changes about once a second; exact position comes from [progress], which only the seek
 * bars read. Music draws its own progress and buttons through [MusicTransport] (see
 * MediaPresentation), so this overlay draws none of them for `mediaKind == "audio"`.
 */
@Composable
fun PlayerControlsOverlay(
    state: PlayerControlsState,
    progress: StateFlow<SeekProgress>,
    mediaKind: String = "video",
    subtitleStyle: com.playbridge.shared.player.SubtitleStyle = com.playbridge.shared.player.SubtitleStyle.DEFAULT,
    subtitleTypeface: android.graphics.Typeface? = null,
    stillWatchingState: StillWatchingState = StillWatchingState(),
    onContinueWatching: () -> Unit = {},
    onStopWatching: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onSubtitles: () -> Unit,
    onPlaylist: () -> Unit,
    onChapters: () -> Unit = {},
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLoop: () -> Unit,
    onSeek: (Long) -> Unit,
    onBookmark: () -> Unit = {},
    onPrePlayStartNow: () -> Unit = {},
    onPrePlayStartOver: () -> Unit = {},
    onPrePlayBack: () -> Unit = {},
    party: TvTogether.State.InParty? = null,
    onLeaveParty: () -> Unit = {},
    onSettingsTabSelected: (SettingsTab) -> Unit = {},
    onTrackSelected: (UnifiedTrack) -> Unit = {},
    onSpeedSelected: (Float) -> Unit = {},
    onScalingSelected: (String) -> Unit = {},
    onSettingsDismiss: () -> Unit = {},
    onOverlayDismiss: () -> Unit = {},
    onPlaylistItemPicked: (Int) -> Unit = {},
    onChapterPicked: (Int) -> Unit = {},
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
        val isAudio = mediaKind == "audio"
        val isImage = mediaKind == "image"
        val surface = state.controlsSurface()

        // Main controls layer: seek strip, full controls and the side panels.
        AnimatedVisibility(
            visible = state.isVisible &&
                state.playbackTransitionMessage == null &&
                state.playbackError == null,
            enter = fadeIn(TvExpressiveMotion.effects()),
            exit = fadeOut(TvExpressiveMotion.effects()),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Settings Panel (Slides from right)
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.SETTINGS,
                    enter = slideInHorizontally(TvExpressiveMotion.spatial()) { it } + fadeIn(TvExpressiveMotion.effects()),
                    exit = slideOutHorizontally(TvExpressiveMotion.spatial()) { it } + fadeOut(TvExpressiveMotion.effects()),
                    modifier = Modifier.fillMaxSize()
                ) {
                    MediaSettingsPanel(
                        state = state,
                        onTrackSelected = onTrackSelected,
                        onSpeedSelected = onSpeedSelected,
                        onScalingSelected = onScalingSelected,
                        onToggleAudioBoost = onToggleAudioBoost,
                        onDismiss = onSettingsDismiss,
                        onPlayerSwitched = onPlayerSwitched,
                        onToggleLoop = onLoop,
                        onOpenChapters = onChapters,
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

                // Chapter Picker Overlay
                AnimatedVisibility(
                    visible = state.activeOverlay == ActiveOverlay.CHAPTERS,
                    enter = slideInHorizontally(TvExpressiveMotion.spatial()) { it } + fadeIn(TvExpressiveMotion.effects()),
                    exit = slideOutHorizontally(TvExpressiveMotion.spatial()) { it } + fadeOut(TvExpressiveMotion.effects()),
                    modifier = Modifier.fillMaxSize()
                ) {
                    ChapterPickerDialog(
                        chapters = state.chapters,
                        currentPositionMs = state.currentPosition,
                        onChapterSelected = onChapterPicked,
                        onDismiss = onOverlayDismiss,
                    )
                }

                // The Still Watching prompt owns focus while it is up; the controls beneath it wait.
                if (surface == ControlsSurface.CONTROLS && !isAudio && !stillWatchingState.isPrompting) {
                    FullControls(
                        state = state,
                        progress = progress,
                        mediaKind = mediaKind,
                        onTogglePlay = onTogglePlay,
                        // Audio opens the panel on its Audio page; More opens the list.
                        onAudio = { onSettingsTabSelected(SettingsTab.AUDIO) },
                        onMore = { onSettingsTabSelected(SettingsTab.SPEED) },
                        onSubtitles = onSubtitles,
                        onPlaylist = onPlaylist,
                        onPrev = onPrev,
                        onNext = onNext,
                        onSeek = onSeek,
                        onBookmark = onBookmark,
                        party = party,
                        onLeaveParty = onLeaveParty,
                    )
                } else if (surface == ControlsSurface.SEEK_BAR && !isAudio && !isImage) {
                    val showTimelineMetadata = !state.capabilities.isLive && state.duration > 0L
                    val timelineChapters = if (showTimelineMetadata) state.chapters else emptyList()
                    val timelineSegments = if (showTimelineMetadata) state.skipSegments else emptyList()
                    val seekCaption = state.chapterOsd ?: if (showTimelineMetadata) {
                        com.playbridge.player.player.timelineCaption(
                            chapters = timelineChapters,
                            segments = timelineSegments,
                            positionMs = state.currentPosition,
                            durationMs = state.duration,
                        )
                    } else {
                        null
                    }
                    EdgeScrim(top = false, height = SEEK_STRIP_SCRIM_HEIGHT, alpha = TvTheme.player.scrimTopAlpha, modifier = Modifier.align(Alignment.BottomCenter))
                    SeekStrip(
                        progress = progress,
                        chapters = timelineChapters,
                        segments = timelineSegments,
                        caption = seekCaption,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
                    )
                }
            }
        }

        if (state.prePlayMetadata == null && state.playbackError == null &&
            (state.playbackTransitionMessage != null ||
                (state.isBuffering && !state.isVisible))
        ) {
            PlaybackTransitionOverlay(
                message = state.playbackTransitionMessage,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Subtitle Overlay (Manual Parser)
        val liftDp = state.subtitleLiftDp()
        if (state.currentSubtitleText != null && state.playbackTransitionMessage == null) {
            SubtitleOverlay(
                text = state.currentSubtitleText,
                style = subtitleStyle,
                typeface = subtitleTypeface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // One translation, no size or layout change: clear of the strip or controls.
                    .graphicsLayer { translationY = -liftDp.dp.toPx() },
            )
        }

        // Small play/pause badge for a media key pressed with nothing else on screen.
        TransportBadgeView(
            badge = state.transportBadge,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = TvSpacing.playerEdge, end = TvSpacing.screenGutter),
        )

        // Skip intro / recap / credits
        state.activeSkipSegment
            ?.takeUnless { state.isPlaybackObscured() || surface == ControlsSurface.PANEL }
            ?.let { segment ->
                val bottom = when (surface) {
                    ControlsSurface.CONTROLS -> 320.dp
                    ControlsSurface.SEEK_BAR -> 196.dp
                    else -> 56.dp
                }
                SkipSegmentButton(
                    segment = segment,
                    onSkip = onSkipSegment,
                    onFocusChanged = onSkipButtonFocusChanged,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = TvSpacing.screenGutter, bottom = bottom),
                )
            }

        // Short notices: bookmark confirmations (light pill, above the controls) and "Press Back again to stop".
        state.notice?.let { notice ->
            val bottom = when (surface) {
                ControlsSurface.CONTROLS -> 262.dp
                ControlsSurface.SEEK_BAR -> 196.dp
                else -> 56.dp
            }
            NoticePill(
                notice = notice,
                modifier = if (notice.emphasis) {
                    Modifier.align(Alignment.BottomEnd).padding(end = TvSpacing.screenGutter, bottom = bottom)
                } else {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = bottom)
                },
            )
        }

        // Watch Together: member chips, the "waiting for ..." pill and party notices. Not focusable.
        // On a bare video it only appears while someone is loading or there is something to say.
        if (party != null) {
            val ui = party.toPartyUi()
            if (surface != ControlsSurface.NOTHING || ui.waitingFor.isNotEmpty() || ui.notice != null) {
                TogetherPlayerChrome(party)
            }
        }

        // What a phone just did: a short confirmation, never the full controls and never focus.
        if (state.prePlayMetadata == null && state.playbackError == null && !stillWatchingState.isPrompting) {
            val seekTarget = state.phoneSeek?.targetMs
            PhoneConfirmations(
                state = state,
                progress = progress,
                controlsUp = surface == ControlsSurface.CONTROLS || surface == ControlsSurface.PANEL,
                seekCaption = state.phoneSeek?.let { seek ->
                    phoneSeekCaption(
                        seek.name,
                        if (!state.capabilities.isLive && state.duration > 0L) {
                            com.playbridge.player.player.timelineCaption(
                                chapters = state.chapters,
                                segments = state.skipSegments,
                                positionMs = seekTarget ?: 0L,
                                durationMs = state.duration,
                            )
                        } else {
                            null
                        },
                    )
                },
                rightTopInset = if (party != null) 56.dp else 0.dp,
            )
        }

        // PrePlay Overlay — deliberately above the spinner and subtitles: it must cover them
        // while the player pre-buffers a resume position underneath.
        state.prePlayMetadata?.let { metadata ->
            com.playbridge.player.preplay.PrePlayScreen(
                metadata = metadata,
                isLaunching = state.isPrePlayLaunching,
                launchCountdown = state.prePlayCountdown,
                onStartNow = onPrePlayStartNow,
                onBack = onPrePlayBack,
                resumePositionMs = state.prePlayResume?.positionMs,
                resumeDurationMs = state.prePlayResume?.durationMs,
                onStartOver = if (state.prePlayResume != null) onPrePlayStartOver else null,
                choiceWindowSeconds = com.playbridge.player.preplay.PREPLAY_COUNTDOWN_SECONDS,
            )
        }

        if (stillWatchingState.isPrompting) {
            StillWatchingPrompt(
                title = state.title,
                secondsRemaining = stillWatchingState.secondsRemaining,
                onContinue = onContinueWatching,
                onStop = onStopWatching,
            )
        }

        state.playbackError?.let { reason ->
            PlaybackErrorScreen(title = state.title, reason = reason)
        }
    }
}

private val SEEK_STRIP_SCRIM_HEIGHT = 170.dp

/** Title, seek bar and the fixed button row on a top and bottom scrim (mockup PlayerControls). */
@Composable
private fun BoxScope.FullControls(
    state: PlayerControlsState,
    progress: StateFlow<SeekProgress>,
    mediaKind: String,
    onTogglePlay: () -> Unit,
    onAudio: () -> Unit,
    onMore: () -> Unit,
    onSubtitles: () -> Unit,
    onPlaylist: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onBookmark: () -> Unit,
    party: TvTogether.State.InParty?,
    onLeaveParty: () -> Unit,
) {
    val isImage = mediaKind == "image"
    val seekFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val focusLabel = remember { mutableStateOf("") }
    val showTimeline = !state.capabilities.isLive && state.duration > 0L

    EdgeScrim(top = true, height = 130.dp, alpha = TvTheme.player.scrimTopAlpha, modifier = Modifier.align(Alignment.TopCenter))
    EdgeScrim(top = false, height = 250.dp, alpha = TvTheme.player.scrimBottomAlpha, modifier = Modifier.align(Alignment.BottomCenter))
    TopMetadata(
        title = state.title,
        context = if (party == null) controlsContextLine(state) else null,
        contextContent = party?.let { { TogetherPartyLabel(it) } },
        modifier = Modifier.align(Alignment.TopCenter),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = TvSpacing.screenGutter)
            .padding(bottom = TvSpacing.playerEdge),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (!isImage) {
            PlayerSeekbar(
                progress = progress,
                onSeek = onSeek,
                focusRequester = seekFocus,
                downFocus = playFocus,
                onFocused = { focusLabel.value = "" },
                chapters = if (showTimeline) state.chapters else emptyList(),
                segments = if (showTimeline) state.skipSegments else emptyList(),
            )
        }
        ControlActionButtons(
            isPlaying = state.isPlaying,
            hasPlaylist = state.hasPlaylist,
            bookmark = state.bookmark,
            focusLabel = focusLabel,
            seekBarFocus = if (isImage) FocusRequester.Default else seekFocus,
            onTogglePlay = onTogglePlay,
            onSeekBack = { onSeek(-SEEK_STEP_MS) },
            onSeekForward = { onSeek(SEEK_STEP_MS) },
            onPrev = onPrev,
            onNext = onNext,
            onBookmark = onBookmark,
            onSubtitles = onSubtitles,
            onAudio = onAudio,
            onPlaylist = onPlaylist,
            onMore = onMore,
            mediaKind = mediaKind,
            onLeaveParty = if (party != null) onLeaveParty else null,
            initialFocusTarget = state.controlActionFocusTarget(mediaKind),
            audioHasFocusAfterPanel = state.activeSettingsTab == SettingsTab.AUDIO,
            playFocus = playFocus,
        )
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
            .background(if (message != null) TvTheme.player.background else Color.Transparent),
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
                    color = TvTheme.player.onSurface.copy(alpha = textAlpha),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(TvTheme.player.background.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SubtitleOverlay(
    text: String,
    style: com.playbridge.shared.player.SubtitleStyle,
    typeface: android.graphics.Typeface?,
    modifier: Modifier = Modifier,
) {
    val startSize = BASE_SUBTITLE_TEXT_SIZE_SP * style.size.scale
    var fontSize by remember(text, startSize) { mutableStateOf(startSize.sp) }
    val box = Color.Black.copy(alpha = style.background.opacity)
    val base = androidx.compose.ui.text.TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        // Android falls back to the device's fonts for characters the custom font lacks.
        fontFamily = typeface?.let { androidx.compose.ui.text.font.FontFamily(it) },
        color = Color.White,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        shadow = if (style.edge == com.playbridge.shared.player.SubtitleStyle.Edge.SHADOW) {
            androidx.compose.ui.graphics.Shadow(
                color = Color.Black,
                offset = androidx.compose.ui.geometry.Offset(2f, 3f),
                blurRadius = 6f,
            )
        } else null,
        lineHeight = (fontSize.value * 1.25f).sp,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.65f)
            .padding(start = 96.dp, end = 96.dp, top = 8.dp, bottom = 72.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            Modifier
                .widthIn(max = 1400.dp)
                .background(box, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            // An outline is a stroked copy of the text drawn underneath the fill.
            if (style.edge == com.playbridge.shared.player.SubtitleStyle.Edge.OUTLINE) {
                Text(
                    text = text,
                    style = base.copy(
                        color = Color.Black,
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = fontSize.value * 0.18f,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    ),
                    softWrap = true,
                    maxLines = 14,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                )
            }
            Text(
                text = text,
                style = base,
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
            )
        }
    }
}

private const val BASE_SUBTITLE_TEXT_SIZE_SP = 30f
private const val MIN_SUBTITLE_TEXT_SIZE_SP = 18f
private const val SUBTITLE_TEXT_SIZE_STEP_SP = 2f
