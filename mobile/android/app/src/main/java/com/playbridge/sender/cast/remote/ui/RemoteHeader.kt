package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.atv.ATV_SAVE_ERROR
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteContext
import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.RemoteMediaKind
import com.playbridge.sender.cast.remote.RemoteProfile
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.ready

/** Visible header line. TalkBack reads the same string. */
internal fun remoteConnectionTitle(state: RemoteUiState): String {
    val name = state.receiver?.name ?: "TV"
    if (!state.connection.ready) return "Reconnecting…"
    if (state.receiver?.profile == RemoteProfile.External) return "Casting to $name"
    return when (state.context) {
        RemoteContext.Player -> when (state.playback?.mediaKind) {
            RemoteMediaKind.Audio -> "Listening on $name"
            RemoteMediaKind.Image -> "Viewing on $name"
            else -> "Watching on $name"
        }
        RemoteContext.Browser -> "Browsing on $name"
        else -> "Connected to $name"
    }
}

/** Phone Back and connection controls remain usable while receiver actions are disabled. */
@Composable
internal fun RemoteHeader(state: RemoteUiState, dispatch: (RemoteAction) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("remote.header"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { dispatch(RemoteAction.BackToOrigin) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Surface(
            onClick = { dispatch(RemoteAction.OpenConnections) },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Tv, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    val name = state.receiver?.name ?: "TV"
                    val title = remoteConnectionTitle(state)
                    Text(
                        title,
                        modifier = Modifier.semantics { contentDescription = title },
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!state.connection.ready) Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                        Text(state.receiver?.endpoint?.protocol?.displayName ?: "Receiver", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        FullControlTag(state, dispatch)
                    }
                    if (state.fullControl?.storageFailure == true) {
                        Text(ATV_SAVE_ERROR, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (!state.connection.ready) {
            TextButton(onClick = { dispatch(RemoteAction.RetryConnection) }) { Text("Retry") }
        }
        FullControlMenu(state, dispatch)
    }
}

@Composable
private fun FullControlTag(state: RemoteUiState, dispatch: (RemoteAction) -> Unit) {
    val control = state.fullControl ?: return
    val powerOff = control.connected && control.powerOn == false
    when {
        control.connected -> Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.testTag("remote.fullControl").semantics { contentDescription = "Full control connected" },
        ) {
            Text("Full control", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
        control.needsRepair || control.pinMismatch -> Surface(
            onClick = { dispatch(RemoteAction.RequestWholeTvControl) },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.testTag("remote.repair"),
        ) {
            Text("Pair again", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
        control.available && !control.paired -> Surface(
            onClick = { dispatch(RemoteAction.RequestWholeTvControl) },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.testTag("remote.getFullControl"),
        ) {
            Text("Get full control", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (powerOff) {
        Text(
            "Off",
            modifier = Modifier.testTag("remote.powerOff").semantics { contentDescription = "TV is off" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FullControlMenu(state: RemoteUiState, dispatch: (RemoteAction) -> Unit) {
    val control = state.fullControl
    if (control?.connected != true) return
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "More TV controls" }) {
        Icon(Icons.Default.MoreVert, "More TV controls")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Open app…") },
            onClick = {
                expanded = false
                dispatch(RemoteAction.OpenSheet(RemoteSheet.OpenTvApp))
            },
        )
        DropdownMenuItem(
            text = { Text("TV menu") },
            onClick = {
                expanded = false
                dispatch(RemoteAction.Key(RemoteKey.Menu))
            },
        )
        if (control.powerOn == false) {
            DropdownMenuItem(
                text = { Text("Power") },
                onClick = {
                    expanded = false
                    dispatch(RemoteAction.PowerTv)
                },
            )
        }
    }
}
