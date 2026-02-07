package com.playbridge.sender.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

/**
 * Bottom sheet displaying detected video sources with detailed info like 1DM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectedVideosSheet(
    videos: List<DetectedVideo>,
    onDismiss: () -> Unit,
    onVideoClick: (DetectedVideo) -> Unit,
    onClear: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detected Videos (${videos.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (videos.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (videos.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No videos detected yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Browse a page with video content",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                // Video list
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(videos) { video ->
                        VideoItemDetailed(
                            video = video,
                            onPlayClick = { specificUrl -> 
                                if (specificUrl != null) {
                                    onVideoClick(video.copy(url = specificUrl))
                                } else {
                                    onVideoClick(video)
                                }
                            },
                            onCopyClick = {
                                copyToClipboard(context, video.url)
                                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoItemDetailed(
    video: DetectedVideo,
    onPlayClick: (String?) -> Unit,
    onCopyClick: () -> Unit
) {
    val urlInfo = remember(video.url) { parseUrlInfo(video.url) }
    val videoType = remember(video) { getVideoType(video) }
    val timeString = remember(video.timestamp) { formatTimestamp(video.timestamp) }
    
    // File size state - fetch asynchronously
    var fileSize by remember { mutableStateOf(video.fileSize) }
    var isLoadingSize by remember { mutableStateOf(!video.fileSizeChecked) }
    var showRaw by remember { mutableStateOf(false) }

    // HLS Qualities state
    val isHls = remember(videoType) { videoType == "HLS" }
    var qualities by remember { mutableStateOf(video.qualities) }
    var isLoadingQualities by remember { mutableStateOf(isHls && !video.qualitiesChecked) }
    
    LaunchedEffect(video.url) {
        if (!video.fileSizeChecked) {
            isLoadingSize = true
            fileSize = VideoDetector.fetchFileSize(video)
            isLoadingSize = false
        }
        
        if (isHls && !video.qualitiesChecked) {
            isLoadingQualities = true
            qualities = VideoDetector.fetchHlsQualities(video)
            isLoadingQualities = false
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Top row: Type badge and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Video type chip
                Surface(
                    color = getTypeColor(videoType).copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = getTypeIcon(videoType),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = getTypeColor(videoType)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = videoType,
                            style = MaterialTheme.typography.labelSmall,
                            color = getTypeColor(videoType),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // Time detected
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // URL domain/host
            Text(
                text = urlInfo.host,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Full URL (truncated)
            Text(
                text = video.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Additional info row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Content type
                if (video.contentType != null) {
                    InfoChip(
                        icon = Icons.Default.Info,
                        text = video.contentType
                    )
                }
                
                // File extension if present
                urlInfo.extension?.let { ext ->
                    InfoChip(
                        icon = Icons.Default.Info,
                        text = ext.uppercase()
                    )
                }
                
                // Detection method
                InfoChip(
                    icon = Icons.Default.Search,
                    text = video.detectedBy ?: "auto"
                )
                
                // File size
                if (isLoadingSize) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.dp
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Checking...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else if (fileSize != null) {
                    InfoChip(
                        icon = Icons.Default.ArrowDropDown,
                        text = formatFileSize(fileSize!!)
                    )
                }
            }
            
            // Raw JSON viewer
            AnimatedVisibility(visible = showRaw && video.originalMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Raw Message:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = video.originalMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Qualities List
            if (isHls) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Qualities:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                
                if (isLoadingQualities) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Parsing HLS playlist...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (qualities.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(qualities) { quality ->
                            AssistChip(
                                onClick = { onPlayClick(quality.url) },
                                label = { 
                                    Text(
                                        text = quality.resolution,
                                        style = MaterialTheme.typography.labelMedium
                                    ) 
                                },
                                leadingIcon = {
                                     Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(12.dp))
                                }
                            )
                        }
                    }
                } else {
                     Spacer(modifier = Modifier.height(4.dp))
                     Text(
                        text = "No variants found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Action buttons
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Copy URL button
                OutlinedButton(
                    onClick = onCopyClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Copy URL",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy URL")
                }
                
                // Play/Send button
                Button(
                    onClick = { onPlayClick(null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Play on TV",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Play on TV")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Show Raw button
            if (video.originalMessage != null) {
                TextButton(
                    onClick = { showRaw = !showRaw },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showRaw) "Hide Raw Data" else "Show Raw Data")
                }
            }
        }
    }
}

@Composable
private fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class UrlInfo(
    val host: String,
    val path: String,
    val extension: String?
)

private fun parseUrlInfo(url: String): UrlInfo {
    return try {
        val uri = URI(url)
        val host = uri.host ?: "Unknown"
        val path = uri.path ?: ""
        val extension = path.substringAfterLast('.', "").takeIf { 
            it.isNotEmpty() && it.length <= 5 && !it.contains('/') 
        }
        UrlInfo(host, path, extension)
    } catch (e: Exception) {
        UrlInfo("Unknown", "", null)
    }
}

private fun getVideoType(video: DetectedVideo): String {
    val url = video.url.lowercase()
    return when {
        url.contains(".m3u8") -> "HLS"
        url.contains(".mpd") -> "DASH"
        url.contains(".mp4") -> "MP4"
        url.contains(".webm") -> "WebM"
        url.contains(".mkv") -> "MKV"
        url.contains("googlevideo.com") -> "YouTube"
        video.contentType?.contains("mpegurl") == true -> "HLS"
        video.contentType?.contains("dash") == true -> "DASH"
        video.contentType?.contains("mp4") == true -> "MP4"
        else -> "Video"
    }
}

@Composable
private fun getTypeColor(type: String): androidx.compose.ui.graphics.Color {
    return when (type) {
        "HLS" -> MaterialTheme.colorScheme.primary
        "DASH" -> MaterialTheme.colorScheme.secondary
        "YouTube" -> MaterialTheme.colorScheme.error
        "MP4" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
}

private fun getTypeIcon(type: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        "HLS", "DASH" -> Icons.Default.Settings
        "YouTube" -> Icons.Default.PlayArrow
        else -> Icons.Default.PlayArrow
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return try {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (e: Exception) {
        ""
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Video URL", text)
    clipboard.setPrimaryClip(clip)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
