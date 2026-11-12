package com.playbridge.player.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.playbridge.player.R
import com.playbridge.player.ui.TogetherEveryoneHint
import com.playbridge.player.ui.TogetherLeaveButton
import com.playbridge.player.ui.components.IconCircleButton
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/** Which control-row button focus goes to. */
internal enum class ControlButtonSlot { PLAY, PLAYLIST, SUBTITLES, AUDIO, MORE }

/** Delays before each attempt to focus the target; spans the side panel's exit animation. */
internal val FOCUS_RETRY_DELAYS_MS = longArrayOf(0L, 120L, 250L, 400L, 650L)

/**
 * The button that gets focus for [target] (the overlay that just closed). A button that is not in
 * the row falls back to Play. Chapters open from More, so they return to More.
 */
internal fun controlFocusSlot(
    target: ControlActionFocusTarget,
    showQueue: Boolean,
    showSubtitles: Boolean,
    showAudio: Boolean,
    showMore: Boolean,
    audioHasFocusAfterPanel: Boolean,
): ControlButtonSlot = when (target) {
    ControlActionFocusTarget.PLAYLIST -> if (showQueue) ControlButtonSlot.PLAYLIST else ControlButtonSlot.PLAY
    ControlActionFocusTarget.CHAPTERS -> if (showMore) ControlButtonSlot.MORE else ControlButtonSlot.PLAY
    ControlActionFocusTarget.SUBTITLES -> if (showSubtitles) ControlButtonSlot.SUBTITLES else ControlButtonSlot.PLAY
    ControlActionFocusTarget.SETTINGS -> when {
        audioHasFocusAfterPanel && showAudio -> ControlButtonSlot.AUDIO
        showMore -> ControlButtonSlot.MORE
        else -> ControlButtonSlot.PLAY
    }
    ControlActionFocusTarget.PLAY_PAUSE -> ControlButtonSlot.PLAY
}

/**
 * The full-controls button row (mockups PlayerControls and ControlsFirstPress).
 *
 * Every button is a fixed circle (60dp, Play/Pause 72dp) and one fixed 220dp label slot between
 * the two clusters names the focused button, so focus never resizes or moves anything. Focus is
 * a colour swap and ring only ([IconCircleButton]). Up from any button goes to [seekBarFocus].
 *
 * Left cluster: previous item (queue), back 10 s, play/pause, forward 10 s, next item (queue).
 * Right cluster: bookmark, subtitles, audio, queue, more. A button only exists when the app can do
 * what it says. Photos get play/pause (the slideshow timer) and queue. Chapters, Repeat and Player
 * live in the More panel. In a Watch Together party ([onLeaveParty] non-null) the centre also says
 * that play, pause and seek apply to everyone, and "Leave party" ends the row.
 */
