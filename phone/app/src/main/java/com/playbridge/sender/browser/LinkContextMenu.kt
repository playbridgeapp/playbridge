package com.playbridge.sender.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Context menu shown on a long-pressed link.
 *
 * @param url            the link URL, or null to hide the menu
 * @param isConnected    whether the phone is connected to a TV
 * @param onPlayOnTv     called when the user taps "Play on TV"
 * @param onOpenInNewTab called when the user taps "Open in new tab"
 * @param onCopyLink     called when the user taps "Copy Link"
 * @param onDismiss      called to close the dialog
 */
@Composable
fun LinkContextMenu(
    url: String?,
    isConnected: Boolean,
    onPlayOnTv: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onOpenInBackground: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (url == null) return

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp).width(IntrinsicSize.Max)) {
                Text(
                    text = "Link Options",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                // Play on TV
                if (isConnected) {
                    FilledTonalButton(
                        onClick = { onPlayOnTv(url) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Play on TV")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Open in new tab
                OutlinedButton(
                    onClick = { onOpenInNewTab(url) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open in new tab")
                }
                Spacer(Modifier.height(8.dp))

                // Open in background
                OutlinedButton(
                    onClick = { onOpenInBackground(url) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open in background")
                }
                Spacer(Modifier.height(8.dp))

                // Copy Link
                OutlinedButton(
                    onClick = { onCopyLink(url) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Link")
                }

                Spacer(Modifier.height(8.dp))

                // Cancel
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}
