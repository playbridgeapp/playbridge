package com.playbridge.sender.cast

import com.playbridge.sender.downloads.DownloadUtils
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.launch
import com.playbridge.sender.data.library.TmdbRepository
import com.playbridge.sender.data.library.StremioSubtitleService
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.theme.PlayBridgeTheme

/**
 * A chip-shaped dropdown trigger that expands to show options as chips.
 * The trigger renders as a [FilterChip]; the popup renders each option as a [FilterChip]
 * (selected option appears filled/highlighted).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChipDropdown(
    selectedLabel: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    chipLabelColor: Color = Color.Unspecified,
    themeColor: Color = Color.Unspecified,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    toggleAction: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    val isHighlighted = chipLabelColor != Color.Unspecified

    val resolvedLabelColor = when {
        isHighlighted -> chipLabelColor
        themeColor != Color.Unspecified -> Color.White.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.75f)
    }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.combinedClickable(
                onClick = {
                    if (toggleAction != null) {
                        toggleAction()
                    } else {
                        expanded = true
                    }
                },
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (toggleAction != null) {
                        expanded = true
                    } else {
                        onLongClick?.invoke()
                    }
                }
            ),
            shape = RoundedCornerShape(50),
            color = when {
                isHighlighted -> chipLabelColor.copy(alpha = 0.15f)
                themeColor != Color.Unspecified -> themeColor.copy(alpha = 0.12f)
                else -> Color.White.copy(alpha = 0.08f)
            },
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                when {
                    isHighlighted -> chipLabelColor.copy(alpha = 0.5f)
                    themeColor != Color.Unspecified -> themeColor.copy(alpha = 0.4f)
                    else -> Color.White.copy(alpha = 0.2f)
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                }
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = resolvedLabelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (trailingIcon != null) {
                    trailingIcon()
                } else {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = resolvedLabelColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
        DropdownMenu( 
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = if (themeColor != Color.Unspecified)
                lerp(themeColor, Color.Black, 0.8f).copy(alpha = 0.95f)
            else
                Color(0xFF202020),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.border(
                1.dp,
                if (themeColor != Color.Unspecified) themeColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(12.dp)
            )
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selectedValue
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f)
                        )
                    },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (themeColor != Color.Unspecified) themeColor else Color(0xFF4CAF50)
                            )
                        }
                    } else null,
                    modifier = Modifier.background(
                        if (isSelected) (if (themeColor != Color.Unspecified) themeColor else Color.White).copy(alpha = 0.12f)
                        else Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
internal fun VideoItemDetailed(
    video: DetectedVideo,
    isSelected: Boolean,
    selectedQualityUrl: String?,
    onClick: () -> Unit,
    onQualityClick: (String) -> Unit,
    onDownloadClick: () -> Unit,
    onCopyClick: () -> Unit,
    onOpenWithClick: () -> Unit,
    onPreviewClick: () -> Unit,
    onPlayPhoneClick: () -> Unit
) {
    val urlInfo = remember(video.url) { parseUrlInfo(video.url) }
    val videoType = remember(video) { getVideoType(video) }
    val timeString = remember(video.timestamp) { formatTimestamp(video.timestamp) }

    // File size state - fetch asynchronously
    var fileSize by remember { mutableStateOf(video.fileSize) }
    var isLoadingSize by remember { mutableStateOf(!video.fileSizeChecked) }
    var showRawDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // HLS Qualities state
    val isHls = remember(videoType) { videoType == "HLS" }
    val isStream = remember(videoType) { videoType == "HLS" || videoType == "DASH" }
    var qualities by remember { mutableStateOf(video.qualities) }
    var isLoadingQualities by remember { mutableStateOf(isHls && !video.qualitiesChecked) }

    // Thumbnail — pre-seed from cache so there's no loading flash if BrowserActivity
    // already completed the background fetch before the sheet was opened.
    var thumbnail by remember(video.url) {
        mutableStateOf(VideoDetector.getCachedThumbnail(video.url))
    }
    var thumbnailFailed by remember { mutableStateOf(false) }

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

    // Thumbnail fetch — fast no-op if already cached; only re-tries on cache miss
    LaunchedEffect(video.url) {
        if (thumbnail != null) return@LaunchedEffect
        val bmp = VideoDetector.fetchThumbnail(video)
        if (bmp != null) thumbnail = bmp else thumbnailFailed = true
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
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

            // Thumbnail — always shown for all video types
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                when {
                    thumbnail != null -> Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = "Video preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    thumbnailFailed -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Preview unavailable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    else -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Content Information
            if (video.playlistPayload != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${video.playlistPayload.size} Items in Playlist",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            } else {
                // Title / Filename
                Text(
                    text = video.title ?: urlInfo.filename ?: urlInfo.host,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Full URL (truncated)
                Text(
                    text = video.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
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
                        text = video.detectedBy
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
                            text = DownloadUtils.formatFileSize(fileSize!!)
                        )
                    }
                }
            }


            // Qualities List
            if (isHls) {
                Spacer(modifier = Modifier.height(8.dp))
                Spacer(modifier = Modifier.height(12.dp))
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
                            val isQualitySelected = isSelected && selectedQualityUrl == quality.url
                            FilterChip(
                                selected = isQualitySelected,
                                onClick = { onQualityClick(quality.url) },
                                label = {
                                    Text(
                                        text = quality.resolution,
                                        style = MaterialTheme.typography.labelMedium
                                    )
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

            if (video.playlistPayload == null) {
                // Compact Action Row
                Spacer(modifier = Modifier.height(16.dp))
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPreviewClick) {
                        Icon(
                            Icons.Default.Visibility,
                            contentDescription = "Preview",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onPlayPhoneClick) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play on Phone",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDownloadClick) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More Options",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open with...") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.OpenInNew,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    onOpenWithClick()
                                    menuExpanded = false
                                }
                            )
                            if (video.originalMessage != null) {
                                DropdownMenuItem(
                                    text = { Text("View raw message") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Code,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showRawDialog = true
                                        menuExpanded = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Copy video URL") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    onCopyClick()
                                    menuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRawDialog && video.originalMessage != null) {
        val prettyJson = remember(video.originalMessage) {
            try {
                val trimmed = video.originalMessage.trim()
                if (trimmed.startsWith("{")) {
                    org.json.JSONObject(video.originalMessage).toString(4)
                } else if (trimmed.startsWith("[")) {
                    org.json.JSONArray(video.originalMessage).toString(4)
                } else {
                    video.originalMessage
                }
            } catch (e: Exception) {
                video.originalMessage
            }
        }

        AlertDialog(
            onDismissRequest = { showRawDialog = false },
            title = { Text("Raw Message") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    ) {
                        SelectionContainer {
                            Text(
                                text = prettyJson,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRawDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun InfoChip(
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

data class UrlInfo(
    val host: String,
    val path: String,
    val extension: String?,
    val filename: String?
)

fun parseUrlInfo(url: String): UrlInfo {
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

fun getVideoType(video: DetectedVideo): String {
    if (video.playlistPayload != null) return "Playlist"
    val url = video.url.lowercase()
    return when {
        url.contains(".m3u8") -> "HLS"
        url.contains(".mpd") -> "DASH"
        url.contains(".mp4") || url.contains(".m4v") -> "MP4"
        url.contains(".webm") -> "WebM"
        url.contains(".mkv") -> "MKV"
        url.contains(".wmv") -> "WMV"
        url.contains(".avi") -> "AVI"
        url.contains(".flv") -> "FLV"
        url.contains(".mov") -> "MOV"
        url.contains(".3gp") -> "3GP"
        url.contains("googlevideo.com") -> "YouTube"
        video.contentType?.contains("mpegurl") == true -> "HLS"
        video.contentType?.contains("dash") == true -> "DASH"
        video.contentType?.contains("mp4") == true -> "MP4"
        else -> "Video"
    }
}

@Composable
fun getTypeColor(type: String): androidx.compose.ui.graphics.Color {
    return when (type) {
        "HLS" -> MaterialTheme.colorScheme.primary
        "DASH" -> MaterialTheme.colorScheme.secondary
        "Playlist" -> MaterialTheme.colorScheme.secondary
        "YouTube" -> MaterialTheme.colorScheme.error
        "MP4", "MKV", "WebM", "WMV", "AVI", "FLV", "MOV", "3GP" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
}

fun getTypeIcon(type: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        "HLS", "DASH" -> Icons.Default.Settings
        "Playlist" -> Icons.Default.List
        "YouTube" -> Icons.Default.PlayArrow
        else -> Icons.Default.PlayArrow
    }
}

fun formatTimestamp(timestamp: Long): String {
    return try {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (e: Exception) {
        ""
    }
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Video URL", text)
    clipboard.setPrimaryClip(clip)
}


fun openInExternalPlayer(
    context: Context,
    url: String,
    mimeType: String?,
    headers: Map<String, String>?,
    title: String? = null
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

        // Add headers for players that support it (VLC, MX Player, Just Player, MPV, etc.)
        if (headers != null && headers.isNotEmpty()) {
            val headersArray = headers.map { "${it.key}: ${it.value}" }.toTypedArray()
            intent.putExtra("headers", headersArray)

            // Also add individual standard headers as potentially some apps look for them directly
            headers["User-Agent"]?.let { intent.putExtra("User-Agent", it); intent.putExtra("user_agent", it) }
            headers["Cookie"]?.let { intent.putExtra("Cookie", it) }
            headers["Referer"]?.let { intent.putExtra("Referer", it); intent.putExtra("referer", it) }

            // Support for mpv-android and forks like MPVEX (comma-separated string under "http-header-fields")
            val mpvHeadersString = headers.map { "${it.key}: ${it.value}" }.joinToString(",")
            intent.putExtra("http-header-fields", mpvHeadersString)
        }

        // Pass title to player if available
        if (title != null) {
            intent.putExtra(android.content.Intent.EXTRA_TITLE, title)
            intent.putExtra("title", title)
        }

        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val chooser = android.content.Intent.createChooser(intent, "Open with")
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to play this video", Toast.LENGTH_SHORT).show()
    }
}

/**
 * UI state for [SubtitleSearchDialog]. Held by the parent (CastSheet) so query/results
 * persist across dialog open/close, matching the original inline behaviour.
 */
