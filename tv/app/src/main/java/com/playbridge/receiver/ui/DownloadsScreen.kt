package com.playbridge.receiver.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import java.io.File

data class DownloadItem(
    val id: Long,
    val title: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: Int,
    val localUri: String?
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var downloads by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
    val dm = remember { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }

    LaunchedEffect(Unit) {
        while (true) {
            val query = DownloadManager.Query()
            val cursor = dm.query(query)
            val newList = mutableListOf<DownloadItem>()

            if (cursor != null && cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val totalBytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val downloadedBytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                do {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown"
                    val totalBytes = cursor.getLong(totalBytesCol)
                    val downloadedBytes = cursor.getLong(downloadedBytesCol)
                    val status = cursor.getInt(statusCol)
                    val localUri = cursor.getString(uriCol)

                    newList.add(
                        DownloadItem(
                            id = id,
                            title = title,
                            totalBytes = totalBytes,
                            downloadedBytes = downloadedBytes,
                            status = status,
                            localUri = localUri
                        )
                    )
                } while (cursor.moveToNext())
            }
            cursor?.close()
            downloads = newList
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23))
            .padding(48.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White
                )

                Button(onClick = onBack) {
                    Text("Back")
                }
            }

            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No downloads found.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(downloads) { item ->
                        DownloadItemCard(
                            item = item,
                            onOpen = {
                                openDownloadedFile(context, item)
                            },
                            onDelete = {
                                dm.remove(item.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DownloadItemCard(
    item: DownloadItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        scale = CardDefaults.scale(focusedScale = 1.05f),
        onClick = {
            if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                onOpen()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray.copy(alpha = 0.3f))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val statusStr = when (item.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> "Completed"
                    DownloadManager.STATUS_FAILED -> "Failed"
                    DownloadManager.STATUS_PAUSED -> "Paused"
                    DownloadManager.STATUS_PENDING -> "Pending"
                    DownloadManager.STATUS_RUNNING -> "Downloading..."
                    else -> "Unknown"
                }

                Text(
                    text = "$statusStr - ${formatBytes(item.downloadedBytes)} / ${if (item.totalBytes > 0) formatBytes(item.totalBytes) else "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Open", tint = Color.White)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Red)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.1f GB", gb)
}

private fun openDownloadedFile(context: Context, item: DownloadItem) {
    if (item.localUri == null) return
    try {
        val uri = Uri.parse(item.localUri)
        val file = File(uri.path!!)

        val intent = Intent(Intent.ACTION_VIEW)

        // For Android 7.0+ we must use FileProvider for file:// URIs
        val finalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && uri.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            uri
        }

        // We can optionally use DownloadManager.getMimeTypeForDownloadedFile(item.id)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val mimeType = dm.getMimeTypeForDownloadedFile(item.id) ?: "*/*"

        intent.setDataAndType(finalUri, mimeType)
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("DownloadsScreen", "Error opening file", e)
    }
}
