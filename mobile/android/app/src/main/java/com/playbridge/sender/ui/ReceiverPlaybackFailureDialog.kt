package com.playbridge.sender.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** A TV playback failure or rejected cast; dismissing it keeps the same failure from returning. */
@Composable
internal fun ReceiverPlaybackFailureDialog(deviceName: String?, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(if (deviceName.isNullOrBlank()) "Couldn't play on the TV" else "Couldn't play on $deviceName") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