@Composable
fun ControlActionButtons(
    isPlaying: Boolean,
    hasPlaylist: Boolean,
    bookmark: BookmarkState,
    focusLabel: MutableState<String>,
    seekBarFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBookmark: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onPlaylist: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    mediaKind: String = "video",
    onLeaveParty: (() -> Unit)? = null,
    initialFocusTarget: ControlActionFocusTarget = ControlActionFocusTarget.PLAY_PAUSE,
    audioHasFocusAfterPanel: Boolean = true,
    playFocus: FocusRequester = remember { FocusRequester() },
) {
    val isImage = mediaKind == "image"
    val isVideo = mediaKind == "video"
    val playlistFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }

    val showQueue = hasPlaylist
    val showSubtitles = isVideo
    val showAudio = !isImage
    val showMore = !isImage
    val showBookmark = bookmark != BookmarkState.UNAVAILABLE && !isImage

    // Focus is requested when the row appears (first open, or coming back from a side panel) and
    // again shortly after: a closing panel is still on screen, and still holding focus, while its
    // exit animation runs. When it leaves, Compose hands focus to the first focusable thing (the
    // seek bar), so a single early request does not stick. Retries stop as soon as a button has focus,
    // so they never fight the viewer.
    val rowFocused = remember { mutableStateOf(false) }
    LaunchedEffect(
        initialFocusTarget, hasPlaylist, mediaKind, audioHasFocusAfterPanel,
    ) {
        val target = when (
            controlFocusSlot(initialFocusTarget, showQueue, showSubtitles, showAudio, showMore, audioHasFocusAfterPanel)
        ) {
            ControlButtonSlot.PLAYLIST -> playlistFocus
            ControlButtonSlot.SUBTITLES -> subtitlesFocus
            ControlButtonSlot.AUDIO -> audioFocus
            ControlButtonSlot.MORE -> moreFocus
            ControlButtonSlot.PLAY -> playFocus
        }
        for (wait in FOCUS_RETRY_DELAYS_MS) {
            if (wait > 0L) delay(wait)
            if (rowFocused.value) break
            runCatching { target.requestFocus() }
        }
    }

    val rightGap = 20.dp

    Row(
        modifier = modifier.fillMaxWidth().onFocusChanged { rowFocused.value = it.hasFocus },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (hasPlaylist) {
                RowButton(R.drawable.ic_skip_previous, ControlLabels.PREVIOUS, focusLabel, seekBarFocus, onPrev)
            }
            if (!isImage) {
                RowButton(R.drawable.ic_player_replay10, ControlLabels.BACK_10, focusLabel, seekBarFocus, onSeekBack)
            }
            RowButton(
                icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                label = ControlLabels.playPause(isPlaying),
                focusLabel = focusLabel,
                seekBarFocus = seekBarFocus,
                onClick = onTogglePlay,
                modifier = Modifier.focusRequester(playFocus),
                size = TvSize.iconButtonLarge,
            )
            if (!isImage) {
                RowButton(R.drawable.ic_player_forward10, ControlLabels.FORWARD_10, focusLabel, seekBarFocus, onSeekForward)
            }
            if (hasPlaylist) {
                RowButton(R.drawable.ic_skip_next, ControlLabels.NEXT, focusLabel, seekBarFocus, onNext)
            }
        }

        if (onLeaveParty != null) {
            PartyCentre(focusLabel, Modifier.weight(1f).padding(horizontal = 16.dp))
        } else {
            Spacer(Modifier.weight(1f))
            FocusLabelSlot(focusLabel)
            Spacer(Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rightGap)) {
            if (showBookmark) {
                RowButton(
                    icon = if (bookmark == BookmarkState.ON) R.drawable.ic_player_bookmark_filled else R.drawable.ic_player_bookmark,
                    label = ControlLabels.bookmark(bookmark),
                    focusLabel = focusLabel,
                    seekBarFocus = seekBarFocus,
                    onClick = onBookmark,
                    active = bookmark == BookmarkState.ON,
                )
            }
            if (showSubtitles) {
                RowButton(
                    R.drawable.ic_player_subtitles, ControlLabels.SUBTITLES, focusLabel, seekBarFocus, onSubtitles,
                    Modifier.focusRequester(subtitlesFocus),
                )
            }
            if (showAudio) {
                RowButton(
                    R.drawable.ic_player_audio, ControlLabels.AUDIO, focusLabel, seekBarFocus, onAudio,
                    Modifier.focusRequester(audioFocus),
                )
            }
            if (showQueue) {
                RowButton(
                    R.drawable.ic_player_queue, ControlLabels.QUEUE, focusLabel, seekBarFocus, onPlaylist,
                    Modifier.focusRequester(playlistFocus),
                )
            }
            if (showMore) {
                RowButton(
                    R.drawable.ic_player_more, ControlLabels.MORE, focusLabel, seekBarFocus, onMore,
                    Modifier.focusRequester(moreFocus),
                )
            }
            if (onLeaveParty != null) {
                TogetherLeaveButton(onLeave = onLeaveParty, modifier = Modifier.focusProperties { up = seekBarFocus })
            }
        }
    }
}

@Composable
private fun RowButton(
    icon: Int,
    label: String,
    focusLabel: MutableState<String>,
    seekBarFocus: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = TvSize.iconButton,
    active: Boolean = false,
) {
    IconCircleButton(
        icon = painterResource(icon),
        contentDescription = label,
        onClick = onClick,
        size = size,
        tone = TvTone.Player,
        active = active,
        modifier = modifier
            .focusProperties { up = seekBarFocus }
            .onFocusChanged { if (it.isFocused) focusLabel.value = label },
    )
}

/** Fixed-width slot, so the name of the focused button never moves or resizes anything. */
@Composable
private fun FocusLabelSlot(focusLabel: MutableState<String>) {
    Box(Modifier.width(TvSize.controlLabelSlot), contentAlignment = Alignment.Center) {
        Text(
            text = focusLabel.value,
            style = TvText.button,
            color = TvTheme.player.onSurface,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** In a party: the focused button's name over the "applies to everyone" line, in the space between the two button groups. */
@Composable
private fun PartyCentre(focusLabel: MutableState<String>, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = focusLabel.value.ifEmpty { " " },
            style = TvText.button,
            color = TvTheme.player.onSurface,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        TogetherEveryoneHint()
    }
}
