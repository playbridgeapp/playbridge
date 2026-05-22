package com.playbridge.sender.browser

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
import androidx.compose.foundation.Image
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

/**
 * Bottom sheet for casting media to a TV — pick a video or browse URL, choose a device and player, send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(
    videos: List<DetectedVideo>,
    onDismiss: () -> Unit,
    onVideoClick: (DetectedVideo, List<String>?) -> Unit,
    onDownload: (DetectedVideo) -> Unit,
    onClear: () -> Unit,
    playerMode: String = "tv",
    onPlayerModeChange: (String) -> Unit = {},
    availableTvDevices: List<TvDevice> = emptyList(),
    selectedTvDevice: TvDevice? = null,
    onTvChange: (TvDevice) -> Unit = {},
    tvConnectionState: Boolean? = null,  // true = connected, false = error, null = neutral
    browseUrl: String = "",
    onBrowseClick: ((String, Boolean) -> Unit)? = null,
    onOpenNewTab: ((String) -> Unit)? = null,
    initialMode: String = "play",
    mediaflowProxyUrl: String = "",
    mediaflowProxyPassword: String = "",
    mediaflowAutoSelect: Boolean = true,
    subtitleService: StremioSubtitleService = StremioSubtitleService(),
    contentPayload: playbridge.PlayPayload? = null,
    onContentClick: (playbridge.PlayPayload) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    // Separate distinct videos and subtitles, and sort videos by priority
    val playableVideos = remember(videos) {
        videos.filter { !it.isSubtitle }
              .sortedWith(
                  compareByDescending<DetectedVideo> { video ->
                      val hasMaster = video.url.contains("master", ignoreCase = true)
                      val base = when {
                          // Score 5: HLS with multiple variants (Master Playlist)
                          video.hlsPlaylist?.videoQualities?.isNotEmpty() == true -> 5
                          // Score 4: Playable stream (HLS/DASH)
                          video.isPlayable == true && (video.url.contains(".m3u8", ignoreCase = true) || video.url.contains(".mpd", ignoreCase = true)) -> 4
                          // Score 1: Verified unplayable (dead link, 403, etc)
                          video.isPlayable == false -> 1
                          // Score 2: Normal video — unchecked or confirmed playable both rank equally,
                          // so timestamp (thenByDescending below) determines order: newest first.
                          else -> 2
                      }
                      if (hasMaster) base + 1 else base
                  }.thenByDescending { it.timestamp }
              )
    }

    var sheetMode by remember(playableVideos, contentPayload) {
        mutableStateOf(
            if (playableVideos.isEmpty() && contentPayload == null) "browse" else initialMode
        )
    }

    // If we have content metadata but no browser-detected videos, ensure we stay in play mode
    // (since the video URL will come from the contentPayload/library resource).
    LaunchedEffect(contentPayload) {
        if (contentPayload != null && playableVideos.isEmpty()) {
            sheetMode = "play"
        }
    }
    val allSubtitles = remember(videos) { videos.filter { it.isSubtitle } }

    val isPlaylistMode = remember(playableVideos) {
        playableVideos.firstOrNull()?.playlistPayload != null
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = if (isPlaylistMode) listOf("Playlist Bundle") else listOf("Videos", "Subtitles")

    // State for subtitle search dialog
    var showSubtitleSearchDialog by remember { mutableStateOf(false) }
    var subtitleSearchQuery by remember { mutableStateOf("") }
    var isSearchingSubtitles by remember { mutableStateOf(false) }
    var subtitleSearchResults by remember { mutableStateOf<List<com.playbridge.sender.data.library.TmdbMultiSearchResult>>(emptyList()) }
    var extraSubtitles by remember { mutableStateOf<List<DetectedVideo>>(emptyList()) }
    var showSubtitleResults by remember { mutableStateOf(false) }

    val tmdbRepository = remember { TmdbRepository(context) }
    val scope = rememberCoroutineScope()

    // Global selection state
    var selectedVideo by remember { mutableStateOf<DetectedVideo?>(playableVideos.firstOrNull()) }
    var selectedQualityUrl by remember { mutableStateOf<String?>(null) }
    var selectedSubtitles by remember { mutableStateOf<Set<String>>(emptySet()) }

    // In-app preview state
    var previewVideo by remember { mutableStateOf<DetectedVideo?>(null) }

    // Browse-mode desktop/mobile toggle
    var browseDesktopMode by remember { mutableStateOf(false) }

    // Proxy mode — only relevant in play mode, only shown when proxy is configured
    val proxyAvailable = mediaflowProxyUrl.isNotBlank()
    var proxyMode by remember { mutableStateOf(MediaflowProxy.Mode.OFF) }

    // Auto-select proxy mode whenever the selected video changes (not on proxyMode changes,
    // which would cause a feedback loop). Only fires when auto-select is enabled AND the
    // proxy is configured. The user can still override the chip manually afterward.
    LaunchedEffect(selectedVideo?.url) {
        if (proxyAvailable && mediaflowAutoSelect && selectedVideo != null) {
            val suggested = MediaflowProxy.autoSelect(selectedVideo!!)
            if (suggested != MediaflowProxy.Mode.OFF) {
                proxyMode = suggested
            }
        }
    }

    /** Rewrite [video] URL through mediaflow-proxy if a mode is selected. */
    fun applyProxy(video: DetectedVideo): DetectedVideo {
        if (proxyMode == MediaflowProxy.Mode.OFF || !proxyAvailable) return video

        // Use the same filtered header set that BrowserActivity would send to the TV:
        //   - strips browser-context headers (Sec-Fetch-*, Sec-CH-UA-*, etc.) that CDNs
        //     reject when they arrive from a different origin (the proxy server)
        //   - ensures a sane User-Agent is always present
        val proxyHeaders = VideoDetector.mediaHeaders(video).also { headers ->
            // Apply the same originUrl → Referer fallback that BrowserActivity uses.
            // Without this, CDNs that require a Referer would reject the proxy request
            // because this fallback normally runs after applyProxy() returns.
            if (!video.originUrl.isNullOrEmpty() &&
                headers.keys.none { it.equals("Referer", ignoreCase = true) }
            ) {
                headers["Referer"] = video.originUrl
            }
        }

        val result = MediaflowProxy.rewrite(
            mode = proxyMode,
            proxyBase = mediaflowProxyUrl,
            password = mediaflowProxyPassword,
            sourceUrl = video.url,
            headers = proxyHeaders,
        )
        // Headers are encoded into the proxy URL — clear them so the TV doesn't re-send them.
        return video.copy(
            url = result.url,
            contentType = result.contentType ?: video.contentType,
            headers = if (result.url != video.url) null else video.headers,
        )
    }

    PlayBridgeTheme {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = 80.dp) // Provide padding so FAB doesn't cover content
            ) {
                // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = true,
                    onClick = { sheetMode = if (sheetMode == "play") "browse" else "play" },
                    label = {
                        Text(
                            text = if (sheetMode == "play") "Play" else "Browse",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (sheetMode == "play") Icons.Default.PlayArrow else Icons.Default.Language,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Switch mode",
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val showTvDropdownHeader = availableTvDevices.size > 1 || (availableTvDevices.size == 1 && selectedTvDevice == null)
                    if (showTvDropdownHeader) {
                        val tvLabelColorHeader = when (tvConnectionState) {
                            true  -> Color(0xFF4CAF50)
                            false -> MaterialTheme.colorScheme.error
                            null  -> Color.Unspecified
                        }
                        ChipDropdown(
                            selectedLabel = selectedTvDevice?.name ?: "None",
                            options = availableTvDevices.map { it.uuid to it.name },
                            selectedValue = selectedTvDevice?.uuid ?: "",
                            onSelect = { uuid ->
                                availableTvDevices.find { it.uuid == uuid }?.let { onTvChange(it) }
                            },
                            chipLabelColor = tvLabelColorHeader
                        )
                    }
                    if (sheetMode == "play" && videos.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (sheetMode == "browse") {
                        IconButton(
                            onClick = { onBrowseClick?.invoke(playerMode, browseDesktopMode) }
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Browse",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (contentPayload != null && selectedVideo == null) {
                                    onContentClick(contentPayload)
                                    return@IconButton
                                }

                                val specificUrl = selectedQualityUrl
                                if (specificUrl != null) {
                                    val selectedQuality = selectedVideo!!.qualities.find { it.url == specificUrl }
                                    val playlist = selectedVideo!!.hlsPlaylist

                                    if (playlist != null && selectedQuality != null) {
                                        // Generate filtered playlist (data: URI — applyProxy skips these)
                                        val filteredContent = HlsParser.generateFilteredPlaylist(playlist, selectedQuality)
                                        val base64Content = android.util.Base64.encodeToString(filteredContent.toByteArray(), android.util.Base64.NO_WRAP)
                                        val dataUri = "data:application/x-mpegurl;base64,$base64Content"

                                        onVideoClick(applyProxy(selectedVideo!!.copy(
                                            url = dataUri,
                                            contentType = "application/x-mpegurl"
                                        )), selectedSubtitles.toList())
                                    } else {
                                        onVideoClick(applyProxy(selectedVideo!!.copy(url = specificUrl)), selectedSubtitles.toList())
                                    }
                                } else {
                                    onVideoClick(applyProxy(selectedVideo!!), selectedSubtitles.toList())
                                }
                            },
                            enabled = selectedVideo != null || contentPayload != null
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "Play",
                                tint = if (selectedVideo != null || contentPayload != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            }

            // Compact player + TV selectors side by side
            val playerOptions = if (sheetMode == "browse") {
                listOf(
                    "tv"      to "TV Default",
                    "webview" to "System WebView",
                    "gecko"   to "GeckoView"
                )
            } else {
                listOf(
                    "tv"           to "TV Default",
                    "internal"     to "ExoPlayer",
                    "internal_vlc" to "LibVLC",
                    "internal_mpv" to "MPV",
                    "external"     to "External",
                    "external_mpv" to "Ext. MPV"
                )
            }
            val selectedPlayerLabel = playerOptions.find { it.first == playerMode }?.second ?: "TV Default"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChipDropdown(
                    selectedLabel = selectedPlayerLabel,
                    options = playerOptions,
                    selectedValue = playerMode,
                    onSelect = onPlayerModeChange
                )
                if (sheetMode == "play" && proxyAvailable) {
                    ChipDropdown(
                        selectedLabel = proxyMode.label,
                        options = MediaflowProxy.Mode.entries.map { it.name to it.label },
                        selectedValue = proxyMode.name,
                        onSelect = { value -> proxyMode = MediaflowProxy.Mode.valueOf(value) }
                    )
                }
                if (sheetMode == "browse") {
                    FilterChip(
                        selected = browseDesktopMode,
                        onClick = { browseDesktopMode = !browseDesktopMode },
                        label = {
                            Text(
                                text = if (browseDesktopMode) "Desktop" else "Mobile",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = if (browseDesktopMode) {
                            {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        } else null,
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = "Switch mode",
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }
            }

            if (sheetMode == "browse") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "URL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = browseUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onOpenNewTab != null) {
                        AssistChip(
                            onClick = { onOpenNewTab.invoke(browseUrl) },
                            label = { Text("New Tab") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Tab,
                                    contentDescription = "Open in new tab",
                                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                                )
                            }
                        )
                    }
                    AssistChip(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(browseUrl)
                                )
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        label = { Text("External Browser") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "Open in external browser",
                                modifier = Modifier.size(AssistChipDefaults.IconSize)
                            )
                        }
                    )
                }
            } else {

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Detected media",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    val count = if (isPlaylistMode) {
                        playableVideos.firstOrNull()?.playlistPayload?.size ?: 0
                    } else {
                        if (index == 0) playableVideos.size else (allSubtitles.size + extraSubtitles.size)
                    }

                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            if (isPlaylistMode) {
                                Text(title)
                            } else {
                                Text("$title ($count)")
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 0) {
                if (playableVideos.isEmpty() && contentPayload == null) {
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
                    val combinedSubtitles = remember(allSubtitles, extraSubtitles) { allSubtitles + extraSubtitles }
                    val subtitlesByTabId = remember(combinedSubtitles) {
                        combinedSubtitles.groupBy { it.tabId }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (contentPayload != null) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedVideo = null
                                            selectedQualityUrl = null
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selectedVideo == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    border = if (selectedVideo == null) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                shape = MaterialTheme.shapes.small
                                            ) {
                                                Text(
                                                    text = "Library Content",
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        AsyncImage(
                                            model = contentPayload.visual_metadata?.backdrop_url ?: contentPayload.visual_metadata?.poster_url,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = contentPayload.title ?: "",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium
                                        )

                                        if (contentPayload.content_type == "series" && contentPayload.visual_metadata != null) {
                                            val meta = contentPayload.visual_metadata!!
                                            if (meta.season != null && meta.episode != null) {
                                                Text(
                                                    text = "S${meta.season} E${meta.episode}${if (meta.episode_title != null) " - ${meta.episode_title}" else ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "URL: ${contentPayload.url}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        items(playableVideos) { video ->
                            VideoItemDetailed(
                                video = video,
                                isSelected = selectedVideo?.url == video.url,
                                selectedQualityUrl = if (selectedVideo?.url == video.url) selectedQualityUrl else null,
                                onClick = {
                                    selectedVideo = video
                                    selectedQualityUrl = null
                                },
                                onQualityClick = { specificUrl ->
                                    selectedVideo = video
                                    selectedQualityUrl = specificUrl
                                },
                                onDownloadClick = { onDownload(video) },
                                onCopyClick = {
                                    copyToClipboard(context, video.url)
                                    Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                onOpenWithClick = {
                                    openInExternalPlayer(context, video.url, video.contentType, video.headers)
                                },
                                onPreviewClick = { previewVideo = video }
                            )
                        }
                    }
                }
            } else if (selectedTab == 1) {
                // Subtitles Tab
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Button(
                        onClick = { showSubtitleSearchDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search for Subtitles")
                    }
                    Spacer(Modifier.height(16.dp))

                    val combinedSubtitles = remember(allSubtitles, extraSubtitles) { allSubtitles + extraSubtitles }
                    if (combinedSubtitles.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No subtitles detected or downloaded.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(combinedSubtitles) { subtitle ->
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

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedSubtitles = if (isSelected) {
                                                selectedSubtitles - subtitle.url
                                            } else {
                                                selectedSubtitles + subtitle.url
                                            }
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
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
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = subInfo.extension?.uppercase() ?: "SUB",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        text = formatTimestamp(subtitle.timestamp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.outline
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = subtitle.title ?: subInfo.filename ?: (subInfo.host + subInfo.path),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )

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
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // end else (non-browse mode)

        }
    }
}
}

    if (showSubtitleSearchDialog) {
        PlayBridgeTheme {
            AlertDialog(
                onDismissRequest = {
                    showSubtitleSearchDialog = false
                    showSubtitleResults = false
                    subtitleSearchResults = emptyList()
                },
                title = { Text("Search Subtitles") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = subtitleSearchQuery,
                        onValueChange = { subtitleSearchQuery = it },
                        label = { Text("Movie / TV Show Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (isSearchingSubtitles) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = {
                                    if (subtitleSearchQuery.isNotBlank()) {
                                        isSearchingSubtitles = true
                                        showSubtitleResults = true
                                        scope.launch {
                                            val response = tmdbRepository.searchMulti(subtitleSearchQuery)
                                            subtitleSearchResults = response.results.filter { it.isMovie || it.isTvShow }
                                            isSearchingSubtitles = false
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (showSubtitleResults) {
                        LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                            items(subtitleSearchResults) { item ->
                                ListItem(
                                    headlineContent = { Text(item.displayTitle) },
                                    supportingContent = { Text("${item.mediaType.uppercase()} • ${item.year}") },
                                    modifier = Modifier.clickable {
                                        isSearchingSubtitles = true
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
                                                    extraSubtitles = extraSubtitles + newSubs
                                                    Toast.makeText(context, "Added ${newSubs.size} subtitles", Toast.LENGTH_SHORT).show()
                                                    showSubtitleSearchDialog = false
                                                } else {
                                                    Toast.makeText(context, "No subtitles found", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Could not find IMDB ID", Toast.LENGTH_SHORT).show()
                                            }
                                            isSearchingSubtitles = false
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
                    showSubtitleSearchDialog = false
                    showSubtitleResults = false
                    subtitleSearchResults = emptyList()
                }) {
                    Text("Close")
                }
            }
        )
    }
}

    // In-app video preview
    previewVideo?.let { pv ->
        PlayBridgeTheme {
            VideoPreviewSheet(
                video = pv,
                onDismiss = { previewVideo = null },
                onSendToTv = {
                    previewVideo = null
                    onVideoClick(applyProxy(pv), selectedSubtitles.toList())
                }
            )
        }
    }
}

@Composable
private fun VideoItemDetailed(
    video: DetectedVideo,
    isSelected: Boolean,
    selectedQualityUrl: String?,
    onClick: () -> Unit,
    onQualityClick: (String) -> Unit,
    onDownloadClick: () -> Unit,
    onCopyClick: () -> Unit,
    onOpenWithClick: () -> Unit,
    onPreviewClick: () -> Unit
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
                    if (video.originalMessage != null) {
                        IconButton(onClick = { showRaw = !showRaw }) {
                            Icon(
                                Icons.Default.Code,
                                contentDescription = if (showRaw) "Hide Raw Data" else "Show Raw Data",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onPreviewClick) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Preview",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onOpenWithClick) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Open With",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onCopyClick) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Copy URL",
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
                }
            }
        }
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
