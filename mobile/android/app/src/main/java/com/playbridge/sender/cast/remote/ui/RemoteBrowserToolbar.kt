package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.receiverActionSupported
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playbridge.sender.cast.remote.BrowserAction
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.remotePressFeedback

/**
 * Browser toolbar: ← → ⟳ ⛶ ⋯
 * - Fullscreen shows Restore after maximize_video is accepted, until a TV report or a page change
 * - ⋯ opens BrowserMoreSheet (Source, Unmute, Scripts, User Agent)
 * - Overflow at large fonts remains horizontally reachable
 */
@Composable
internal fun RemoteBrowserToolbar(
    state: RemoteUiState,
    dispatch: (RemoteAction) -> Unit,
) {
    val isFullscreen = state.browser?.fullscreen == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .remoteSolidSurface(RoundedCornerShape(16.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.receiverActionSupported(RemoteAction.Key(RemoteKey.Back))) LabeledIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            label = "Back",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { dispatch(RemoteAction.Key(RemoteKey.Back)) },
        )
        if (state.receiverActionSupported(RemoteAction.Browser(BrowserAction.Forward))) LabeledIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            label = "Forward",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { dispatch(RemoteAction.Browser(BrowserAction.Forward)) },
        )
        if (state.receiverActionSupported(RemoteAction.Browser(BrowserAction.Reload))) LabeledIconButton(
            icon = Icons.Default.Refresh,
            label = "Refresh",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { dispatch(RemoteAction.Browser(BrowserAction.Reload)) },
        )
        if (state.receiverActionSupported(RemoteAction.SetBrowserFullscreen(!isFullscreen))) LabeledIconButton(
            icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            label = if (isFullscreen) "Restore" else "Fullscreen",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { dispatch(RemoteAction.SetBrowserFullscreen(!isFullscreen)) },
        )
        if (state.nativePolicy == null || state.capabilities.browser.managers == com.playbridge.sender.cast.remote.Support.Supported ||
            state.capabilities.browser.actions.any { it == BrowserAction.CycleSource || it == BrowserAction.Unmute }) LabeledIconButton(
            icon = Icons.Default.MoreHoriz,
            label = "More",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { dispatch(RemoteAction.OpenSheet(RemoteSheet.BrowserMore)) },
        )
    }
}

@Composable
internal fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 6.dp),
    ) {
        IconButton(
            enabled = LocalRemoteControlsEnabled.current,
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .remotePressFeedback(interaction),
        ) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.7f),
            fontSize = 10.sp,
        )
    }
}
