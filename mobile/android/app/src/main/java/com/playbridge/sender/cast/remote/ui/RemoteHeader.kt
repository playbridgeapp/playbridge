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
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteContext
import com.playbridge.sender.cast.remote.RemoteMediaKind
import com.playbridge.sender.cast.remote.RemoteProfile
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
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!state.connection.ready) Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                        Text(state.receiver?.endpoint?.protocol?.displayName ?: "Receiver", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (!state.connection.ready) {
            TextButton(onClick = { dispatch(RemoteAction.RetryConnection) }) { Text("Retry") }
        }
        // The stage-4 Open app menu is hidden until it has an executable item.
    }
}
