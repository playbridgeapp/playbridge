package com.playbridge.sender.library
import com.playbridge.sender.cast.*

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.palette.graphics.Palette
import com.playbridge.sender.data.library.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.roundToInt
import com.playbridge.sender.model.TvDevice

/**
 * Unified detail screen for movies, TV shows, and addon-native content.
 *
 * ID resolution:
 * - Numeric ID (toIntOrNull != null)   → TMDB lookup (movie/tv based on type)
 * - ID starts with "tt"                → TMDB /find by IMDb ID, then addon meta
 * - Anything else                      → addon-native ID, no TMDB lookup
 *
 * After TMDB resolution, always attempts addon meta fetch where:
 * - addonType = if (type == "tv") "series" else type
 * - metaId = resolvedImdbId ?: id
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryDetailScreen(
    id: String,
    type: String,
    addonRepository: AddonRepository,
    onPlayStream: (url: String, title: String) -> Unit = { _, _ -> },
    onPlayPayloadToTv: (playbridge.PlayPayload) -> Unit = {},
    onStartTvEpisodeQueue: (current: playbridge.PlayPayload, plan: com.playbridge.sender.connection.TvEpisodeQueuePlan) -> Unit = { _, _ -> },
    onPlayPlaylistToTv: (playbridge.PlaylistPayload) -> Unit = {},
    onPlayTrailer: ((String) -> Unit)? = null,
    onQueueAdd: (playbridge.PlayPayload) -> Unit = {},
    onNowPlayingStarted: (tmdbId: Int, season: Int, startEpisode: Int) -> Unit = { _, _, _ -> },
    viewModel: LibraryViewModel,
    tvName: String? = null,
    isTvConnected: Boolean = false,
    selectedTvDevice: TvDevice? = null,
    onTvDeviceSelect: ((TvDevice) -> Unit)? = null,
    onOpenConnectionScreen: () -> Unit = {},
    onSendStreamToTv: (url: String, title: String, headers: Map<String, String>?, contentType: String?) -> Unit = { _, _, _, _ -> },
    onBack: () -> Unit,
    onShare: (title: String, imdbId: String?) -> Unit = { _, _ -> },
    forcedSource: String? = null,
    onOpenRemote: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val subtitleService = remember { StremioSubtitleService(addonRepository) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Determine if this is a series or movie
    val addonType = if (type == "tv") "series" else type
    val isSeries = type == "tv" || type == "series"

    // Primary state for Hub content
    var addonMeta by remember { mutableStateOf<StremioMetaDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var trailerOptions by remember { mutableStateOf<List<TrailerOption>>(emptyList()) }
    var hasAddons by remember { mutableStateOf(false) }
    var selectedSeason by remember { mutableIntStateOf(1) }
    /** Name of the addon that supplied [addonMeta], e.g. "Cinemeta" or "Kitsu". Null until loaded. */
    var addonMetaSource by remember { mutableStateOf<String?>(null) }

    // ID resolution state
    var resolvedImdbId by remember(id) { mutableStateOf(id.takeIf { it.startsWith("tt") }) }
    var resolvedTmdbId by remember(id) { mutableStateOf(id.toIntOrNull()) }

    // Stream resolution state
    var resolvedStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var resolutionState by remember { mutableStateOf(ResolutionState()) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var streamPickerTitle by remember { mutableStateOf("Select Stream") }
    var forceManualInPicker by remember { mutableStateOf(false) }
    var lastResolvedId by remember { mutableStateOf<String?>(null) }
    var lastResolvedType by remember { mutableStateOf<String?>(null) }
    var hubAddon by remember { mutableStateOf<InstalledAddonEntity?>(null) }
    var resolutionJob by remember { mutableStateOf<Job?>(null) }
    var dominantColor by remember { mutableStateOf<Color?>(null) }

    // Episode selection for stream picker
    var currentEpisodeSelection by remember { mutableStateOf<StremioVideo?>(null) }
    // Persist watch mode preference in browser_prefs
    val browserPrefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
    val browserSettings = remember { context.getSharedPreferences("browser_settings", android.content.Context.MODE_PRIVATE) }
    var watchOnTv by remember {
        mutableStateOf(
            if (browserPrefs.contains("watch_on_tv")) {
                browserPrefs.getBoolean("watch_on_tv", tvName != null)
            } else {
                tvName != null
            }
        )
    }

    // Player mode (mirrors CastSheet; persisted to browser_prefs/tv_player_mode)
    val settingsRepository: com.playbridge.sender.data.settings.SettingsRepository = org.koin.compose.koinInject()
    val tmdbRepository: com.playbridge.sender.data.library.TmdbRepository = org.koin.compose.koinInject()
    val playerMode by settingsRepository.tvPlayerMode.collectAsState(initial = "tv")

    // Mediaflow proxy config (read once — user reopens the screen to pick up changes)
    val mediaflowProxyUrl by remember { mutableStateOf(browserSettings.getString(MediaflowProxy.PREFS_KEY_URL, "") ?: "") }
    val mediaflowProxyPassword by remember { mutableStateOf(browserSettings.getString(MediaflowProxy.PREFS_KEY_PASSWORD, "") ?: "") }
    val proxyAvailable = mediaflowProxyUrl.isNotBlank()
    var proxyMode by remember { mutableStateOf(MediaflowProxy.Mode.OFF) }

    // Auto-connect to TV when landing on this screen if we're in TV mode but not connected.
    // Only attempt once per screen entry — subsequent reconnect attempts are triggered by
    // the Watch click/long-click handlers.
    LaunchedEffect(Unit) {
        if (watchOnTv && !isTvConnected && selectedTvDevice != null) {
            onTvDeviceSelect?.invoke(selectedTvDevice)
        }
    }

    val episodeListState = rememberLazyListState()
    var episodesAscending by remember { mutableStateOf(true) }

    // Load unified metadata from the Hub
    LaunchedEffect(id, type, forcedSource) {
        isLoading = true
        errorMessage = null
        addonMeta = null
        addonMetaSource = null

        // 1. Resolve IDs if needed
        // If we have a numeric ID (Discover), resolve to IMDb for addon lookups
        if (resolvedTmdbId != null && resolvedImdbId == null) {
            val imdb = if (type == "movie") {
                viewModel.tmdb.getMovieDetails(resolvedTmdbId!!)?.imdbId
            } else {
                viewModel.tmdb.getTvDetails(resolvedTmdbId!!)?.imdbId
            }
            imdb?.let { resolvedImdbId = it }
        } else if (resolvedImdbId != null && resolvedTmdbId == null) {
            // If we have an IMDb ID (Addon item), resolve to TMDB for tracking
            val findResponse = viewModel.tmdb.findByImdbId(resolvedImdbId!!)
            resolvedTmdbId = if (type == "movie") {
                findResponse?.movieResults?.firstOrNull()?.id
            } else {
                findResponse?.tvResults?.firstOrNull()?.id
            }
        }

        val effectiveId = resolvedImdbId ?: id

        // 2. Fetch unified metadata from Hub (using resolved ID if available)
        val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, effectiveId, forcedSource) }.getOrNull()
        if (metaResult != null) {
            addonMeta = metaResult.first
            addonMetaSource = metaResult.second

            // Final fallback: if addon metadata has a TMDB ID, sync it to state
            if (resolvedTmdbId == null && addonMeta?.tmdbId != null) {
                resolvedTmdbId = addonMeta?.tmdbId
            }
        }

        if (addonMeta == null) {
            errorMessage = "Could not load details for \"$id\"."
        }

        isLoading = false
        hasAddons = addonRepository.hasAnyAddons()
        hubAddon = addonRepository.getInstalledAddons().find { it.supportsPlayEndpoint() }
    }

    // Build the trailer list: TMDB /videos first (when resolved), addon-provided ytIds as
    // fallback/extras. Recomputed whenever the TMDB id or addon metadata changes.
    LaunchedEffect(resolvedTmdbId, addonMeta) {
        val tmdbVideos = resolvedTmdbId?.let { tid ->
            runCatching {
                if (type == "movie") tmdbRepository.getMovieVideos(tid)
                else tmdbRepository.getTvVideos(tid)
            }.getOrNull()?.results ?: emptyList()
        } ?: emptyList()
        trailerOptions = buildTrailerOptions(addonMeta, tmdbVideos)
    }

    // IDs for tracking/streams are now managed as state variables

    // Watchlist state (gated on resolvedTmdbId)
    val isWatchlisted by remember(resolvedTmdbId) {
        resolvedTmdbId?.let { viewModel.isWatchlisted(it) }
            ?: kotlinx.coroutines.flow.flowOf(false)
    }.collectAsState(initial = false)

    val tracked by remember(resolvedTmdbId) {
        resolvedTmdbId?.let { viewModel.getTracked(it) }
            ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)

    LaunchedEffect(addonMeta, tracked) {
        if (addonMeta == null) return@LaunchedEffect
        val trackedStatusValue = tracked?.status ?: ""
        val trackedSeason = if (trackedStatusValue == WatchlistStatus.WATCHING.value) tracked?.seasonProgress else null
        val firstAddonSeason = addonMeta?.videos?.mapNotNull { it.season }?.filter { it > 0 }?.minOrNull()
        selectedSeason = trackedSeason ?: firstAddonSeason ?: 1
    }

    // Derived display values (pure Hub metadata)
    val displayTitle = addonMeta?.name ?: id
    val displayBackdrop = addonMeta?.background ?: addonMeta?.poster
    val displayLogo = addonMeta?.logo
    val displayYear = addonMeta?.year ?: ""
    // Certification: prefer the explicit app_extras field (AIO/TMDB); otherwise try to
    // recover it from releaseInfo, where Cinemeta appends it after the year (e.g. "2014  PG-13").
    val displayCert = addonMeta?.appExtras?.certification?.takeIf { it.isNotBlank() }
        ?: addonMeta?.releaseInfo
            ?.replace(Regex("""^\s*\d{4}(\s*[–-]\s*\d{4})?"""), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        ?: ""
    val displayRating = addonMeta?.imdbRating ?: ""
    val displayRuntime = addonMeta?.runtime ?: ""
    val displayGenres = addonMeta?.genres ?: emptyList()
    val displayOverview = addonMeta?.description ?: ""
    val displayCast = addonMeta?.cast ?: emptyList()
    // Rich cast (headshots + character names) when the addon provides app_extras.cast.
    val displayCastDetailed = addonMeta?.appExtras?.cast ?: emptyList()
    val displayDirector = addonMeta?.director ?: emptyList()
    val displayWriter = addonMeta?.writer ?: emptyList()
    val displayCountry = addonMeta?.country?.takeIf { it.isNotBlank() } ?: ""
    val displayStatus = addonMeta?.status?.takeIf { it.isNotBlank() } ?: ""
    val displayAwards = addonMeta?.awards?.takeIf { it.isNotBlank() } ?: ""


    // Check if we have episodes
    val hasEpisodes = addonMeta?.videos?.isNotEmpty() == true
    val canResolveStreams = hasAddons && (resolvedImdbId != null || !id.startsWith("tt"))

    // Stream resolution helper
    val autoPickerEnabled = remember { browserPrefs.getBoolean("auto_select_enabled", false) }
    val preferredQuality = remember {
        browserPrefs.getString("default_video_quality", "Auto") ?: "Auto"
    }
    // autoQualityKey is used by local phone auto-picking (StreamSelector).
    // It should be empty if auto-select is disabled so the picker sheet opens.
    val autoQualityKey = remember(autoPickerEnabled, preferredQuality) {
        if (autoPickerEnabled) preferredQuality else ""
    }

    val autoMaxMbps = remember { browserPrefs.getString("auto_stream_max_mbps", "")?.toDoubleOrNull() }
    val autoAddonKey = remember { browserPrefs.getString("auto_stream_addon", "") ?: "" }
    val autoSourceTypes = remember {
        SourceTypeFilter.parseCsv(
            browserPrefs.getString("auto_stream_source_types", "")
        )
    }
    val episodesInSeason = addonMeta?.videos?.filter { it.season == selectedSeason } ?: emptyList()

    /** Helper to build visual metadata for the TV receiver. */
    fun buildVisualMetadata(episode: StremioVideo?): playbridge.VisualMetadata {
        val currentImdbId = resolvedImdbId
        val currentTmdbId = resolvedTmdbId
        return playbridge.VisualMetadata(
            title = displayTitle,
            year = displayYear,
            rating = displayRating,
            runtime = displayRuntime,
            overview = displayOverview,
            genres = displayGenres,
            cast = displayCast,
            director = displayDirector,
            backdrop_url = displayBackdrop,
            poster_url = addonMeta?.poster,
            logo_url = displayLogo,
            imdb_id = currentImdbId,
            tmdb_id = currentTmdbId?.toString(),
            season = if (isSeries) selectedSeason else null,
            episode = if (isSeries) episode?.episode else null,
            episode_title = if (isSeries) episode?.title else null
        )
    }

    /** Helper to build Hub URLs for all episodes. */
    fun buildHubPlaylist(targetEpisode: StremioVideo? = null): playbridge.PlaylistPayload? {
        val hub = hubAddon ?: return null
        val videos = addonMeta?.videos
            ?.filter { it.season != null && it.episode != null && it.season > 0 }
            ?.distinctBy { Pair(it.season, it.episode) }
            ?.sortedWith(compareBy({ it.season }, { it.episode }))
            ?: return null

        val currentImdbId = resolvedImdbId
        val items = videos.mapIndexed { index, vid ->
            val streamId = if (currentImdbId != null) "$currentImdbId:${vid.season}:${vid.episode}" else vid.id
            val streamType = if (currentImdbId != null) "series" else addonType
            
            val hubUrl = StreamingUtils.buildPlayUrl(
                hub, streamType, streamId, context
            )

            playbridge.PlayPayload(
                url = hubUrl,
                title = "$displayTitle S${vid.season}E${vid.episode}${if (vid.title.isNotBlank()) " - ${vid.title}" else ""}",
                content_type = "series",
                detected_by = "library",
                visual_metadata = buildVisualMetadata(vid)
            )
        }
            
        val startIndex = if (targetEpisode != null) {
            videos.indexOfFirst { it.season == targetEpisode.season && it.episode == targetEpisode.episode }.coerceAtLeast(0)
        } else 0

        return playbridge.PlaylistPayload(
            items = items,
            start_index = startIndex,
            visual_metadata = buildVisualMetadata(null)
        )
    }

    /**
     * Launch the in-app phone player for a just-resolved stream. For a series episode we
     * hand the player the whole show (ordered episode stream IDs) so it can resolve and
     * auto-advance episode-to-episode even without a Hub/play-endpoint addon — mirroring
     * [buildHubPlaylist]'s episode selection. Movies / single episodes play directly.
     */
    fun launchPhonePlayback(streamUrl: String, resTitle: String, episode: StremioVideo?, bingeGroup: String? = null) {
        val videos = addonMeta?.videos
            ?.filter { it.season != null && it.episode != null && it.season > 0 }
            ?.distinctBy { Pair(it.season, it.episode) }
            ?.sortedWith(compareBy({ it.season }, { it.episode }))
        if (isSeries && episode != null && videos != null && videos.size > 1) {
            val currentImdbId = resolvedImdbId
            val streamType = if (currentImdbId != null) "series" else addonType
            val streamIds = videos.map { vid ->
                if (currentImdbId != null) "$currentImdbId:${vid.season}:${vid.episode}" else vid.id
            }
            val titles = videos.map { vid ->
                "$displayTitle S${vid.season}E${vid.episode}${if (vid.title.isNotBlank()) " - ${vid.title}" else ""}"
            }
            val startIdx = videos.indexOfFirst {
                it.season == episode.season && it.episode == episode.episode
            }.coerceAtLeast(0)
            com.playbridge.sender.player.PlayerLauncher.startLazyEpisodes(
                context = context,
                firstUrl = streamUrl,
                streamIds = streamIds,
                titles = titles,
                streamType = streamType,
                startIndex = startIdx,
                forcedSource = forcedSource,
                bingeGroup = bingeGroup
            )
        } else {
            com.playbridge.sender.player.PlayerLauncher.start(context, streamUrl, resTitle)
        }
    }

    /**
     * Send a just-resolved stream to the TV. For a series episode (no Hub addon to build a
     * deterministic playlist), hand the whole show to [onStartTvEpisodeQueue] so the phone can
     * resolve & queue subsequent episodes on the TV (bingeGroup-consistent). Movies / single
     * episodes go through the normal single-play path.
     */
    fun sendToTv(payload: playbridge.PlayPayload, episode: StremioVideo?, bingeGroup: String?) {
        val videos = addonMeta?.videos
            ?.filter { it.season != null && it.episode != null && it.season > 0 }
            ?.distinctBy { Pair(it.season, it.episode) }
            ?.sortedWith(compareBy({ it.season }, { it.episode }))
        if (isSeries && episode != null && videos != null && videos.size > 1) {
            val currentImdbId = resolvedImdbId
            val streamType = if (currentImdbId != null) "series" else addonType
            val startIdx = videos.indexOfFirst {
                it.season == episode.season && it.episode == episode.episode
            }.coerceAtLeast(0)
            // bingeGroup is echoed back by the TV so the phone can recognise its own lazy-queued
            // series and resume queueing after an app restart (see TvQueueCoordinator re-attach).
            val currentPayload = payload.copy(binge_group = bingeGroup)
            val items = videos.mapIndexed { i, vid ->
                val streamId = if (currentImdbId != null) "$currentImdbId:${vid.season}:${vid.episode}" else vid.id
                val template = if (i == startIdx) {
                    currentPayload // already resolved, with this episode's metadata
                } else {
                    playbridge.PlayPayload(
                        url = "",
                        title = "$displayTitle S${vid.season}E${vid.episode}${if (vid.title.isNotBlank()) " - ${vid.title}" else ""}",
                        content_type = "series",
                        detected_by = "library",
                        visual_metadata = buildVisualMetadata(vid),
                        binge_group = bingeGroup
                    )
                }
                com.playbridge.sender.connection.TvQueueEpisode(streamId = streamId, template = template)
            }
            onStartTvEpisodeQueue(
                currentPayload,
                com.playbridge.sender.connection.TvEpisodeQueuePlan(
                    streamType = streamType,
                    forcedSource = forcedSource,
                    bingeGroup = bingeGroup,
                    startIndex = startIdx,
                    items = items
                )
            )
        } else {
            onPlayPayloadToTv(payload)
        }
    }

    val startResolution: (String, String, String, Boolean, Boolean, StremioVideo?) -> Unit = start@{ streamId, streamType, resTitle, forPhone, forcePicker, episode ->
        if (!canResolveStreams) return@start

        resolutionState = ResolutionState(
            isResolving = true,
            target = if (forPhone) ResolutionTarget.PHONE else ResolutionTarget.TV,
            episodeId = episode?.episode
        )
        forceManualInPicker = forcePicker
        currentEpisodeSelection = episode
        streamPickerTitle = resTitle
        resolvedStreams = emptyList()
        lastResolvedId = streamId
        lastResolvedType = streamType

        if (forcePicker || autoQualityKey.isEmpty()) {
            showStreamPicker = true
        }

        resolutionJob?.cancel()
        resolutionJob = scope.launch {
            addonRepository.resolveStreamsFlow(streamType, streamId, forcedSource).collect { latest ->
                resolvedStreams = latest
            }
            resolutionState = resolutionState.copy(isResolving = false)

            // Auto-pick logic
            if (!showStreamPicker && !forcePicker && autoQualityKey.isNotEmpty()) {
                val runtimeForBitrate = if (isSeries) 45 else 120
                val best = StreamSelector.selectBest(
                    streams = resolvedStreams,
                    preferredQuality = QualityFilter.fromKey(autoQualityKey),
                    maxMbps = autoMaxMbps,
                    runtimeMinutes = runtimeForBitrate,
                    preferredAddon = autoAddonKey.takeIf { it.isNotEmpty() },
                    preferredSourceTypes = autoSourceTypes
                )
                val finalSelection = best ?: resolvedStreams.firstOrNull()
                if (finalSelection != null) {
                    val streamUrl = finalSelection.stream.url
                    if (streamUrl != null) {
                        if (forPhone) {
                            launchPhonePlayback(streamUrl, resTitle, episode, finalSelection.stream.behaviorHints?.bingeGroup)
                        } else {
                            // Send to TV with metadata
                            val payload = playbridge.PlayPayload(
                                url = streamUrl,
                                title = resTitle,
                                content_type = if (isSeries) "series" else "movie",
                                detected_by = "library",
                                visual_metadata = buildVisualMetadata(episode)
                            )
                            sendToTv(payload, episode, finalSelection.stream.behaviorHints?.bingeGroup)
                        }
                    }
                } else {
                    showStreamPicker = true
                }
            }
        }
    }

    /**
     * Proxy path: resolve on the phone, pick the best stream, rewrite via
     * mediaflow-proxy, then send as a direct `play` command to the TV.
     *
     * Used when [proxyMode] != OFF — bypasses the Hub instant-play path so
     * the proxy-rewritten URL is sent directly instead.
     */
    val startProxiedResolution: (String, String, String, StremioVideo?) -> Unit = startProxy@{ streamId, streamType, resTitle, episode ->
        if (!canResolveStreams) return@startProxy
        resolutionState = ResolutionState(
            isResolving = true,
            target = ResolutionTarget.TV,
            episodeId = episode?.episode
        )
        currentEpisodeSelection = episode
        streamPickerTitle = resTitle
        resolvedStreams = emptyList()
        lastResolvedId = streamId
        lastResolvedType = streamType

        resolutionJob?.cancel()
        resolutionJob = scope.launch {
            addonRepository.resolveStreamsFlow(streamType, streamId, forcedSource).collect { latest ->
                resolvedStreams = latest
            }
            resolutionState = resolutionState.copy(isResolving = false)

            val runtimeForBitrate = if (isSeries) 45 else 120
            val best = StreamSelector.selectBest(
                streams = resolvedStreams,
                preferredQuality = QualityFilter.fromKey(autoQualityKey),
                maxMbps = autoMaxMbps,
                runtimeMinutes = runtimeForBitrate,
                preferredAddon = autoAddonKey.takeIf { it.isNotEmpty() },
                preferredSourceTypes = autoSourceTypes
            ) ?: resolvedStreams.firstOrNull()

            val streamUrl = best?.stream?.url
            if (streamUrl == null) {
                Toast.makeText(context, "No streams resolved", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Resolved streams from addons don't carry request headers (the addon
            // already signed / debrid-resolved them); pass null so the proxy encodes
            // nothing extra into the rewritten URL.
            val result = MediaflowProxy.rewrite(
                mode = proxyMode,
                proxyBase = mediaflowProxyUrl,
                password = mediaflowProxyPassword,
                sourceUrl = streamUrl,
                headers = null
            )
            onSendStreamToTv(result.url, resTitle, null, result.contentType)
            if (isSeries && episode != null) {
                onNowPlayingStarted(resolvedTmdbId ?: 0, selectedSeason, episode.episode ?: 1)
            }
        }
    }

    val triggerWatch: (String, String, String, Boolean, Boolean, StremioVideo?) -> Unit = triggerWatch@{ streamId, streamType, resTitle, forPhone, forcePicker, episode ->
        // Reconnect attempt before sending — covers the case where auto-connect
        // failed or the socket dropped while the user was on this screen.
        if (!forPhone && !isTvConnected && selectedTvDevice != null) {
            onTvDeviceSelect?.invoke(selectedTvDevice)
        }

        // 1. Proxy path (only for auto-play; long-press should still use picker)
        if (!forPhone && !forcePicker && proxyMode != MediaflowProxy.Mode.OFF && proxyAvailable) {
            startProxiedResolution(streamId, streamType, resTitle, episode)
            return@triggerWatch
        }

        // 2. Hub / Instant Play logic (only for single click)
        if (!forcePicker && hubAddon != null) {
            if (isSeries) {
                // TV Show: Send playlist
                val playlist = buildHubPlaylist(targetEpisode = episode)
                if (playlist != null) {
                    if (forPhone) {
                        // Series auto-play: hand the whole season to the in-app player so it
                        // advances episode-to-episode and exits after the last one.
                        com.playbridge.sender.player.PlayerLauncher.startPlaylist(
                            context = context,
                            urls = playlist.items.map { it.url },
                            titles = playlist.items.map { it.title ?: resTitle },
                            startIndex = playlist.start_index
                        )
                    } else {
                        onPlayPlaylistToTv(playlist)
                    }
                    return@triggerWatch
                }
            } else {
                // Movie: Send single play
                val hubUrl = StreamingUtils.buildPlayUrl(hubAddon!!, streamType, streamId, context)
                if (forPhone) {
                    com.playbridge.sender.player.PlayerLauncher.start(context, hubUrl, resTitle)
                } else {
                    onPlayPayloadToTv(playbridge.PlayPayload(
                        url = hubUrl,
                        title = resTitle,
                        content_type = "movie",
                        detected_by = "library",
                        visual_metadata = buildVisualMetadata(null)
                    ))
                }
                return@triggerWatch
            }
        }

        // 3. Default: Resolve (always used for long-press or when Hub/Proxy are unavailable)
        startResolution(streamId, streamType, resTitle, forPhone, forcePicker, episode)
    }

    // Stream picker sheet
    if (showStreamPicker) {
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = resolutionState.isResolving,
            title = streamPickerTitle,
            episodeRuntimeMinutes = if (isSeries) 45 else 120,
            forceManual = forceManualInPicker,
            themeColor = dominantColor,
            onStreamSelected = { resolved ->
                val forPhone = resolutionState.target == ResolutionTarget.PHONE
                showStreamPicker = false
                resolutionState = resolutionState.copy(isResolving = false)
                val streamUrl = resolved.stream.url ?: return@StreamPickerSheet

                if (forPhone) {
                    launchPhonePlayback(streamUrl, streamPickerTitle, currentEpisodeSelection, resolved.stream.behaviorHints?.bingeGroup)
                } else {
                    val payload = playbridge.PlayPayload(
                        url = streamUrl,
                        title = streamPickerTitle,
                        content_type = if (isSeries) "series" else "movie",
                        detected_by = "library",
                        visual_metadata = buildVisualMetadata(currentEpisodeSelection)
                    )
                    sendToTv(payload, currentEpisodeSelection, resolved.stream.behaviorHints?.bingeGroup)
                }
            },
            onRefresh = {
                val id = lastResolvedId
                val type = lastResolvedType
                if (id != null && type != null) {
                    addonRepository.clearStreamCache(type, id)
                    // Trigger resolution again with same params
                    startResolution(
                        id, type, streamPickerTitle,
                        resolutionState.target == ResolutionTarget.PHONE,
                        true, // force picker
                        currentEpisodeSelection
                    )
                }
            },
            onDismiss = {
                showStreamPicker = false
                resolutionState = ResolutionState()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        TranslucentBackground(backdropUrl = displayBackdrop, dominantColor = dominantColor)
        when {
            isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            errorMessage != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(errorMessage!!, color = Color.White)
            }
            else -> {
                val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    state = episodeListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp + navBarPadding)
                ) {
                    // Backdrop
                    item {
                        BackdropSection(
                            backdropUrl = displayBackdrop,
                            logoUrl = displayLogo,
                            title = displayTitle,
                            year = displayYear,
                            certification = displayCert,
                            rating = displayRating,
                            runtime = displayRuntime,
                            genres = displayGenres,
                            omdbDetails = null,
                            onColorExtracted = { dominantColor = it }
                        )
                    }

                    // Play button
                    item {
                        if (!isSeries || !hasEpisodes) {
                            // Movie or series with no episodes
                            val firstEpisodeForTv = episodesInSeason.firstOrNull()
                            SplitPlayButton(
                                isTvResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.TV,
                                isPhoneResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.PHONE,
                                resolvingLabel = "Finding streams…",
                                hasAddons = hasAddons,
                                hasImdbId = resolvedImdbId != null || resolvedTmdbId != null,
                                canResolveStreams = canResolveStreams,
                                watchProviders = emptyList(),
                                tvName = tvName,
                                isTvConnected = isTvConnected,
                                watchOnTv = watchOnTv,
                                onWatchOnTvChange = {
                                    watchOnTv = it
                                    browserPrefs.edit().putBoolean("watch_on_tv", it).apply()
                                },
                                selectedTvDevice = selectedTvDevice,
                                onOpenConnectionScreen = onOpenConnectionScreen,
                                onWatchOnTv = {
                                    if (isSeries && firstEpisodeForTv != null) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${firstEpisodeForTv.episode ?: 1}" else firstEpisodeForTv.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${firstEpisodeForTv.episode ?: 1}"
                                        triggerWatch(streamId, streamType, title, false, false, firstEpisodeForTv)
                                    } else {
                                        val streamId = resolvedImdbId ?: id
                                        triggerWatch(streamId, addonType, displayTitle, false, false, null)
                                    }
                                },
                                onWatchOnTvLongClick = {
                                    // Long-press: send to TV with forcePicker=true so the TV shows
                                    // its own stream picker. BrowserActivity no longer diverts this
                                    // into the phone-side CastSheet.
                                    if (isSeries && firstEpisodeForTv != null) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${firstEpisodeForTv.episode ?: 1}" else firstEpisodeForTv.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${firstEpisodeForTv.episode ?: 1}"
                                        triggerWatch(streamId, streamType, title, false, true, firstEpisodeForTv)
                                    } else {
                                        val streamId = resolvedImdbId ?: id
                                        triggerWatch(streamId, addonType, displayTitle, false, true, null)
                                    }
                                },
                                onWatchOnPhone = {
                                    if (isSeries && firstEpisodeForTv != null) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${firstEpisodeForTv.episode ?: 1}" else firstEpisodeForTv.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${firstEpisodeForTv.episode ?: 1}"
                                        triggerWatch(streamId, streamType, title, true, false, firstEpisodeForTv)
                                    } else {
                                        val streamId = resolvedImdbId ?: id
                                        triggerWatch(streamId, addonType, displayTitle, true, false, null)
                                    }
                                },
                                onWatchOnPhoneLongClick = {
                                    if (isSeries && firstEpisodeForTv != null) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${firstEpisodeForTv.episode ?: 1}" else firstEpisodeForTv.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${firstEpisodeForTv.episode ?: 1}"
                                        triggerWatch(streamId, streamType, title, true, true, firstEpisodeForTv)
                                    } else {
                                        val streamId = resolvedImdbId ?: id
                                        triggerWatch(streamId, addonType, displayTitle, true, true, null)
                                    }
                                },
                                playerMode = playerMode,
                                onPlayerModeChange = { mode ->
                                    scope.launch {
                                        settingsRepository.setTvPlayerMode(mode)
                                    }
                                },
                                proxyAvailable = proxyAvailable,
                                proxyMode = proxyMode,
                                onProxyModeChange = { proxyMode = it },
                                themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                            )
                        } else {
                            // Series with episodes — play the next unwatched episode
                            val nextUnwatchedEpisode = episodesInSeason.firstOrNull { ep ->
                                val epNum = ep.episode ?: 0
                                val epWatched = tracked?.let { entity ->
                                    if (entity.status != WatchlistStatus.WATCHING.value &&
                                        entity.status != WatchlistStatus.COMPLETED.value) return@let false
                                    val trackedSeason = entity.seasonProgress ?: return@let false
                                    val trackedEp    = entity.episodeProgress ?: return@let false
                                    when {
                                        selectedSeason < trackedSeason -> true
                                        selectedSeason == trackedSeason -> epNum <= trackedEp
                                        else -> false
                                    }
                                } ?: false
                                !epWatched
                            } ?: episodesInSeason.firstOrNull()
                            if (nextUnwatchedEpisode != null) {
                                val epLabel = "S${selectedSeason.toString().padStart(2, '0')}E${(nextUnwatchedEpisode.episode ?: 1).toString().padStart(2, '0')}"
                                SplitPlayButton(
                                    isTvResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.TV,
                                    isPhoneResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.PHONE,
                                    resolvingLabel = "Finding streams…",
                                    hasAddons = hasAddons,
                                    hasImdbId = resolvedImdbId != null || resolvedTmdbId != null,
                                    canResolveStreams = canResolveStreams,
                                    watchProviders = emptyList(),
                                    tvName = tvName,
                                    isTvConnected = isTvConnected,
                                    watchOnTv = watchOnTv,
                                    onWatchOnTvChange = {
                                        watchOnTv = it
                                        browserPrefs.edit().putBoolean("watch_on_tv", it).apply()
                                    },
                                    watchLabel = "Watch $epLabel",
                                    selectedTvDevice = selectedTvDevice,
                                    onOpenConnectionScreen = onOpenConnectionScreen,
                                    onWatchOnTv = {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${nextUnwatchedEpisode.episode ?: 1}" else nextUnwatchedEpisode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${nextUnwatchedEpisode.episode ?: 1}"
                                        triggerWatch(streamId, streamType, title, false, false, nextUnwatchedEpisode)
                                    },
                                    onWatchOnTvLongClick = {
                                        // Long-press: send to TV with forcePicker=true so the TV shows
                                        // its own stream picker.
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${nextUnwatchedEpisode.episode ?: 1}" else nextUnwatchedEpisode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${nextUnwatchedEpisode.episode ?: 1}"
                                        triggerWatch(streamId, streamType, title, false, true, nextUnwatchedEpisode)
                                    },
                                    onWatchOnPhone = {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${nextUnwatchedEpisode.episode ?: 1}" else nextUnwatchedEpisode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${nextUnwatchedEpisode.episode ?: 1}"
                                        triggerWatch(streamId, streamType, title, true, false, nextUnwatchedEpisode)
                                    },
                                    onWatchOnPhoneLongClick = {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${nextUnwatchedEpisode.episode ?: 1}" else nextUnwatchedEpisode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${nextUnwatchedEpisode.episode ?: 1}"
                                        triggerWatch(streamId, streamType, title, true, true, nextUnwatchedEpisode)
                                    },
                                    playerMode = playerMode,
                                    onPlayerModeChange = { mode ->
                                        scope.launch {
                                            settingsRepository.setTvPlayerMode(mode)
                                        }
                                    },
                                    proxyAvailable = proxyAvailable,
                                    proxyMode = proxyMode,
                                    onProxyModeChange = { proxyMode = it },
                                    themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Action buttons — watchlist requires a TMDB id; the trailer button
                    // shows whenever any trailer (TMDB or addon) is available.
                    val tmdbIdForActions = resolvedTmdbId
                    if (tmdbIdForActions != null || trailerOptions.isNotEmpty()) {
                        item {
                            ActionButtons(
                                isWatchlisted = isWatchlisted,
                                tracked = tracked,
                                canWatchlist = tmdbIdForActions != null,
                                onToggleWatchlist = {
                                    if (tmdbIdForActions != null) {
                                        viewModel.toggleWatchlist(
                                            tmdbId = tmdbIdForActions,
                                            mediaType = type,
                                            title = displayTitle,
                                            posterUrl = addonMeta?.poster,
                                            year = displayYear,
                                            rating = displayRating
                                        )
                                    }
                                },
                                trailers = trailerOptions,
                                onPlayTrailer = onPlayTrailer,
                                themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Overview / synopsis — kept up top, above seasons & episodes.
                    item {
                        Text(
                            text = displayOverview,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            color = Color.White
                        )
                    }

                    // Season chips — only for series with episodes
                    if (isSeries && hasEpisodes) {
                        item {
                            val seasons = addonMeta?.videos
                                ?.mapNotNull { it.season }
                                ?.filter { it > 0 }
                                ?.distinct()
                                ?.sorted()
                                ?: emptyList()
                            val chipScrollState = rememberScrollState()
                            val isScrollable = chipScrollState.maxValue > 0
                            val isAtEnd = isScrollable && chipScrollState.value >= chipScrollState.maxValue
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isScrollable) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            if (isAtEnd) chipScrollState.animateScrollTo(0)
                                            else chipScrollState.animateScrollTo(chipScrollState.maxValue)
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (isAtEnd) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                                            contentDescription = if (isAtEnd) "Scroll to start" else "Scroll to end"
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(chipScrollState)
                                        .padding(vertical = 8.dp)
                                        .padding(start = if (isScrollable) 0.dp else 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    seasons.forEach { seasonNumber ->
                                        val isSelected = selectedSeason == seasonNumber
                                        val themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                                        val contentColor = if (themeColor.luminance() > 0.5f) Color.Black else Color.White
                                        val haptic = LocalHapticFeedback.current
                                        Surface(
                                            modifier = Modifier.combinedClickable(
                                                onClick = {
                                                    if (isSelected) return@combinedClickable
                                                    selectedSeason = seasonNumber
                                                }
                                            ),
                                            shape = RoundedCornerShape(20.dp),
                                            color = if (isSelected) themeColor else Color.White.copy(alpha = 0.1f),
                                        ) {
                                            Text(
                                                text = "S$seasonNumber",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (isSelected) contentColor else Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { episodesAscending = !episodesAscending },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (episodesAscending) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                        contentDescription = if (episodesAscending) "Sort descending" else "Sort ascending",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    // Episodes list
                    if (isSeries && hasEpisodes) {
                        val episodes = (addonMeta?.videos?.filter { it.season == selectedSeason } ?: emptyList())
                            .let { if (episodesAscending) it else it.reversed() }
                        items(episodes.size) { index ->
                            val episode = episodes[index]
                            val epNum = episode.episode ?: 0
                            val isEpWatched = tracked?.let { entity ->
                                if (entity.status != WatchlistStatus.WATCHING.value &&
                                    entity.status != WatchlistStatus.COMPLETED.value) return@let false
                                val trackedSeason = entity.seasonProgress ?: return@let false
                                val trackedEp    = entity.episodeProgress ?: return@let false
                                when {
                                    selectedSeason < trackedSeason -> true
                                    selectedSeason == trackedSeason -> epNum <= trackedEp
                                    else -> false
                                }
                            } ?: false
                            EpisodeItem(
                                episode = episode,
                                hasAddon = canResolveStreams,
                                isResolving = resolutionState.isResolving && resolutionState.episodeId == epNum,
                                isWatched = isEpWatched,
                                onClick = {
                                    if (canResolveStreams) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${epNum}" else episode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${epNum}"
                                        triggerWatch(streamId, streamType, title, !watchOnTv, false, episode)
                                    }
                                },
                                onLongClick = {
                                    if (canResolveStreams) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${epNum}" else episode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${epNum}"
                                        triggerWatch(streamId, streamType, title, !watchOnTv, true, episode)
                                    }
                                },
                                onToggleWatched = {
                                    val currentTmdbId = resolvedTmdbId
                                    if (currentTmdbId != null) {
                                        if (isEpWatched) {
                                            viewModel.setEpisodeProgress(currentTmdbId, selectedSeason, (epNum - 1).coerceAtLeast(0))
                                        } else {
                                            viewModel.upsertTracked(
                                                tmdbId = currentTmdbId,
                                                mediaType = "tv",
                                                title = displayTitle,
                                                posterUrl = addonMeta?.poster,
                                                year = displayYear,
                                                rating = displayRating,
                                                status = WatchlistStatus.WATCHING,
                                            )
                                            viewModel.setEpisodeProgress(currentTmdbId, selectedSeason, epNum)
                                        }
                                    }
                                },
                                themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Cast & credits — moved below the episode list so the synopsis and
                    // seasons stay near the top. Each row renders only when data is present.
                    item {
                        if (displayCastDetailed.isNotEmpty()) {
                            CastCarousel(
                                members = displayCastDetailed,
                                themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                            )
                        } else if (displayCast.isNotEmpty()) {
                            Text(
                                text = "Starring: ${displayCast.take(5).joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }

                        if (displayDirector.isNotEmpty()) {
                            Text(
                                text = "Director: ${displayDirector.joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                            )
                        }

                        if (displayWriter.isNotEmpty()) {
                            Text(
                                text = "Writer: ${displayWriter.joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                            )
                        }

                        if (displayCountry.isNotEmpty()) {
                            Text(
                                text = "Country: $displayCountry",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                            )
                        }

                        if (displayStatus.isNotEmpty()) {
                            Text(
                                text = "Status: $displayStatus",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                            )
                        }

                        if (displayAwards.isNotEmpty()) {
                            Text(
                                text = "Awards: $displayAwards",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // ID + metadata-source info chips
                    // addonNativeId: a non-numeric, non-IMDB id such as "kitsu:12345"
                    val addonNativeId = id.takeIf { id.toIntOrNull() == null && !id.startsWith("tt") }
                    val hasTmdbData = resolvedTmdbId != null
                    val hasAddonMeta = addonMeta != null
                    val showAnyChip = resolvedImdbId != null || addonNativeId != null || hasTmdbData || hasAddonMeta
                    if (showAnyChip) {
                        item {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 24.dp, vertical = 16.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // IMDb ID chip (movies + shows resolved via IMDB/TMDB)
                                resolvedImdbId?.let { imdbId ->
                                    DetailInfoChip(label = "IMDb", value = imdbId)
                                }

                                // Addon-native ID chip (e.g. kitsu:12345, mal:678)
                                if (resolvedImdbId == null && addonNativeId != null) {
                                    val prefix = if (addonNativeId.contains(":"))
                                        addonNativeId.substringBefore(":").replaceFirstChar { it.uppercase() }
                                    else "ID"
                                    val value = if (addonNativeId.contains(":"))
                                        addonNativeId.substringAfter(":")
                                    else addonNativeId
                                    DetailInfoChip(label = prefix, value = value)
                                }

                                // Metadata source chip
                                val displaySource = if (addonMetaSource == hubAddon?.name && forcedSource != null) forcedSource else addonMetaSource
                                val sourceLabel = displaySource?.let { "via $it" }
                                sourceLabel?.let { DetailInfoChip(label = "Source", value = it) }                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }


        TopAppBar(
            title = { },
            windowInsets = TopAppBarDefaults.windowInsets, // Respect status bar
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                val btnBg = dominantColor ?: Color.Black
                val btnIcon = if (btnBg.luminance() > 0.5f) Color.Black else Color.White
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight().padding(start = 12.dp)) {
                    Surface(
                        onClick = onBack,
                        shape = CircleShape,
                        color = btnBg,
                        shadowElevation = 4.dp
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                "Back",
                                tint = btnIcon,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            },
            actions = {
                val btnBg = dominantColor ?: Color.Black
                val btnIcon = if (btnBg.luminance() > 0.5f) Color.Black else Color.White
                var showMenu by remember { mutableStateOf(false) }

                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight().padding(end = 12.dp)) {
                    Box {
                        Surface(
                            onClick = { showMenu = true },
                            shape = CircleShape,
                            color = btnBg,
                            shadowElevation = 4.dp
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    "More",
                                    tint = btnIcon,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = (dominantColor ?: Color.Black).copy(alpha = 0.95f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.border(
                                1.dp,
                                (dominantColor ?: Color.White).copy(alpha = 0.2f),
                                RoundedCornerShape(12.dp)
                            )
                        ) {
                            val themeColor = dominantColor ?: Color.Black
                            val contentColor = if (themeColor.luminance() > 0.5f) Color.Black else Color.White

                            DropdownMenuItem(
                                text = {
                                    Text("Share", color = contentColor)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null, tint = contentColor)
                                },
                                onClick = {
                                    showMenu = false
                                    onShare(displayTitle, resolvedImdbId)
                                },
                                colors = MenuDefaults.itemColors(
                                    textColor = contentColor,
                                    leadingIconColor = contentColor
                                )
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            )
        )

        // Remote shortcut — shown only when a TV is connected (gated by a non-null callback).
        // Matches the back/more buttons: circular, filled with the poster's dominant color.
        onOpenRemote?.let { openRemote ->
            val fabBg = dominantColor ?: Color.Black
            val fabIcon = if (fabBg.luminance() > 0.5f) Color.Black else Color.White
            Surface(
                onClick = openRemote,
                shape = CircleShape,
                color = fabBg,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            ) {
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.SettingsRemote,
                        contentDescription = "Remote",
                        tint = fabIcon,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}
