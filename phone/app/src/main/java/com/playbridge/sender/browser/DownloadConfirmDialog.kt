package com.playbridge.sender.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

/** State for a blocked popup awaiting user decision. */
data class PendingPopup(val openerHost: String, val popupUrl: String)

/** State for a pending download awaiting user confirmation. */
data class PendingDownload(
    val url: String,
    val fileName: String? = null,
    val contentType: String? = null,
    val userAgent: String? = null,
    val cookie: String? = null,
    val referer: String? = null
)

/**
 * Confirmation dialog shown when a non-extension download is intercepted.
 *
 * @param pendingDownload  the download details, or null to hide the dialog
 * @param onConfirm        called with the download when the user taps "Download"
 * @param onDismiss        called when the dialog is cancelled
 */
@Composable
fun DownloadConfirmDialog(
    pendingDownload: PendingDownload?,
    onConfirm: (PendingDownload) -> Unit,
    onPlayOnTv: (PendingDownload) -> Unit,
    onDismiss: () -> Unit
) {
    if (pendingDownload == null) return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download file?") },
        text = {
            Column {
                Text("Do you want to download this file?")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = pendingDownload.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                pendingDownload.fileName?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("File: $it", style = MaterialTheme.typography.bodySmall)
                }
                pendingDownload.contentType?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Type: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { onPlayOnTv(pendingDownload) }) {
                    Text("Play on TV")
                }
                TextButton(onClick = { onConfirm(pendingDownload) }) {
                    Text("Download")
                }
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Download URL", pendingDownload.url)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy URL")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
