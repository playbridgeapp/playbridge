package com.playbridge.sender.browser

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import com.playbridge.sender.browser.DownloadManagerSingleton
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import java.io.IOException
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val uri: String?,
    val mediaType: String?,
    val totalSize: Long,
    val bytesDownloaded: Long,
    val lastModified: Long,
    val isExo: Boolean = false,
    val exoState: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlayOnTv: (String, String) -> Unit // url, mimeType
) {
    val context = LocalContext.current
    val downloadManager = remember { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    val exoDownloadManager = remember { DownloadManagerSingleton.getDownloadManager(context) }
    var downloads by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // Deletion state
    var itemToDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Periodically refresh downloads
    LaunchedEffect(Unit) {
        while (true) {
            val systemDownloads = getSystemDownloads(downloadManager)
            val exoDownloads = getExoDownloads(exoDownloadManager)
            downloads = (systemDownloads + exoDownloads).sortedByDescending { it.lastModified }
            delay(1000) // Refresh every 1 second
        }
    }

    if (showDeleteDialog && itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(if (itemToDelete?.status == DownloadManager.STATUS_RUNNING) "Cancel Download" else "Delete Download") },
            text = { Text("Are you sure you want to ${if (itemToDelete?.status == DownloadManager.STATUS_RUNNING) "cancel" else "delete"} '${itemToDelete?.title}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        itemToDelete?.let { item ->
                            if (item.isExo) {
                                item.uri?.let { url ->
                                     exoDownloadManager.removeDownload(url)
                                     Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                downloadManager.remove(item.id)
                                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showDeleteDialog = false
                        itemToDelete = null
                    }
                ) {
                    Text(if (itemToDelete?.status == DownloadManager.STATUS_RUNNING) "Cancel" else "Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Download,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No downloads yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {

                items(downloads) { item ->
                    DownloadItemRow(
                        item = item, 
                        onPlayOnTv = onPlayOnTv,
                        onDelete = {
                            itemToDelete = item
                            showDeleteDialog = true
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun DownloadItemRow(
    item: DownloadItem, 
    onPlayOnTv: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    val progress = if (item.totalSize > 0) item.bytesDownloaded.toFloat() / item.totalSize.toFloat() else 0f
    
    ListItem(
        headlineContent = {
            Text(
                item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                if (item.status == DownloadManager.STATUS_RUNNING) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                    Text("${DownloadUtils.formatFileSize(item.bytesDownloaded)} / ${DownloadUtils.formatFileSize(item.totalSize)}")
                } else if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                     Text(DownloadUtils.formatFileSize(item.totalSize))
                } else if (item.status == DownloadManager.STATUS_FAILED) {
                    Text("Failed", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Pending...")
                }
            }
        },
        leadingContent = {
            val icon = when {
                item.mediaType?.startsWith("video/") == true -> Icons.Default.Movie
                item.mediaType?.contains("zip") == true || item.mediaType?.contains("compressed") == true -> Icons.Default.Archive
                else -> Icons.Default.Description
            }
            Icon(icon, null)
        },
        trailingContent = {
            Row {
                if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (item.mediaType?.startsWith("video/") == true || item.isExo) {
                        IconButton(onClick = { 
                           item.uri?.let { uri ->
                               onPlayOnTv(uri, item.mediaType ?: "video/*") 
                           }
                        }) {
                            Icon(Icons.Default.PlayArrow, "Play on TV", tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(onClick = {}, enabled = false) {
                             Icon(Icons.Default.CheckCircle, "Completed", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (item.status == DownloadManager.STATUS_FAILED) {
                    IconButton(onClick = {}, enabled = false) {
                        Icon(Icons.Default.Error, "Failed", tint = MaterialTheme.colorScheme.error)
                    }
                }
                
                if (item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PENDING) {
                     IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    )
}

fun getSystemDownloads(downloadManager: DownloadManager): List<DownloadItem> {
    val downloads = mutableListOf<DownloadItem>()
    val query = DownloadManager.Query()
    
    try {
        val cursor = downloadManager.query(query)
        cursor.use {
            if (it.moveToFirst()) {
                val idCol = it.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriCol = it.getColumnIndex(DownloadManager.COLUMN_URI) // Remote URI
                val mediaTypeCol = it.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                val totalSizeCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val downloadedCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val lastModCol = it.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                
                do {
                    val id = it.getLong(idCol)
                    val title = it.getString(titleCol) ?: "Unknown"
                    val status = it.getInt(statusCol)
                    val uri = it.getString(uriCol)
                    val mediaType = it.getString(mediaTypeCol)
                    val totalSize = it.getLong(totalSizeCol)
                    val bytesDownloaded = it.getLong(downloadedCol)
                    val lastModified = it.getLong(lastModCol)
                    
                    downloads.add(DownloadItem(id, title, status, uri, mediaType, totalSize, bytesDownloaded, lastModified))
                } while (it.moveToNext())
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return downloads
}

fun getExoDownloads(downloadManager: androidx.media3.exoplayer.offline.DownloadManager): List<DownloadItem> {
    val downloads = mutableListOf<DownloadItem>()
    try {
        val cursor: DownloadCursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            val download = cursor.download
            val id = download.request.id.hashCode().toLong() // Use hash of URL as ID
            val title = String(download.request.data) // We stored filename in data
            
            val status = when (download.state) {
                Download.STATE_COMPLETED -> DownloadManager.STATUS_SUCCESSFUL
                Download.STATE_FAILED -> DownloadManager.STATUS_FAILED
                Download.STATE_DOWNLOADING -> DownloadManager.STATUS_RUNNING
                Download.STATE_QUEUED -> DownloadManager.STATUS_PENDING
                Download.STATE_STOPPED -> DownloadManager.STATUS_PAUSED
                else -> DownloadManager.STATUS_PENDING
            }
            
            val uri = download.request.uri.toString()
            val mediaType = "application/x-mpegurl" // Assumed HLS
            val totalSize = download.contentLength
            val bytesDownloaded = download.bytesDownloaded
            val lastModified = download.updateTimeMs
            
            downloads.add(DownloadItem(
                id = id,
                title = title.ifEmpty { "HLS Video" },
                status = status,
                uri = uri,
                mediaType = mediaType,
                totalSize = if (totalSize == -1L) 0L else totalSize,
                bytesDownloaded = bytesDownloaded,
                lastModified = lastModified,
                isExo = true,
                exoState = download.state
            ))
        }
        cursor.close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return downloads
}
