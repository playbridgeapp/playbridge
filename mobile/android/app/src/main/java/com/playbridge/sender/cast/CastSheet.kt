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
import androidx.compose.material.icons.filled.PhoneAndroid
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
 * Bottom sheet for casting media to a TV — pick a video or browse URL, choose a device and player, send.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CastSheet(
    videos: List<DetectedVideo>,
    onDismiss: () -> Unit,
    onVideoClick: (DetectedVideo, List<String>?) -> Unit,
    onQueueVideo: (DetectedVideo, List<String>?) -> Unit = { _, _ -> },
    onDownload: (DetectedVideo) -> Unit,
    onClear: () -> Unit,
    isTvPlaying: Boolean = false,
    playerMode: String = "tv",
    onPlayerModeChange: (String) -> Unit = {},
    selectedTvDevice: TvDevice? = null,
    onOpenAllDevices: () -> Unit = {},
    browseUrl: String = "",
    onBrowseClick: ((String, Boolean) -> Unit)? = null,
    onOpenNewTab: ((String) -> Unit)? = null,
    initialMode: String = "play",
    mediaflowProxyUrl: String = "",
    mediaflowProxyPassword: String = "",
    mediaflowAutoSelect: Boolean = true,
    subtitleService: StremioSubtitleService = StremioSubtitleService(),
    contentPayload: playbridge.PlayPayload? = null,
    onContentClick: (playbridge.PlayPayload) -> Unit = {},
    onQueueContent: (playbridge.PlayPayload) -> Unit = {}
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

    // The action chosen in the header dropdown. It drives both the body layout and what the
    // Send button does when tapped — nothing is sent on selection:
    //   "play"   → play the current selection now on the TV
    //   "queue"  → append the current selection to the TV's queue
    //   "browse" → open the page URL on the TV
    // Whether the selected TV can open web pages. Receivers that report no browser engines
    // (e.g. desktop, Apple TV) get no Browse action at all.
    val canBrowse = onBrowseClick != null && selectedTvDevice?.browsers?.isNotEmpty() == true

    var castAction by remember(playableVideos, contentPayload) {
        mutableStateOf(
            if (playableVideos.isEmpty() && contentPayload == null && canBrowse) "browse" else initialMode
        )
    }

    // Never sit in browse mode for a TV that can't browse (covers capabilities arriving after
    // the sheet opened, an explicit browse initialMode, or switching to a browser-less TV).
    LaunchedEffect(canBrowse) {
        if (!canBrowse && castAction == "browse") castAction = "play"
    }

    // If we have content metadata but no browser-detected videos, default to playing it
    // (the video URL comes from the contentPayload/library resource).
    LaunchedEffect(contentPayload) {
        if (contentPayload != null && playableVideos.isEmpty()) {
            castAction = "play"
        }
    }
    val allSubtitles = remember(videos) { videos.filter { it.isSubtitle } }

    val isPlaylistMode = remember(playableVideos) {
        playableVideos.firstOrNull()?.playlistPayload != null
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = if (isPlaylistMode) listOf("Playlist Bundle") else listOf("Videos", "Subtitles")

    // State for subtitle search dialog. The gate + shared results live here; the dialog's
    // own query/loading/results are bundled in SubtitleSearchUiState (held here so they
    // persist across open/close).
    var showSubtitleSearchDialog by remember { mutableStateOf(false) }
    val subtitleSearch = remember { SubtitleSearchUiState() }
    var extraSubtitles by remember { mutableStateOf<List<DetectedVideo>>(emptyList()) }

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

    // Selected subtitles mapped to player tracks, labelled from the
    // detected subtitle's title (falling back to its filename).
    fun phoneSubtitleTracks(): List<com.playbridge.sender.player.SubtitleTrack> {
        val combined = allSubtitles + extraSubtitles
        return selectedSubtitles.map { url ->
            val sub = combined.find { it.url == url }
            com.playbridge.sender.player.SubtitleTrack(
                url = url,
                label = sub?.title ?: parseUrlInfo(url).filename
            )
        }
    }

    // Play the given video on this phone via the in-app PlayerActivity.
    // Skips proxy rewriting (that's only for TV hand-off) — ExoPlayer applies
    // the request headers directly.
    fun dispatchPhoneForVideo(video: DetectedVideo) {
        val specificUrl = if (selectedVideo?.url == video.url) selectedQualityUrl else null
        val resolved = if (specificUrl != null) {
            val selectedQuality = video.qualities.find { it.url == specificUrl }
            val playlist = video.hlsPlaylist
            if (playlist != null && selectedQuality != null) {
                val filteredContent = HlsParser.generateFilteredPlaylist(playlist, selectedQuality)
                val base64Content = android.util.Base64.encodeToString(filteredContent.toByteArray(), android.util.Base64.NO_WRAP)
                video.copy(url = "data:application/x-mpegurl;base64,$base64Content", contentType = "application/x-mpegurl")
            } else {
                video.copy(url = specificUrl)
            }
        } else video
        com.playbridge.sender.player.PlayerLauncher.start(
            context = context,
            url = resolved.url,
            title = resolved.title,
            contentType = resolved.contentType,
            headers = VideoDetector.mediaHeaders(resolved),
            subtitles = phoneSubtitleTracks()
        )
        onDismiss()
    }

    // Play the given video via an external player (e.g. VLC, MPV)
    fun dispatchExternalPlayerForVideo(video: DetectedVideo) {
        val specificUrl = if (selectedVideo?.url == video.url) selectedQualityUrl else null
        val resolved = if (specificUrl != null) {
            val selectedQuality = video.qualities.find { it.url == specificUrl }
            val playlist = video.hlsPlaylist
            if (playlist != null && selectedQuality != null) {
                val filteredContent = HlsParser.generateFilteredPlaylist(playlist, selectedQuality)
                val base64Content = android.util.Base64.encodeToString(filteredContent.toByteArray(), android.util.Base64.NO_WRAP)
                video.copy(url = "data:application/x-mpegurl;base64,$base64Content", contentType = "application/x-mpegurl")
            } else {
                video.copy(url = specificUrl)
            }
        } else video
        openInExternalPlayer(
            context = context,
            url = resolved.url,
            mimeType = resolved.contentType,
            headers = VideoDetector.mediaHeaders(resolved),
            title = resolved.title
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
                // Shared by the header action dropdown and the Send button.
                var actionMenuExpanded by remember { mutableStateOf(false) }
                val playEnabled = selectedVideo != null || contentPayload != null
                val canQueue = isTvPlaying

                // Resolve the current selection (quality / HLS filtering / proxy) once, then
                // either play it now or append it to the TV's queue.
                fun dispatch(queue: Boolean) {
                    if (contentPayload != null && selectedVideo == null) {
                        if (queue) onQueueContent(contentPayload) else onContentClick(contentPayload)
                        return
                    }
                    val specificUrl = selectedQualityUrl
                    val resolved = if (specificUrl != null) {
                        val selectedQuality = selectedVideo!!.qualities.find { it.url == specificUrl }
                        val playlist = selectedVideo!!.hlsPlaylist
                        if (playlist != null && selectedQuality != null) {
                            // Generate filtered playlist (data: URI — applyProxy skips these)
                            val filteredContent = HlsParser.generateFilteredPlaylist(playlist, selectedQuality)
                            val base64Content = android.util.Base64.encodeToString(filteredContent.toByteArray(), android.util.Base64.NO_WRAP)
                            val dataUri = "data:application/x-mpegurl;base64,$base64Content"
                            applyProxy(selectedVideo!!.copy(url = dataUri, contentType = "application/x-mpegurl"))
                        } else {
                            applyProxy(selectedVideo!!.copy(url = specificUrl))
                        }
                    } else {
                        applyProxy(selectedVideo!!)
                    }
                    val subs = selectedSubtitles.toList()
                    if (queue) onQueueVideo(resolved, subs) else onVideoClick(resolved, subs)
                }

                // Whether the Send button can fire for the currently-selected action.
                val sendEnabled = when (castAction) {
                    "browse" -> canBrowse && browseUrl.isNotBlank()
                    "queue"  -> playEnabled && canQueue
                    else     -> playEnabled
                }

                // Action chip: tap opens a dropdown to choose Play / Queue / Browse. Selecting an
                // action only switches the mode — nothing is sent until the Send button is tapped.
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { actionMenuExpanded = true },
                        label = {
                            Text(
                                text = when (castAction) {
                                    "browse" -> "Browse"
                                    "queue"  -> "Queue"
                                    else     -> "Play"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = when (castAction) {
                                    "browse" -> Icons.Default.Language
                                    "queue"  -> Icons.Default.List
                                    else     -> Icons.Default.PlayArrow
                                },
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Choose action",
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = actionMenuExpanded,
                        onDismissRequest = { actionMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = {
                                castAction = "play"
                                actionMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Queue") },
                            leadingIcon = { Icon(Icons.Default.List, contentDescription = null) },
                            enabled = canQueue,
                            onClick = {
                                castAction = "queue"
                                actionMenuExpanded = false
                            }
                        )
                        if (canBrowse) {
                            DropdownMenuItem(
                                text = { Text("Browse") },
                                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                                onClick = {
                                    castAction = "browse"
                                    actionMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Shared device picker (TV-only here — no "This Device" when casting). Pinned
                    // width so a long device name ellipsises instead of pushing Send off the edge.
                    DeviceChip(
                        showThisDevice = false,
                        fixedWidth = 140.dp,
                        onOpenAllDevices = onOpenAllDevices
                    )
                    if (castAction != "browse" && videos.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // The single commit point: runs whichever action the dropdown has selected.
                    IconButton(
                        onClick = {
                            when (castAction) {
                                "browse" -> onBrowseClick?.invoke(playerMode, browseDesktopMode)
                                "queue"  -> dispatch(queue = true)
                                else     -> dispatch(queue = false)
                            }
                        },
                        enabled = sendEnabled
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (sendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            }

            // Compact player + TV selectors side by side
            // Options reflect what the selected TV reported it supports (see TvCapabilityOptions).
            val playerOptions = if (castAction == "browse") {
                TvCapabilityOptions.browserOptions(selectedTvDevice)
            } else {
                TvCapabilityOptions.playerOptions(selectedTvDevice)
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
                if (castAction != "browse" && proxyAvailable) {
                    ChipDropdown(
                        selectedLabel = proxyMode.label,
                        options = MediaflowProxy.Mode.entries.map { it.name to it.label },
                        selectedValue = proxyMode.name,
                        onSelect = { value -> proxyMode = MediaflowProxy.Mode.valueOf(value) }
                    )
                }
                if (castAction == "browse") {
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

            if (castAction == "browse") {
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
                                    dispatchExternalPlayerForVideo(video)
                                },
                                onPreviewClick = { previewVideo = video },
                                onPlayPhoneClick = { dispatchPhoneForVideo(video) }
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
        SubtitleSearchDialog(
            state = subtitleSearch,
            tmdbRepository = tmdbRepository,
            subtitleService = subtitleService,
            onAddSubtitles = { newSubs -> extraSubtitles = extraSubtitles + newSubs },
            onDismiss = { showSubtitleSearchDialog = false },
        )
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
