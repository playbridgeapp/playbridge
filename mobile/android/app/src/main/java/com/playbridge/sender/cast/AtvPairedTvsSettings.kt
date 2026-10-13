package com.playbridge.sender.cast

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.playbridge.sender.cast.atv.ATV_REMOVE_ERROR
import com.playbridge.sender.cast.atv.AtvCredentialStore
import com.playbridge.sender.cast.atv.AtvStorageException
import com.playbridge.sender.cast.atv.AtvTvRecord

@Composable
internal fun AtvPairedTvsSettings(store: AtvCredentialStore) {
    val pairedTvs by store.records.collectAsState()
    val recoveryRequired by store.recoveryRequired.collectAsState()
    var pendingForget by remember { mutableStateOf<AtvTvRecord?>(null) }
    var removeFailed by remember { mutableStateOf(false) }
    Text("Paired TVs", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (recoveryRequired) {
        Text("Pair again", color = MaterialTheme.colorScheme.error)
        Text("Open the remote and pair your TVs again.", style = MaterialTheme.typography.bodyMedium)
    } else if (pairedTvs.isEmpty()) {
        Text("No paired TVs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    pairedTvs.forEach { tv ->
        val label = tv.serverName ?: tv.lastIp ?: "Paired TV"
        ListItem(
            headlineContent = { Text(label) },
            trailingContent = {
                TextButton(
                    onClick = { removeFailed = false; pendingForget = tv },
                    modifier = Modifier.semantics { contentDescription = "Forget $label" },
                ) { Text("Forget") }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    pendingForget?.let { tv ->
        val label = tv.serverName ?: tv.lastIp ?: "Paired TV"
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text("Forget $label?") },
            text = {
                Text(if (removeFailed) ATV_REMOVE_ERROR else "PlayBridge will need a new pairing code to control this TV.")
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        store.forget(tv.fingerprint)
                        pendingForget = null
                    } catch (_: AtvStorageException) {
                        removeFailed = true
                    }
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { pendingForget = null }) { Text("Cancel") } },
        )
    }
}
