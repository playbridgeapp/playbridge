package com.playbridge.player.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.*
import com.playbridge.player.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ControlActionButtons(
    isPlaying: Boolean,
    isLooping: Boolean,
    hasPlaylist: Boolean,
    hasMultipleStreams: Boolean,
    onTogglePlay: () -> Unit,
    onTrackSelection: () -> Unit,
    onPlaylist: () -> Unit,
    onStreams: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLoop: () -> Unit,
    onSwitchPlayer: () -> Unit,
    isVisible: Boolean = false,
    modifier: Modifier = Modifier
) {
    val playPauseFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            playPauseFocusRequester.requestFocus()
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
                onClick = onPlaylist
            )
        }

        if (hasMultipleStreams) {
            PlayerIconButton(
                iconRes = R.drawable.ic_dns,
                contentDescription = "Streams",
                onClick = onStreams
            )
        }

        PlayerIconButton(
            iconRes = R.drawable.ic_loop,
            contentDescription = "Loop",
            onClick = onLoop,
            tint = if (isLooping) MaterialTheme.colorScheme.primary else Color.White
        )

        PlayerIconButton(
            iconRes = android.R.drawable.ic_menu_sort_by_size,
            contentDescription = "Switch Player",
            onClick = onSwitchPlayer
        )

        // Tracks / Settings (Far right)
        PlayerIconButton(
            iconRes = R.drawable.ic_settings,
            contentDescription = "Tracks",
            onClick = onTrackSelection
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.2f)
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp),
                tint = tint
            )
        }
    }
}
