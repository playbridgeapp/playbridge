package com.playbridge.player.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.playbridge.player.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ControlActionButtons(
    isPlaying: Boolean,
    isLooping: Boolean,
    hasPlaylist: Boolean,
    hasMultipleStreams: Boolean,
    canSwitchPlayer: Boolean,
    mediaKind: String = "video",
    onTogglePlay: () -> Unit,
    onTrackSelection: () -> Unit,
    onSubtitles: () -> Unit,
    onPlaylist: () -> Unit,
    onStreams: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLoop: () -> Unit,
    onSwitchPlayer: () -> Unit,
    hasChapters: Boolean = false,
    onChapters: () -> Unit = {},
    initialFocusTarget: ControlActionFocusTarget = ControlActionFocusTarget.PLAY_PAUSE,
    isVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    val playPauseFocusRequester = remember { FocusRequester() }
    val playlistFocusRequester = remember { FocusRequester() }
    val chaptersFocusRequester = remember { FocusRequester() }
    val subtitlesFocusRequester = remember { FocusRequester() }
    val switchPlayerFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isVisible, initialFocusTarget, hasPlaylist, hasChapters, canSwitchPlayer, mediaKind) {
        if (isVisible) {
            val requester = when (initialFocusTarget) {
                ControlActionFocusTarget.PLAYLIST ->
                    if (hasPlaylist) playlistFocusRequester else playPauseFocusRequester
                ControlActionFocusTarget.CHAPTERS ->
                    if (hasChapters) chaptersFocusRequester else playPauseFocusRequester
                ControlActionFocusTarget.SUBTITLES ->
                    if (mediaKind == "video") subtitlesFocusRequester else playPauseFocusRequester
                ControlActionFocusTarget.SWITCH_PLAYER ->
                    if (canSwitchPlayer && mediaKind != "image") switchPlayerFocusRequester else playPauseFocusRequester
                ControlActionFocusTarget.SETTINGS ->
                    if (mediaKind != "image") settingsFocusRequester else playPauseFocusRequester
                ControlActionFocusTarget.PLAY_PAUSE -> playPauseFocusRequester
            }
            try {
                requester.requestFocus()
            } catch (_: Exception) {
                // Ignore focus errors if not attached yet
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        // Navigation (if playlist)
        if (hasPlaylist) {
            PlayerIconButton(
                iconRes = R.drawable.ic_skip_previous,
                contentDescription = "Previous",
                onClick = onPrev
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        // Play / Pause
        Surface(
            onClick = onTogglePlay,
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                focusedContainerColor = MaterialTheme.colorScheme.primary
            ),
            shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.extraLarge),
            modifier = Modifier
                .size(56.dp)
                .focusRequester(playPauseFocusRequester)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
        }

        if (hasPlaylist) {
            Spacer(modifier = Modifier.width(12.dp))
            PlayerIconButton(
                iconRes = R.drawable.ic_skip_next,
                contentDescription = "Next",
                onClick = onNext
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (hasPlaylist) {
            PlayerIconButton(
                iconRes = R.drawable.ic_list,
                contentDescription = "Playlist",
                onClick = onPlaylist,
                modifier = Modifier.focusRequester(playlistFocusRequester),
            )
        }

        if (hasChapters) {
            PlayerIconButton(
                iconRes = R.drawable.ic_chapters,
                contentDescription = "Chapters",
                onClick = onChapters,
                modifier = Modifier.focusRequester(chaptersFocusRequester),
            )
        }

        if (hasMultipleStreams) {
            PlayerIconButton(
                iconRes = R.drawable.ic_dns,
                contentDescription = "Streams",
                onClick = onStreams
            )
        }

        if (mediaKind == "video") {
            PlayerIconButton(
                iconRes = R.drawable.ic_subtitles,
                contentDescription = "Subtitles",
                onClick = onSubtitles,
                modifier = Modifier.focusRequester(subtitlesFocusRequester),
            )
        }

        if (mediaKind != "image") {
            PlayerIconButton(
                iconRes = R.drawable.ic_loop,
                contentDescription = "Loop",
                onClick = onLoop,
                tint = if (isLooping) MaterialTheme.colorScheme.primary else Color.White
            )
        }

        if (canSwitchPlayer && mediaKind != "image") {
            PlayerIconButton(
                iconRes = android.R.drawable.ic_menu_sort_by_size,
                contentDescription = "Switch Player",
                onClick = onSwitchPlayer,
                modifier = Modifier.focusRequester(switchPlayerFocusRequester),
            )
        }

        // Tracks / Settings (Far right)
        if (mediaKind != "image") {
            PlayerIconButton(
                iconRes = R.drawable.ic_settings,
                contentDescription = "Tracks",
                onClick = onTrackSelection,
                modifier = Modifier.focusRequester(settingsFocusRequester),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (isFocused) 1.08f else 1.15f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.2f)
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        modifier = modifier
            .height(44.dp)
            .widthIn(min = 44.dp)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .height(44.dp)
                .padding(horizontal = if (isFocused) 12.dp else 10.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = tint
            )
            if (isFocused) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = contentDescription,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}