internal class SubtitleSearchUiState {
    var query by mutableStateOf("")
    var isSearching by mutableStateOf(false)
    var results by mutableStateOf<List<com.playbridge.sender.data.library.TmdbMultiSearchResult>>(emptyList())
    var showResults by mutableStateOf(false)
}

/**
 * Dialog that searches TMDB for a title and pulls matching Stremio subtitles into the sheet.
 * Extracted verbatim from the CastSheet body; [onAddSubtitles] appends to the sheet's
 * `extraSubtitles`, [onDismiss] closes the dialog (the gate stays in the parent).
 */
@Composable
internal fun SubtitleSearchDialog(
    state: SubtitleSearchUiState,
    tmdbRepository: TmdbRepository,
    subtitleService: StremioSubtitleService,
    onAddSubtitles: (List<DetectedVideo>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    PlayBridgeTheme {
        AlertDialog(
            onDismissRequest = {
                state.showResults = false
                state.results = emptyList()
                onDismiss()
            },
            title = { Text("Search Subtitles") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { state.query = it },
                        label = { Text("Movie / TV Show Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (state.isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = {
                                    if (state.query.isNotBlank()) {
                                        state.isSearching = true
                                        state.showResults = true
                                        scope.launch {
                                            val response = tmdbRepository.searchMulti(state.query)
                                            state.results = response.results.filter { it.isMovie || it.isTvShow }
                                            state.isSearching = false
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (state.showResults) {
                        LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                            items(state.results) { item ->
                                ListItem(
                                    headlineContent = { Text(item.displayTitle) },
                                    supportingContent = { Text("${item.mediaType.uppercase()} • ${item.year}") },
                                    modifier = Modifier.clickable {
                                        state.isSearching = true
                                        scope.launch {
                                            var imdbIdToUse: String? = null
                                            if (item.isMovie) {
                                                val details = tmdbRepository.getMovieDetails(item.id)
                                                imdbIdToUse = details?.imdbId
                                            } else {
                                                val details = tmdbRepository.getTvDetails(item.id)
                                                imdbIdToUse = details?.imdbId
                                            }

                                            if (imdbIdToUse != null) {
                                                val streams = if (item.isMovie) {
                                                    subtitleService.getSubtitlesForMovie(imdbIdToUse)
                                                } else {
                                                    // For series, just try season 1 episode 1 by default, or we'd need more UI.
                                                    // For now, let's just query S01E01 if it's a TV show.
                                                    subtitleService.getSubtitlesForEpisode(imdbIdToUse, 1, 1)
                                                }

                                                if (streams.isNotEmpty()) {
                                                    val newSubs = streams.mapNotNull { stream ->
                                                        stream.url?.let { url ->
                                                            DetectedVideo(
                                                                url = url,
                                                                tabId = -1, // Global
                                                                timestamp = System.currentTimeMillis(),
                                                                contentType = "text/vtt",
                                                                detectedBy = "stremio_addon"
                                                            )
                                                        }
                                                    }
                                                    onAddSubtitles(newSubs)
                                                    Toast.makeText(context, "Added ${newSubs.size} subtitles", Toast.LENGTH_SHORT).show()
                                                    onDismiss()
                                                } else {
                                                    Toast.makeText(context, "No subtitles found", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Could not find IMDB ID", Toast.LENGTH_SHORT).show()
                                            }
                                            state.isSearching = false
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    state.showResults = false
                    state.results = emptyList()
                    onDismiss()
                }) {
                    Text("Close")
                }
            }
        )
    }
}
