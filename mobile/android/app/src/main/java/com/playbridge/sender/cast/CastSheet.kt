package com.playbridge.sender.cast

import com.playbridge.sender.downloads.DownloadUtils
import android.content.ClipData
import androidx.core.net.toUri
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.playbridge.sender.data.library.TmdbRepository
import com.playbridge.sender.data.library.StremioSubtitleService
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.cast.proxy.BrowserStreamRoute
import com.playbridge.sender.cast.proxy.CastableMedia
import com.playbridge.sender.cast.proxy.StreamProxySettingsStore
import com.playbridge.sender.cast.proxy.StreamRouteException
import com.playbridge.sender.cast.proxy.StreamRouteMode
import com.playbridge.sender.cast.proxy.StreamRouteService
import com.playbridge.sender.cast.routing.CastPreparation
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.ui.theme.PlayBridgeTheme

/**
 * Bottom sheet for casting media to a TV — pick a video or browse URL, choose a stream route, send.
 * Device selection lives on the global connection UI, not on this sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CastSheet(
    videos: List<DetectedVideo>,
    mediaRevision: Int = 0,
    onDismiss: () -> Unit,
    onVideoClick: (DetectedVideo, List<String>?) -> Unit,
    onQueueVideo: (DetectedVideo, List<String>?) -> Unit = { _, _ -> },
    onDownload: (DetectedVideo) -> Unit,
    isTvPlaying: Boolean = false,
    playerMode: String = "tv",
    onPlayerModeChange: (String) -> Unit = {},
    selectedTvDevice: TvDevice? = null,
    onOpenAllDevices: () -> Unit = {},
    browseUrl: String = "",
    onBrowseClick: ((String, Boolean) -> Unit)? = null,
    onOpenNewTab: ((String) -> Unit)? = null,
    initialMode: String = "play",
    subtitleService: StremioSubtitleService = StremioSubtitleService(),
    contentPayload: playbridge.PlayPayload? = null,
    onContentClick: (playbridge.PlayPayload) -> Unit = {},
    onQueueContent: (playbridge.PlayPayload) -> Unit = {},
    detectionEnabled: Boolean = true,
    onEnableDetection: (() -> Unit)? = null,
    // When provided, each detected-video card's ⋮ menu gets a "Save to Collection" action.
    onSaveToCollection: ((DetectedVideo) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val streamProxySettings = remember { StreamProxySettingsStore.load(context) }
    val streamRouteService = remember { StreamRouteService(context.applicationContext) }
    val castProtocol = selectedTvDevice?.resolvedProtocol ?: CastProtocol.PLAYBRIDGE
    val isBrowserDestination = castProtocol == CastProtocol.WEB_BROWSER
    var routeMode by remember(castProtocol) {
        mutableStateOf(CastPreparation.defaultRoute(castProtocol))
    }
    var packaging by remember { mutableStateOf(false) }
    val castSessionManager: CastSessionManager = org.koin.compose.koinInject()
    // Promote synthetic handoff into a dedicated first row; rank the rest below.
    val rankedVideos = remember(videos, mediaRevision) { buildCastSheetVideos(videos) }
    val playableVideos = remember(rankedVideos, mediaRevision) {
        rankedVideos.filter {
            it.effectiveValidationState != MediaValidationState.FAILED
        }
    }
    val unavailableVideos = remember(rankedVideos, mediaRevision) {
        rankedVideos.filter {
            it.effectiveValidationState == MediaValidationState.FAILED
        }
    }
    val detectedAudio = remember(videos, mediaRevision) { buildCastSheetAudio(videos) }
    val detectedImages = remember(videos, mediaRevision) { buildCastSheetImages(videos) }
    val hasDetectedMedia = playableVideos.isNotEmpty() ||
        detectedAudio.isNotEmpty() ||
        detectedImages.isNotEmpty()

    // The action chosen in the header dropdown. It drives both the body layout and what the
    // Send button does when tapped — nothing is sent on selection:
    //   "play"   → play the current selection now on the TV
    //   "queue"  → append the current selection to the TV's queue
    //   "browse" → open the page URL on the TV
    // Whether the selected TV can open web pages. Receivers that report no browser engines
    // (e.g. desktop, Apple TV) get no Browse action at all.
    val canBrowse = onBrowseClick != null && selectedTvDevice?.browsers?.isNotEmpty() == true

    var castAction by remember(playableVideos, detectedAudio, detectedImages, contentPayload) {
        mutableStateOf(
            if (!hasDetectedMedia && contentPayload == null && canBrowse) "browse" else initialMode
        )
    }

    // Never sit in browse mode for a TV that can't browse (covers capabilities arriving after
    // the sheet opened, an explicit browse initialMode, or switching to a browser-less TV).
    LaunchedEffect(canBrowse) {
        if (!canBrowse && castAction == "browse") castAction = "play"
    }

    // If we have content metadata but no browser-detected videos, default to playing it
    // (the video URL comes from the contentPayload/library resource).
    LaunchedEffect(contentPayload, hasDetectedMedia) {
        if (contentPayload != null && !hasDetectedMedia) {
            castAction = "play"
        }
    }
    val allSubtitles = remember(videos, mediaRevision) { videos.filter { it.isSubtitle } }

    val isPlaylistMode = remember(playableVideos) {
        playableVideos.firstOrNull()?.playlistPayload != null
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs: List<Pair<DetectedMediaKind?, String>> = if (isPlaylistMode) {
        listOf(null to "Playlist Bundle")
    } else {
        listOf(
            DetectedMediaKind.VIDEO to "Videos",
            DetectedMediaKind.AUDIO to "Audio",
            DetectedMediaKind.IMAGE to "Images",
            DetectedMediaKind.SUBTITLE to "Subtitles",
        )
    }
    LaunchedEffect(tabs.size) {
        if (selectedTab !in tabs.indices) selectedTab = 0
    }

    // State for subtitle search dialog. The gate + shared results live here; the dialog's
    // own query/loading/results are bundled in SubtitleSearchUiState (held here so they
    // persist across open/close).
    var showSubtitleSearchDialog by remember { mutableStateOf(false) }
    val subtitleSearch = remember { SubtitleSearchUiState() }
    var extraSubtitles by remember { mutableStateOf<List<DetectedVideo>>(emptyList()) }

    val tmdbRepository = remember { TmdbRepository(context) }
    val scope = rememberCoroutineScope()
    val videoListState = rememberLazyListState()
    var showUnavailableVideos by remember { mutableStateOf(false) }
    var previousBestVideoUrl by remember { mutableStateOf<String?>(null) }
    var showNewBestVideoPrompt by remember { mutableStateOf(false) }
    var userBrowsedVideoList by remember { mutableStateOf(false) }

    // A keyed LazyColumn preserves the previously visible row when ranking changes, which can
    // move its index away from zero even though the user never scrolled. Track real drag input so
    // a background manifest probe can promote the quality ladder into view automatically.
    LaunchedEffect(videoListState) {
        videoListState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userBrowsedVideoList = true
        }
    }
    LaunchedEffect(videoListState) {
        snapshotFlow { videoListState.isScrollInProgress }.collect { scrolling ->
            if (
                !scrolling &&
                videoListState.firstVisibleItemIndex == 0 &&
                videoListState.firstVisibleItemScrollOffset == 0
            ) {
                userBrowsedVideoList = false
            }
        }
    }

    // Global selection state — prefer the synthetic row when present.
    var selectedVideo by remember(playableVideos, detectedAudio, detectedImages) {
        mutableStateOf(
            playableVideos.firstOrNull()
                ?: detectedAudio.firstOrNull()
                ?: detectedImages.firstOrNull()
        )
    }
    var selectedQualityUrl by remember { mutableStateOf<String?>(null) }
    var selectedSubtitles by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Validation and thumbnail work can change the ordering after the sheet opens. Keep the
    // selection bound to the current object and offer a non-disruptive jump when a newly
    // verified candidate becomes the best result while the user is farther down the list.
    LaunchedEffect(mediaRevision) {
        val currentUrl = selectedVideo?.url
        selectedVideo = playableVideos.firstOrNull { it.url == currentUrl }
            ?: detectedAudio.firstOrNull { it.url == currentUrl }
            ?: detectedImages.firstOrNull { it.url == currentUrl }
            ?: playableVideos.firstOrNull()
            ?: detectedAudio.firstOrNull()
            ?: detectedImages.firstOrNull()
    }
    LaunchedEffect(playableVideos.firstOrNull()?.url) {
        val bestUrl = playableVideos.firstOrNull()?.url
        val previousUrl = previousBestVideoUrl
        if (previousUrl != null && bestUrl != null && bestUrl != previousUrl) {
            if (!userBrowsedVideoList || videoListState.firstVisibleItemIndex == 0) {
                // Stable LazyColumn keys intentionally preserve the visible row across reorders.
                // Override that preservation unless the user deliberately browsed farther down.
                videoListState.scrollToItem(0)
                showNewBestVideoPrompt = false
            } else {
                showNewBestVideoPrompt = true
            }
        }
        previousBestVideoUrl = bestUrl
    }

    val selectedIsLocal = remember(
        selectedVideo?.url,
        selectedVideo?.playlistBody,
        selectedVideo?.audioUrl,
        selectedVideo?.hasSyntheticHandoff,
        contentPayload?.url,
    ) {
        val url = selectedVideo?.url ?: contentPayload?.url.orEmpty()
            BrowserStreamRoute.isLocalMediaUrl(url) ||
            !selectedVideo?.playlistBody.isNullOrBlank() ||
            !selectedVideo?.audioUrl.isNullOrBlank() ||
            selectedVideo?.hasSyntheticHandoff == true
    }

    val effectiveRoute = remember(
        routeMode,
        selectedIsLocal,
        isBrowserDestination,
    ) {
        when {
            isBrowserDestination -> BrowserStreamRoute.effectiveMode(
                routeMode,
                isLocalMedia = selectedIsLocal,
            )
            routeMode == StreamRouteMode.VIA_PHONE || routeMode == StreamRouteMode.VIA_PROXY ->
                routeMode
            routeMode == StreamRouteMode.DIRECT && selectedIsLocal -> StreamRouteMode.VIA_PHONE
            routeMode == StreamRouteMode.DIRECT -> StreamRouteMode.DIRECT
            else -> routeMode
        }
    }
    val browserRouteHint = remember(routeMode, effectiveRoute, selectedIsLocal, isBrowserDestination) {
        if (!isBrowserDestination) null
        else BrowserStreamRoute.overrideReason(
            requested = routeMode,
            effective = effectiveRoute,
            isLocalMedia = selectedIsLocal,
        )
    }
    val directRouteHint = remember(
        routeMode,
        effectiveRoute,
        isBrowserDestination,
        castAction,
        castProtocol,
    ) {
        when {
            isBrowserDestination || castAction == "browse" -> null
            routeMode == StreamRouteMode.DIRECT && effectiveRoute == StreamRouteMode.VIA_PHONE ->
                "This media is phone-only, so it will use Via phone."
            routeMode == StreamRouteMode.DIRECT && castProtocol != CastProtocol.PLAYBRIDGE ->
                "Direct is not recommended. It sends no browser headers, and the receiver must be able to reach the URL."
            else -> null
        }
    }

    // Keep the user's route choice for browser loads that skip this sheet. Do not store the
    // item-specific effective route (for example, a local item forcing Via phone).
    LaunchedEffect(routeMode, castProtocol) {
        castSessionManager.setPreferredStreamRoute(routeMode)
    }

    // Synthetic / exclusive handoff must use the phone proxy (body is served locally).
    LaunchedEffect(selectedVideo?.url, selectedVideo?.playlistBody, selectedVideo?.audioUrl) {
        if (selectedVideo?.hasSyntheticHandoff == true) {
            routeMode = StreamRouteMode.VIA_PHONE
        }
    }

    // In-app preview state
    var previewVideo by remember { mutableStateOf<DetectedVideo?>(null) }

    // Browse-mode desktop/mobile toggle
    var browseDesktopMode by remember { mutableStateOf(false) }

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

                // Resolve quality / HLS filtering, then package through the selected stream route.
                fun dispatch(queue: Boolean) {
                    if (packaging) return
                    // Library / contentPayload path: same policy + packaging as detected media.
                    if (contentPayload != null && selectedVideo == null) {
                        packaging = true
                        scope.launch {
                            try {
                                val payload = contentPayload
                                val headers = payload.headers
                                val isLocal = BrowserStreamRoute.isLocalMediaUrl(payload.url)
                                val requested = if (isBrowserDestination) {
                                    BrowserStreamRoute.effectiveMode(routeMode, isLocalMedia = isLocal)
                                } else {
                                    effectiveRoute
                                }
                                val prepared = packagePreparedForCast(
                                    streamRouteService = streamRouteService,
                                    media = CastableMedia(
                                        url = payload.url,
                                        headers = headers,
                                        contentType = payload.content_type,
                                        title = payload.title,
                                    ),
                                    requested = requested,
                                    protocol = castProtocol,
                                    settings = streamProxySettings,
                                    browserDestination = isBrowserDestination,
                                    context = context,
                                )
                                // Carry route metadata on a DetectedVideo so external
                                // MediaItem construction never uses a global arm slot.
                                // Native PlayBridge paths still use the packaged payload.
                                val isExternalProtocol = castProtocol == CastProtocol.GOOGLE_CAST ||
                                    castProtocol == CastProtocol.ROKU ||
                                    castProtocol == CastProtocol.DLNA ||
                                    castProtocol == CastProtocol.WEB_BROWSER
                                if (isExternalProtocol) {
                                    val asVideo = DetectedVideo(
                                        url = prepared.url,
                                        headers = prepared.headers,
                                        contentType = prepared.contentType ?: payload.content_type,
                                        title = payload.title,
                                        effectiveStreamRoute =
                                            prepared.effectiveRoute.mode.prefsValue,
                                        streamRouteReason = prepared.effectiveRoute.policyReason,
                                        visualMetadata = payload.visual_metadata,
                                        startPositionMs = payload.start_position_ms ?: 0L,
                                    )
                                    if (queue) onQueueVideo(asVideo, null) else onVideoClick(asVideo, null)
                                } else {
                                    val packagedPayload = payload.copy(
                                        url = prepared.url,
                                        headers = prepared.headers.orEmpty(),
                                        content_type = prepared.contentType
                                            ?: payload.content_type,
                                    )
                                    if (queue) {
                                        onQueueContent(packagedPayload)
                                    } else {
                                        onContentClick(packagedPayload)
                                    }
                                }
                            } catch (e: StreamRouteException) {
                                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    e.message ?: "Failed to package stream",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                packaging = false
                            }
                        }
                        return
                    }
                    val specificUrl = selectedQualityUrl
                    val evaluatedQualityUrl = specificUrl
                    val base = if (specificUrl != null) {
                        val selectedQuality = selectedVideo!!.qualities.find { it.url == specificUrl }
                        val playlist = selectedVideo!!.hlsPlaylist
                        if (playlist != null && selectedQuality != null) {
                            // Build a self-contained fixed-quality candidate. Packaging later
                            // converts it to an HTTP child or the original master when the chosen
                            // route cannot carry a data URI.
                            val hasSeparateAudio = !selectedQuality.audioGroupId.isNullOrBlank()
                            val canDirectChild = !hasSeparateAudio &&
                                !isBrowserDestination &&
                                effectiveRoute == StreamRouteMode.DIRECT &&
                                (specificUrl.startsWith("http://") ||
                                    specificUrl.startsWith("https://"))
                            if (canDirectChild) {
                                selectedVideo!!.copy(
                                    url = specificUrl,
                                    contentType = selectedVideo!!.contentType
                                        ?: "application/vnd.apple.mpegurl",
                                )
                            } else {
                                val filteredContent =
                                    HlsParser.generateFilteredPlaylist(playlist, selectedQuality)
                                val base64Content = android.util.Base64.encodeToString(
                                    filteredContent.toByteArray(),
                                    android.util.Base64.NO_WRAP,
                                )
                                val dataUri = "data:application/x-mpegurl;base64,$base64Content"
                                selectedVideo!!.copy(
                                    url = dataUri,
                                    contentType = "application/x-mpegurl",
                                )
                            }
                        } else {
                            selectedVideo!!.copy(url = specificUrl)
                        }
                    } else {
                        selectedVideo!!
                    }
                    val subs = selectedSubtitles.toList()
                    packaging = true
                    scope.launch {
                        try {
                            val isLocal = BrowserStreamRoute.isLocalMediaUrl(base.url) ||
                                !base.playlistBody.isNullOrBlank() ||
                                base.hasSyntheticHandoff
                            val originalHeaders = VideoDetector.mediaHeaders(base)
                            var requested = if (isBrowserDestination) {
                                BrowserStreamRoute.effectiveMode(routeMode, isLocalMedia = isLocal)
                            } else {
                                effectiveRoute
                            }
                            // Align packaging URL/mode when quality filtering produced a data URI.
                            val qualityForAudio = evaluatedQualityUrl?.let { qUrl ->
                                selectedVideo?.qualities?.find { it.url == qUrl }
                            }
                            val hasSeparateAudio =
                                !qualityForAudio?.audioGroupId.isNullOrBlank()
                            val (packageUrl, packageMode) = CastPreparation.resolveItemForPackaging(
                                evaluatedUrl = evaluatedQualityUrl ?: base.url,
                                packagedCandidateUrl = base.url,
                                requestedMode = requested,
                                hasSeparateAudio = hasSeparateAudio,
                                originalMasterUrl = selectedVideo?.url,
                            )
                            val usingAutoQuality = evaluatedQualityUrl != null &&
                                hasSeparateAudio &&
                                requested != StreamRouteMode.VIA_PHONE &&
                                packageUrl == selectedVideo?.url
                            requested = packageMode
                            val prepared = packagePreparedForCast(
                                streamRouteService = streamRouteService,
                                media = CastableMedia(
                                    url = packageUrl,
                                    headers = originalHeaders,
                                    contentType = base.contentType,
                                    title = base.title,
                                    playlistBody = base.playlistBody.takeIf {
                                        requested == StreamRouteMode.VIA_PHONE &&
                                            !packageUrl.startsWith("data:")
                                    },
                                    audioUrl = base.audioUrl.takeIf {
                                        requested == StreamRouteMode.VIA_PHONE
                                    },
                                ),
                                requested = requested,
                                protocol = castProtocol,
                                settings = streamProxySettings,
                                browserDestination = isBrowserDestination,
                                context = context,
                            )
                            if (usingAutoQuality) {
                                Toast.makeText(
                                    context,
                                    "Fixed quality requires Via phone; using Auto quality with this route",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            val resolved = base.copy(
                                url = prepared.url,
                                contentType = prepared.contentType ?: base.contentType,
                                headers = prepared.headers,
                                effectiveStreamRoute = prepared.effectiveRoute.mode.prefsValue,
                                streamRouteReason = prepared.effectiveRoute.policyReason,
                            )
                            if (queue) onQueueVideo(resolved, subs) else onVideoClick(resolved, subs)
                        } catch (e: StreamRouteException) {
                            Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                e.message ?: "Failed to package stream",
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            packaging = false
                        }
                    }
                }

                val sendEnabled = !packaging && when (castAction) {
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
                    if (castAction != "browse") {
                        val routeOptions = StreamRouteMode.entries
                            .filter {
                                // Via proxy only when configured.
                                it != StreamRouteMode.VIA_PROXY ||
                                    streamProxySettings.isRemoteConfigured
                            }
                            .filter {
                                // Phone-local and synthetic media must be served by the phone.
                                !selectedIsLocal || it == StreamRouteMode.VIA_PHONE
                            }
                            .map { mode ->
                                val label = when {
                                    mode == StreamRouteMode.VIA_PHONE &&
                                        castProtocol != CastProtocol.PLAYBRIDGE ->
                                        "Via phone · Recommended"
                                    else -> mode.label
                                }
                                mode.prefsValue to label
                            }
                        ChipDropdown(
                            selectedLabel = effectiveRoute.label,
                            options = routeOptions,
                            selectedValue = effectiveRoute.prefsValue,
                            onSelect = { value ->
                                val picked = StreamRouteMode.fromPrefs(value)
                                routeMode = picked
                            },
                            fixedWidth = 120.dp,
                        )
                    }

                    // Destination indicator: shows where we're casting and reports it on tap.
                    // "This device" (phone) gets a phone icon; any remote target gets a TV icon.
                    val isThisDevice = selectedTvDevice == null
                    val currentTarget = selectedTvDevice?.name
                    IconButton(onClick = {
                        Toast.makeText(
                            context,
                            if (isThisDevice) "Connected to this device"
                            else "Connected to $currentTarget",
                            Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(
                            imageVector = if (isThisDevice) Icons.Default.Smartphone
                            else Icons.Default.Tv,
                            contentDescription = if (isThisDevice) "This device"
                            else "Connected to $currentTarget",
                            tint = if (isThisDevice) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                    }

                    // The single send Action: runs whichever action the dropdown has selected.
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

            if (castAction != "browse" && browserRouteHint != null) {
                Text(
                    text = browserRouteHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            } else if (castAction != "browse" && isBrowserDestination) {
                Text(
                    text = if (effectiveRoute == StreamRouteMode.DIRECT) {
                        "Direct is not recommended. The receiver browser must be able to fetch the URL itself."
                    } else {
                        "Casting to browser · Via phone is recommended"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            } else if (castAction != "browse" && directRouteHint != null) {
                Text(
                    text = directRouteHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            // Browse mode still offers the browser-engine picker from TV capabilities.
            val browserOptions = if (castAction == "browse") {
                TvCapabilityOptions.browserOptions(selectedTvDevice)
            } else {
                emptyList()
            }
            val selectedBrowserLabel = browserOptions.find { it.first == playerMode }?.second ?: "TV Default"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (castAction == "browse") {
                    ChipDropdown(
                        selectedLabel = selectedBrowserLabel,
                        options = browserOptions,
                        selectedValue = playerMode,
                        onSelect = onPlayerModeChange
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
                                    browseUrl.toUri()
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

            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 8.dp,
            ) {
                tabs.forEachIndexed { index, (kind, title) ->
                    val count = if (isPlaylistMode) {
                        playableVideos.firstOrNull()?.playlistPayload?.size ?: 0
                    } else {
                        when (index) {
                            0 -> playableVideos.size
                            1 -> detectedAudio.size
                            2 -> detectedImages.size
                            else -> allSubtitles.size + extraSubtitles.size
                        }
                    }
                    val accent = if (kind != null) {
                        mediaCategoryAccent(kind)
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    val tabSelected = selectedTab == index

                    Tab(
                        selected = tabSelected,
                        onClick = { selectedTab = index },
                        selectedContentColor = accent,
                        unselectedContentColor = accent.copy(alpha = 0.72f),
                        modifier = Modifier
                            .padding(horizontal = 2.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (tabSelected) accent.copy(alpha = 0.12f)
                                else Color.Transparent,
                            ),
                        text = {
                            if (isPlaylistMode) {
                                Text(title)
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = when (kind) {
                                            DetectedMediaKind.VIDEO -> Icons.Default.PlayArrow
                                            DetectedMediaKind.AUDIO -> Icons.Default.Audiotrack
                                            DetectedMediaKind.IMAGE -> Icons.Default.Photo
                                            else -> Icons.Default.Subtitles
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(title)
                                    Surface(
                                        shape = CircleShape,
                                        color = accent.copy(alpha = if (tabSelected) 0.22f else 0.12f),
                                        contentColor = accent,
                                    ) {
                                        Text(
                                            text = count.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == 0) {
                if (
                    playableVideos.isEmpty() &&
                    unavailableVideos.isEmpty() &&
                    contentPayload == null
                ) {
                    // Empty state — explain WHY it's empty when detection is off,
                    // instead of a silently empty sheet.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            if (detectionEnabled) Icons.Default.PlayArrow else Icons.Default.VideocamOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (detectionEnabled) "No videos detected yet" else "Video detection is disabled",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (detectionEnabled) {
                            Text(
                                "Browse a page with video content",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            Text(
                                "Videos on pages won't be found while it's off",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                            if (onEnableDetection != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = onEnableDetection) {
                                    Text("Enable video detection")
                                }
                            }
                        }
                    }
                } else {
                    // Video list
                    val combinedSubtitles = remember(allSubtitles, extraSubtitles) { allSubtitles + extraSubtitles }
                    val subtitlesByTabId = remember(combinedSubtitles) {
                        combinedSubtitles.groupBy { it.tabId }
                    }

                    if (showNewBestVideoPrompt) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch { videoListState.animateScrollToItem(0) }
                                showNewBestVideoPrompt = false
                            },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text("A better stream was found · Show")
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        state = videoListState,
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

                        items(
                            items = playableVideos,
                            key = { video -> "available:${video.url}" },
                        ) { video ->
                            VideoItemDetailed(
                                video = video,
                                isSelected = selectedVideo?.url == video.url,
                                selectedQualityUrl = if (selectedVideo?.url == video.url) selectedQualityUrl else null,
                                onClick = {
                                    selectedVideo = video
                                    selectedQualityUrl = null
                                    if (video.hasSyntheticHandoff) {
                                        routeMode = StreamRouteMode.VIA_PHONE
                                    }
                                },
                                onQualityClick = { specificUrl ->
                                    selectedVideo = video
                                    selectedQualityUrl = specificUrl
                                    if (video.hasSyntheticHandoff) {
                                        routeMode = StreamRouteMode.VIA_PHONE
                                    }
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
                                onPlayPhoneClick = { dispatchPhoneForVideo(video) },
                                onSaveToCollection = onSaveToCollection?.let { cb -> { cb(video) } }
                            )
                        }

                        if (unavailableVideos.isNotEmpty()) {
                            item(key = "unavailable-toggle") {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    HorizontalDivider()
                                    TextButton(
                                        onClick = {
                                            showUnavailableVideos = !showUnavailableVideos
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if (showUnavailableVideos) {
                                                "Hide unavailable candidates (${unavailableVideos.size})"
                                            } else {
                                                "Show unavailable candidates (${unavailableVideos.size})"
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        if (showUnavailableVideos) {
                            items(
                                items = unavailableVideos,
                                key = { video -> "unavailable:${video.url}" },
                            ) { video ->
                                VideoItemDetailed(
                                    video = video,
                                    isSelected = selectedVideo?.url == video.url,
                                    selectedQualityUrl = if (selectedVideo?.url == video.url) {
                                        selectedQualityUrl
                                    } else {
                                        null
                                    },
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
                                        Toast.makeText(
                                            context,
                                            "URL copied to clipboard",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    onOpenWithClick = {
                                        dispatchExternalPlayerForVideo(video)
                                    },
                                    onPreviewClick = { previewVideo = video },
                                    onPlayPhoneClick = { dispatchPhoneForVideo(video) },
                                    onSaveToCollection = onSaveToCollection?.let { cb ->
                                        { cb(video) }
                                    },
                                )
                            }
                        }
                    }
                }
            } else if (selectedTab == 1) {
                if (detectedAudio.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Audiotrack,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No audio detected yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(detectedAudio) { audio ->
                            DetectedMediaItemDetailed(
                                media = audio,
                                kind = DetectedMediaKind.AUDIO,
                                isSelected = selectedVideo?.url == audio.url,
                                onClick = {
                                    selectedVideo = audio
                                    selectedQualityUrl = null
                                },
                                onDownloadClick = { onDownload(audio) },
                                onCopyClick = {
                                    copyToClipboard(context, audio.url)
                                    Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                onOpenWithClick = { dispatchExternalPlayerForVideo(audio) },
                                onPlayPhoneClick = { dispatchPhoneForVideo(audio) },
                                onSaveToCollection = onSaveToCollection?.let { cb -> { cb(audio) } },
                            )
                        }
                    }
                }
            } else if (selectedTab == 2) {
                if (detectedImages.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Photo,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No images detected yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(detectedImages) { image ->
                            DetectedMediaItemDetailed(
                                media = image,
                                kind = DetectedMediaKind.IMAGE,
                                isSelected = selectedVideo?.url == image.url,
                                onClick = {
                                    selectedVideo = image
                                    selectedQualityUrl = null
                                },
                                onDownloadClick = { onDownload(image) },
                                onCopyClick = {
                                    copyToClipboard(context, image.url)
                                    Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                onOpenWithClick = { dispatchExternalPlayerForVideo(image) },
                                onSaveToCollection = onSaveToCollection?.let { cb -> { cb(image) } },
                            )
                        }
                    }
                }
            } else if (selectedTab == 3) {
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

    // In-app video preview uses the currently selected route.
    previewVideo?.let { pv ->
        val previewEffectiveRoute = remember(routeMode, isBrowserDestination, pv) {
            val isLocal = BrowserStreamRoute.isLocalMediaUrl(pv.url) ||
                !pv.playlistBody.isNullOrBlank() ||
                pv.hasSyntheticHandoff
            when {
                isBrowserDestination -> BrowserStreamRoute.effectiveMode(routeMode, isLocalMedia = isLocal)
                isLocal -> StreamRouteMode.VIA_PHONE
                else -> routeMode
            }
        }
        PlayBridgeTheme {
            VideoPreviewSheet(
                video = pv,
                onDismiss = { previewVideo = null },
                onSendToTv = {
                    if (packaging) return@VideoPreviewSheet
                    packaging = true
                    scope.launch {
                        try {
                            val isLocal = BrowserStreamRoute.isLocalMediaUrl(pv.url) ||
                                !pv.playlistBody.isNullOrBlank() ||
                                pv.hasSyntheticHandoff
                            val originalHeaders = VideoDetector.mediaHeaders(pv)
                            val requested = if (isBrowserDestination) {
                                BrowserStreamRoute.effectiveMode(routeMode, isLocalMedia = isLocal)
                            } else {
                                previewEffectiveRoute
                            }
                            val prepared = packagePreparedForCast(
                                streamRouteService = streamRouteService,
                                media = CastableMedia(
                                    url = pv.url,
                                    headers = originalHeaders,
                                    contentType = pv.contentType,
                                    title = pv.title,
                                    playlistBody = pv.playlistBody,
                                    audioUrl = pv.audioUrl,
                                ),
                                requested = requested,
                                protocol = castProtocol,
                                settings = streamProxySettings,
                                browserDestination = isBrowserDestination,
                                context = context,
                            )
                            previewVideo = null
                            onVideoClick(
                                pv.copy(
                                    url = prepared.url,
                                    contentType = prepared.contentType ?: pv.contentType,
                                    headers = prepared.headers,
                                    effectiveStreamRoute = prepared.effectiveRoute.mode.prefsValue,
                                    streamRouteReason = prepared.effectiveRoute.policyReason,
                                ),
                                selectedSubtitles.toList(),
                            )
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                e.message ?: "Failed to package stream",
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            packaging = false
                        }
                    }
                }
            )
        }
    }
}

/**
 * Packages media for cast using the user-selected effective route.
 * On browser destinations, falls back Via proxy → Via phone once with a toast.
 */
private suspend fun packagePreparedForCast(
    streamRouteService: StreamRouteService,
    media: CastableMedia,
    requested: StreamRouteMode,
    protocol: CastProtocol,
    settings: com.playbridge.sender.cast.proxy.StreamProxySettings,
    browserDestination: Boolean,
    context: Context,
): com.playbridge.sender.cast.routing.PreparedCastItem {
    return try {
        val prepared = CastPreparation.prepare(
            streamRouteService = streamRouteService,
            media = media,
            requested = requested,
            protocol = protocol,
            settings = settings,
        )
        prepared
    } catch (e: Exception) {
        if (!browserDestination || requested != StreamRouteMode.VIA_PROXY) throw e
        Toast.makeText(
            context,
            "Remote proxy unavailable — using Via phone",
            Toast.LENGTH_LONG,
        ).show()
        val prepared = CastPreparation.prepare(
            streamRouteService = streamRouteService,
            media = media,
            requested = StreamRouteMode.VIA_PHONE,
            protocol = protocol,
            settings = settings,
        )
        prepared
    }
}
