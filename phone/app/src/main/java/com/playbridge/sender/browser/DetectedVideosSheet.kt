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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDropDown
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
import java.net.URLDecoder
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
    onVideoClick: (DetectedVideo, List<String>?) -> Unit,
    onDownload: (DetectedVideo) -> Unit,
    onClear: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    
    // Separate distinct videos and subtitles
    val playableVideos = remember(videos) { videos.filter { !it.isSubtitle } }
    val allSubtitles = remember(videos) { videos.filter { it.isSubtitle } }
    
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
                    text = "Detected Videos (${playableVideos.size})",
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
            
            if (playableVideos.isEmpty()) {
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
                    items(playableVideos) { video ->
                        // Find subtitles from the same tab
                        val relevantSubtitles = remember(allSubtitles, video.tabId) {
                            allSubtitles.filter { it.tabId == video.tabId }
                        }
                        
                        VideoItemDetailed(
                            video = video,
                            availableSubtitles = relevantSubtitles,
                            onPlayClick = { specificUrl, subtitles -> 
                                if (specificUrl != null) {
                                    // If we have a playlist and this is one of the qualities, try to generate a filtered playlist
                                    // We need to find the specific quality object first
                                    val selectedQuality = video.qualities.find { it.url == specificUrl }
                                    val playlist = video.hlsPlaylist
                                    
                                    if (playlist != null && selectedQuality != null) {
                                        // Generate filtered playlist
                                        val filteredContent = HlsParser.generateFilteredPlaylist(playlist, selectedQuality)
                                        
                                        // Encode as Data URI
                                        val base64Content = android.util.Base64.encodeToString(filteredContent.toByteArray(), android.util.Base64.NO_WRAP)
                                        val dataUri = "data:application/x-mpegurl;base64,$base64Content"
                                        
                                        // Send Data URI with a title hint of the resolution
                                        onVideoClick(video.copy(
                                            url = dataUri,
                                            contentType = "application/x-mpegurl" // Force MIME type
                                        ), subtitles)
                                    } else {
                                        onVideoClick(video.copy(url = specificUrl), subtitles)
                                    }
                                } else {
                                    onVideoClick(video, subtitles)
                                }
                            },
                            onDownloadClick = { onDownload(video) },
                            onCopyClick = {
                                copyToClipboard(context, video.url)
                                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onOpenWithClick = {
                                openInExternalPlayer(context, video.url, video.contentType, video.headers)
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
    availableSubtitles: List<DetectedVideo>,
    onPlayClick: (String?, List<String>?) -> Unit,
    onDownloadClick: () -> Unit,
    onCopyClick: () -> Unit,
    onOpenWithClick: () -> Unit
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
    
    // Subtitle selection state
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var pendingPlayUrl by remember { mutableStateOf<String?>(null) }
    var selectedSubtitles by remember { mutableStateOf(setOf<String>()) }
    
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
    
    // Helper to start play (either directly or via dialog)
    fun initiatePlay(url: String?) {
        if (availableSubtitles.isNotEmpty()) {
            pendingPlayUrl = url
            selectedSubtitles = emptySet() // Reset selection when dialog opens
            showSubtitleDialog = true
        } else {
            onPlayClick(url, null)
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
                                onClick = { initiatePlay(quality.url) },
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
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Download button
                OutlinedButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }

                // Play/Send button
                Button(
                    onClick = { initiatePlay(null) },
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
            
            // Copy URL button (Secondary)
            OutlinedButton(
                onClick = onCopyClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                 Icon(
                    Icons.Default.Share,
                    contentDescription = "Copy URL",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Copy URL")
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            // Open With button (Secondary)
            OutlinedButton(
                onClick = onOpenWithClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                 Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Open With",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Open With")
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
    
    // Subtitle Selection Dialog
    if (showSubtitleDialog) {
        val allSelected = selectedSubtitles.size == availableSubtitles.size && availableSubtitles.isNotEmpty()
        
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            title = { Text("Select Subtitles") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Found ${availableSubtitles.size} subtitle tracks. Select the ones you want to send to TV.")
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Select All Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedSubtitles = if (allSelected) emptySet() else availableSubtitles.map { it.url }.toSet()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                selectedSubtitles = if (checked) availableSubtitles.map { it.url }.toSet() else emptySet()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Select All",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    // List of subtitles
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(availableSubtitles) { subtitle ->
                            var previewText by remember(subtitle.url) { mutableStateOf(subtitle.subtitlePreview) }
                            var isLoadingPreview by remember(subtitle.url) { mutableStateOf(!subtitle.subtitlePreviewChecked) }
                            
                            LaunchedEffect(subtitle.url) {
                                if (!subtitle.subtitlePreviewChecked) {
                                    isLoadingPreview = true
                                    previewText = VideoDetector.fetchSubtitlePreview(subtitle)
                                    isLoadingPreview = false
                                }
                            }
                            
                            val subInfo = parseUrlInfo(subtitle.url)
                            val isSelected = selectedSubtitles.contains(subtitle.url)
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedSubtitles = if (isSelected) {
                                            selectedSubtitles - subtitle.url
                                        } else {
                                            selectedSubtitles + subtitle.url
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedSubtitles = if (checked) {
                                            selectedSubtitles + subtitle.url
                                        } else {
                                            selectedSubtitles - subtitle.url
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = subInfo.extension?.uppercase() ?: "SUB",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = subInfo.filename ?: (subInfo.host + subInfo.path),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (subInfo.filename != null) {
                                         Text(
                                            text = subInfo.host + subInfo.path,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    
                                    if (isLoadingPreview) {
                                        Text(
                                            text = "Loading preview...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                        )
                                    } else if (!previewText.isNullOrEmpty()) {
                                        Text(
                                            text = previewText!!,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.secondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 48.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSubtitleDialog = false
                        onPlayClick(pendingPlayUrl, selectedSubtitles.toList())
                    }
                ) {
                    val countText = if (selectedSubtitles.isNotEmpty()) " (${selectedSubtitles.size})" else ""
                    Text("Play$countText")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
    val extension: String?,
    val filename: String?
)

private fun parseUrlInfo(url: String): UrlInfo {
    return try {
        val uri = URI(url)
        val host = uri.host ?: "Unknown"
        val path = uri.path ?: ""
        val extension = path.substringAfterLast('.', "").takeIf { 
            it.isNotEmpty() && it.length <= 5 && !it.contains('/') 
        }
        val filename = try {
            val name = path.substringAfterLast('/')
            if (name.isNotEmpty()) URLDecoder.decode(name, "UTF-8") else null
        } catch (e: Exception) {
            path.substringAfterLast('/').takeIf { it.isNotEmpty() }
        }
        UrlInfo(host, path, extension, filename)
    } catch (e: Exception) {
        UrlInfo("Unknown", "", null, null)
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

private fun openInExternalPlayer(
    context: Context, 
    url: String, 
    mimeType: String?, 
    headers: Map<String, String>?
) {
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        val uri = android.net.Uri.parse(url)
        
        // standard call
        if (mimeType != null) {
            intent.setDataAndType(uri, mimeType)
        } else {
            intent.setDataAndType(uri, "video/*")
        }
        
        // Add headers for players that support it (VLC, MX Player, Just Player, etc.)
        if (headers != null && headers.isNotEmpty()) {
            val headersArray = headers.map { "${it.key}: ${it.value}" }.toTypedArray()
            intent.putExtra("headers", headersArray)
            
            // Also add individual standard headers as potentially some apps look for them directly
            headers["User-Agent"]?.let { intent.putExtra("User-Agent", it); intent.putExtra("user_agent", it) }
            headers["Cookie"]?.let { intent.putExtra("Cookie", it) }
        }
        
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        
        val chooser = android.content.Intent.createChooser(intent, "Open with")
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to play this video", Toast.LENGTH_SHORT).show()
    }
}
