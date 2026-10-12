package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteUiState

/** Stage 3d owns linked-website content, including the existing phone-side unlink action. */
@Composable
internal fun RemoteLinkedWebsite(state: RemoteUiState, dispatch: (RemoteAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary)
        Text("Controlled by ${state.linkedWebsite?.controllerName ?: "website"}", modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { dispatch(RemoteAction.UnlinkWebsite) }) { Text("Unlink") }
    }
}
