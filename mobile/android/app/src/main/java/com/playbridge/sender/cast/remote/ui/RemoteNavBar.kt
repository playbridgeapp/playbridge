package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteCallbackActions
import com.playbridge.sender.cast.remote.RemoteLayoutSpec
import com.playbridge.sender.cast.remote.RemoteNavAvailability
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState

@Composable
internal fun RemoteNavBar(nav: RemoteNavAvailability, onRemoteKey: (String) -> Unit, onKeys: () -> Unit) {
    if (!nav.back && !nav.home && !nav.keys && !nav.volume) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f), shape = CircleShape) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (nav.back) RemoteButton(Icons.AutoMirrored.Filled.Undo, "TV Back", true) { onRemoteKey("back") }
            if (nav.home) RemoteButton(Icons.Default.Home, "TV Home", true) { onRemoteKey("home") }
            if (nav.keys) RemoteButton(Icons.Default.Keyboard, "Keys", true, onKeys)
            if (nav.volume) {
                RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, "TV volume down", true) { onRemoteKey("volume_down") }
                RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, "TV volume up", true) { onRemoteKey("volume_up") }
            }
        }
    }
}

@Composable
internal fun RemoteNavBar(state: RemoteUiState, layout: RemoteLayoutSpec, dispatch: (RemoteAction) -> Unit) {
    val nav = RemoteNavAvailability(layout.nav.back.enabled, layout.nav.home.enabled, layout.nav.keys.enabled, layout.nav.volume.enabled)
    if (!nav.back && !nav.home && !nav.keys && !nav.volume) return
    val level = state.capabilities.volume.level
    val maximum = state.capabilities.volume.maximum
    Column(Modifier.testTag("remote.nav"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (nav.volume && level != null && maximum != null && maximum > 0) {
            val label = if (state.capabilities.volume.muted == true) "$level/$maximum muted" else "$level/$maximum"
            Text(
                label,
                modifier = Modifier
                    .testTag("remote.volumeLevel")
                    .semantics { contentDescription = "TV volume $level of $maximum" },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        RemoteNavBar(
            nav,
            onRemoteKey = { dispatch(RemoteCallbackActions.key(it)) },
            onKeys = { dispatch(RemoteAction.OpenSheet(RemoteSheet.Keyboard)) },
        )
    }
}
