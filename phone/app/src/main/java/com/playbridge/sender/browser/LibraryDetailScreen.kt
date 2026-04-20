package com.playbridge.sender.browser

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
    onPlayContent: (com.playbridge.protocol.ContentPlayPayload) -> Unit = {},
    onPlayTrailer: ((String) -> Unit)? = null,
    onPlayPlaylist: (items: List<com.playbridge.protocol.PlayPayload>) -> Unit = {},
    onQueueAdd: (com.playbridge.protocol.PlayPayload) -> Unit = {},
    onPlaylistJump: (Int) -> Unit = {},
    playlistState: PlaylistUiState? = null,
    onNowPlayingStarted: (tmdbId: Int, season: Int, startEpisode: Int) -> Unit = { _, _, _ -> },
    highlightSeason: Int? = null,
    highlightEpisode: Int? = null,
    viewModel: LibraryViewModel,
    tvName: String? = null,
    isTvConnected: Boolean = false,
    availableTvDevices: List<TvDevice> = emptyList(),
    selectedTvDevice: TvDevice? = null,
    onTvDeviceSelect: ((TvDevice) -> Unit)? = null,
    /**
     * Send a pre-resolved stream URL (e.g. after applying mediaflow-proxy rewrite
     * on the phone) directly to the TV as a `play` command. Used when the proxy
     * chip is non-OFF — the TV can't rewrite URLs itself, so phone must resolve
     * + rewrite + dispatch.
     */
    onSendStreamToTv: (url: String, title: String, headers: Map<String, String>?, contentType: String?) -> Unit = { _, _, _, _ -> },
    onBack: () -> Unit,
    onShare: (title: String, imdbId: String?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val omdb = remember { OmdbRepository(context) }
    val subtitleService = remember { StremioSubtitleService(addonRepository) }
    val tvdb = remember { TvdbRepository(context) }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Determine if this is a series or movie
    val addonType = if (type == "tv") "series" else type
    val isSeries = type == "tv" || type == "series"

    // Primary state for TMDB content
    var movieDetails by remember { mutableStateOf<TmdbMovieDetails?>(null) }
    var tvDetails by remember { mutableStateOf<TmdbTvDetails?>(null) }
    var addonMeta by remember { mutableStateOf<StremioMetaDetail?>(null) }
    var omdbDetails by remember { mutableStateOf<OmdbResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var trailerUrl by remember { mutableStateOf<String?>(null) }
    var watchProviders by remember { mutableStateOf<List<TmdbWatchProvider>>(emptyList()) }
    var hasAddons by remember { mutableStateOf(false) }
    var selectedSeason by remember { mutableIntStateOf(highlightSeason ?: 1) }
    var resolvedTmdbId by remember { mutableStateOf<Int?>(null) }
    var resolvedImdbId by remember { mutableStateOf<String?>(null) }
    /** Name of the addon that supplied [addonMeta], e.g. "Cinemeta" or "Kitsu". Null until loaded. */
    var addonMetaSource by remember { mutableStateOf<String?>(null) }

    // Stream resolution state
    var resolvedStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var resolutionState by remember { mutableStateOf(ResolutionState()) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var streamPickerTitle by remember { mutableStateOf("Select Stream") }
    var forceManualInPicker by remember { mutableStateOf(false) }
    var lastResolvedId by remember { mutableStateOf<String?>(null) }
    var lastResolvedType by remember { mutableStateOf<String?>(null) }
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
    var playerMode by remember { mutableStateOf(browserPrefs.getString("tv_player_mode", "tv") ?: "tv") }

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

    // Load TMDB/TVDB/Addon content based on routing logic
    LaunchedEffect(id, type) {
        isLoading = true
        errorMessage = null

        // Reset state
        movieDetails = null
        tvDetails = null
        addonMeta = null
        omdbDetails = null
        resolvedTmdbId = null
        resolvedImdbId = null
        addonMetaSource = null

        val useTvdb = isSeries && tvdb.shouldUseTvdb()

        when {
            // A: Addon-native IDs (skip TMDB/TVDB lookup)
            id.contains(":") && !id.startsWith("tt") && !id.startsWith("tvdb:") -> {
                val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, id) }.getOrNull()
                addonMeta = metaResult?.first
                addonMetaSource = metaResult?.second
            }

            // B: Series Routing (TVDB priority)
            useTvdb -> {
                when {
                    id.toIntOrNull() != null -> {
                        // Numeric TMDB ID -> need cross-reference
                        val numericId = id.toInt()
                        resolvedTmdbId = numericId
                        val tmdbSeries = tmdb.getTvDetails(numericId)
                        tvDetails = tmdbSeries
                        val tvdbId = tmdbSeries?.externalIds?.tvdbId
                        if (tvdbId != null) {
                            val tvdbEpisodes = tvdb.getEpisodes(tvdbId)
                            val tvdbSeriesDetails = tvdb.getSeriesDetails(tvdbId)
                            addonMeta = tvdb.buildStremioMeta(id, addonType, tmdbSeries, tvdbEpisodes, tvdbSeriesDetails)
                            addonMetaSource = "TVDB"
                        }
                        resolvedImdbId = tmdbSeries?.imdbId
                    }
                    id.startsWith("tt") -> {
                        // IMDb ID -> Parallel TMDB lookup + TVDB search, then synthesize
                        resolvedImdbId = id
                        var localTvDetails: com.playbridge.sender.data.library.TmdbTvDetails? = null
                        val tmdbJob = scope.launch {
                            val result = tmdb.findByImdbId(id)
                            val tvId = result?.tvResults?.firstOrNull()?.id
                            if (tvId != null) {
                                resolvedTmdbId = tvId
                                val details = tmdb.getTvDetails(tvId)
                                tvDetails = details
                                localTvDetails = details
                            }
                        }
                        val tvdbJob = scope.launch {
                            val tvdbSearch = tvdb.findSeriesByImdbId(id)
                            val tvdbId = tvdbSearch?.tvdbId?.toIntOrNull()
                            if (tvdbId != null) {
                                val tvdbEpisodes = tvdb.getEpisodes(tvdbId)
                                val tvdbSeriesDetails = tvdb.getSeriesDetails(tvdbId)
                                // Wait for TMDB job so localTvDetails is populated before building meta
                                tmdbJob.join()
                                addonMeta = tvdb.buildStremioMeta(id, addonType, localTvDetails, tvdbEpisodes, tvdbSeriesDetails)
                                addonMetaSource = "TVDB"
                            }
                        }
                        tmdbJob.join()
                        tvdbJob.join()
                    }
                    id.startsWith("tvdb:") -> {
                        // Direct TVDB ID
                        val tvdbId = id.removePrefix("tvdb:").toIntOrNull()
                        if (tvdbId != null) {
                            val tvdbEpisodes = tvdb.getEpisodes(tvdbId)
                            val tvdbSeriesDetails = tvdb.getSeriesDetails(tvdbId)
                            addonMeta = tvdb.buildStremioMeta(id, addonType, null, tvdbEpisodes, tvdbSeriesDetails)
                            addonMetaSource = "TVDB"
                        }
                    }
                }

                // Fallback: If TVDB return no episodes, try addon
                if (addonMeta == null || addonMeta!!.videos.isEmpty()) {
                    val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, resolvedImdbId ?: id) }.getOrNull()
                    if (metaResult != null) {
                        addonMeta = metaResult.first
                        addonMetaSource = metaResult.second
                    }
                }
            }

            // C: Standard Path (Movies or Series when TVDB is off)
            else -> {
                if (id.toIntOrNull() != null) {
                    val numericId = id.toInt()
                    resolvedTmdbId = numericId
                    if (isSeries) {
                        val tv = tmdb.getTvDetails(numericId)
                        tvDetails = tv
                        resolvedImdbId = tv?.imdbId
                    } else {
                        val movie = tmdb.getMovieDetails(numericId)
                        movieDetails = movie
                        resolvedImdbId = movie?.imdbId
                    }
                } else if (id.startsWith("tt")) {
                    resolvedImdbId = id
                    val result = tmdb.findByImdbId(id)
                    if (result == null) {
                        errorMessage = "Could not reach TMDB. Check your API key and connection."
                    } else if (isSeries && result.tvResults.isNotEmpty()) {
                        val tvId = result.tvResults.first().id
                        resolvedTmdbId = tvId
                        tvDetails = tmdb.getTvDetails(tvId)
                    } else if (!isSeries && result.movieResults.isNotEmpty()) {
                        val movieId = result.movieResults.first().id
                        resolvedTmdbId = movieId
                        movieDetails = tmdb.getMovieDetails(movieId)
                    } else if (isSeries && result.movieResults.isNotEmpty()) {
                        // Type mismatch fallback: found as movie, display it anyway
                        val movieId = result.movieResults.first().id
                        resolvedTmdbId = movieId
                        movieDetails = tmdb.getMovieDetails(movieId)
                    } else if (!isSeries && result.tvResults.isNotEmpty()) {
                        // Type mismatch fallback: found as TV show, display it anyway
                        val tvId = result.tvResults.first().id
                        resolvedTmdbId = tvId
                        tvDetails = tmdb.getTvDetails(tvId)
                    } else {
                        errorMessage = "\"$id\" was not found on TMDB."
                    }
                }

                // Load Addon Meta
                val metaId = resolvedImdbId ?: id
                val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, metaId) }.getOrNull()
                addonMeta = metaResult?.first
                addonMetaSource = metaResult?.second
            }
        }

        // Common supplementary data (Trailers, Providers, OMDB)
        resolvedTmdbId?.let { tmdbId ->
            if (isSeries) {
                trailerUrl = tmdb.getTvVideos(tmdbId)?.bestTrailerUrl
                watchProviders = tmdb.getTvWatchProviders(tmdbId)
            } else {
                trailerUrl = tmdb.getMovieVideos(tmdbId)?.bestTrailerUrl
                watchProviders = tmdb.getMovieWatchProviders(tmdbId)
            }
        }
        resolvedImdbId?.let { imdbId ->
            if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(imdbId)
        }

        // Error if nothing loaded
        if (movieDetails == null && tvDetails == null && addonMeta == null && errorMessage == null) {
            errorMessage = "Could not load details for \"$id\"."
        }

        isLoading = false
        hasAddons = addonRepository.hasAnyAddons()
    }

    // Watchlist state (gated on resolvedTmdbId)
    val isWatchlisted by remember(resolvedTmdbId) {
        resolvedTmdbId?.let { viewModel.isWatchlisted(it) }
            ?: kotlinx.coroutines.flow.flowOf(false)
    }.collectAsState(initial = false)

    val tracked by remember(resolvedTmdbId) {
        resolvedTmdbId?.let { viewModel.getTracked(it) }
            ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)

    // Season initialization (after tracked is set)
    LaunchedEffect(addonMeta, tracked) {
        if (addonMeta == null && tvDetails == null) return@LaunchedEffect
        val trackedSeason = tracked?.takeIf { it.status == WatchlistStatus.WATCHING.value }?.seasonProgress
        val firstAddonSeason = addonMeta?.videos?.mapNotNull { it.season }?.filter { it > 0 }?.minOrNull()
        selectedSeason = trackedSeason ?: highlightSeason ?: firstAddonSeason ?: 1
    }

    // Derived display values (prefer addon meta, fallback to TMDB)
    val displayTitle = addonMeta?.name ?: tvDetails?.name ?: movieDetails?.title ?: id
    val displayBackdrop = addonMeta?.background ?: tvDetails?.backdropUrl ?: movieDetails?.backdropUrl ?: addonMeta?.poster
    val displayLogo = tvDetails?.logoUrl ?: movieDetails?.logoUrl
    val displayYear = addonMeta?.year ?: tvDetails?.year ?: movieDetails?.year ?: ""
    val displayCert = tvDetails?.certification ?: movieDetails?.certification ?: ""
    val displayRating = addonMeta?.imdbRating ?: tvDetails?.rating ?: movieDetails?.rating ?: ""
    val displayRuntime = addonMeta?.runtime ?: run {
        if (isSeries) tvDetails?.let { "${it.numberOfSeasons} Season${if (it.numberOfSeasons != 1) "s" else ""}" } ?: ""
        else movieDetails?.runtimeFormatted ?: ""
    }
    val displayGenres = addonMeta?.genres?.takeIf { it.isNotEmpty() }
        ?: tvDetails?.genres?.map { it.name } ?: movieDetails?.genres?.map { it.name } ?: emptyList()
    val displayOverview = addonMeta?.description ?: tvDetails?.overview ?: movieDetails?.overview ?: ""
    val displayCast = addonMeta?.cast?.takeIf { it.isNotEmpty() } ?: tvDetails?.cast ?: movieDetails?.cast ?: emptyList()
    val displayDirector = if (!isSeries) movieDetails?.director ?: "" else ""

    // Check if we have episodes
    val hasEpisodes = addonMeta?.videos?.isNotEmpty() == true
    val canResolveStreams = hasAddons && (resolvedImdbId != null || !id.startsWith("tt"))

    // Stream resolution helper
    val autoQualityKey = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE).getString("auto_stream_quality", "") ?: "" }
    val autoMaxMbps = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE).getString("auto_stream_max_mbps", "")?.toDoubleOrNull() }
    val autoAddonKey = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE).getString("auto_stream_addon", "") ?: "" }
    val autoSourceTypes = remember {
        SourceTypeFilter.parseCsv(
            context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE).getString("auto_stream_source_types", "")
        )
    }
    val episodesInSeason = addonMeta?.videos?.filter { it.season == selectedSeason } ?: emptyList()

    // Unified stream resolution function
    val startResolution: (String, String, String, Boolean, Boolean, StremioVideo?) -> Unit = start@{ streamId, streamType, resTitle, forPhone, forcePicker, episode ->
        if (!canResolveStreams) return@start

        if (!forPhone) {
            // TV target: skip phone resolution, send content metadata instead
            scope.launch {
                val runtimeMinutes = if (isSeries) tvDetails?.typicalEpisodeRuntimeMinutes
                                     else movieDetails?.runtime
                val payload = buildContentPayload(
                    isSeries = isSeries,
                    rawId = id,
                    resolvedImdbId = resolvedImdbId,
                    resolvedTmdbId = resolvedTmdbId,
                    displayTitle = displayTitle,
                    displayYear = displayYear,
                    displayRating = displayRating,
                    displayRuntime = displayRuntime,
                    displayOverview = displayOverview,
                    displayGenres = displayGenres,
                    displayCast = displayCast,
                    displayDirector = displayDirector,
                    displayBackdrop = displayBackdrop,
                    displayPoster = addonMeta?.poster ?: tvDetails?.posterUrl ?: movieDetails?.posterUrl,
                    displayLogo = displayLogo,
                    selectedSeason = selectedSeason,
                    currentEpisodeSelection = episode,
                    addonMeta = addonMeta,
                    addonRepository = addonRepository,
                    forcePicker = forcePicker,
                    autoQuality = autoQualityKey,
                    autoMaxMbps = autoMaxMbps,
                    preferredAddonName = autoAddonKey,
                    preferredSourceTypeKeys = autoSourceTypes.map { it.key },
                    episodeRuntimeMinutes = runtimeMinutes
                )
                if (payload != null) {
                    onPlayContent(payload)
                } else {
                    Toast.makeText(context, "No stream-capable addons found", Toast.LENGTH_SHORT).show()
                }
            }
            return@start
        }

        resolutionState = ResolutionState(
            isResolving = true,
            target = ResolutionTarget.PHONE,
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
            addonRepository.resolveStreamsFlow(streamType, streamId).collect { latest ->
                resolvedStreams = latest
            }
            resolutionState = resolutionState.copy(isResolving = false)

            // Auto-pick logic
            if (!showStreamPicker && !forcePicker && autoQualityKey.isNotEmpty()) {
                val runtimeForBitrate = if (isSeries) tvDetails?.typicalEpisodeRuntimeMinutes
                                        else movieDetails?.runtime
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
                            openInExternalPlayer(context, streamUrl, null, null)
                        } else {
                            // This branch is technically unreachable now because !forPhone is handled at the start
                            // of startResolution, but we keep it simplified to match the new signature.
                            onPlayStream(streamUrl, resTitle)
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
     * Used when [proxyMode] != OFF — ContentPlayPayload can't carry a pre-rewritten
     * URL (the TV resolves streams itself), so we bypass that flow entirely.
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
            addonRepository.resolveStreamsFlow(streamType, streamId).collect { latest ->
                resolvedStreams = latest
            }
            resolutionState = resolutionState.copy(isResolving = false)

            val runtimeForBitrate = if (isSeries) tvDetails?.typicalEpisodeRuntimeMinutes
                                    else movieDetails?.runtime
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
            Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Wrapper around [startResolution] that (a) attempts a fresh TV connection if
     * we're in TV mode but not connected, and (b) routes through the proxy path
     * when proxy mode is non-OFF.
     */
    val triggerWatch: (String, String, String, Boolean, Boolean, StremioVideo?) -> Unit = triggerWatch@{ streamId, streamType, resTitle, forPhone, forcePicker, episode ->
        // Reconnect attempt before sending — covers the case where auto-connect
        // failed or the socket dropped while the user was on this screen.
        if (!forPhone && !isTvConnected && selectedTvDevice != null) {
            onTvDeviceSelect?.invoke(selectedTvDevice)
        }
        if (!forPhone && proxyMode != MediaflowProxy.Mode.OFF && proxyAvailable) {
            startProxiedResolution(streamId, streamType, resTitle, episode)
        } else {
            startResolution(streamId, streamType, resTitle, forPhone, forcePicker, episode)
        }
    }

    // Stream picker sheet
    if (showStreamPicker) {
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = resolutionState.isResolving,
            title = streamPickerTitle,
            episodeRuntimeMinutes = if (isSeries) tvDetails?.typicalEpisodeRuntimeMinutes
                                    else movieDetails?.runtime,
            forceManual = forceManualInPicker,
            themeColor = dominantColor,
            onStreamSelected = { resolved ->
                val forPhone = resolutionState.target == ResolutionTarget.PHONE
                showStreamPicker = false
                resolutionState = resolutionState.copy(isResolving = false)
                val streamUrl = resolved.stream.url ?: return@StreamPickerSheet

                if (forPhone) {
                    openInExternalPlayer(context, streamUrl, null, null)
                } else {
                    scope.launch {
                        val subtitles = if (resolvedImdbId != null && currentEpisodeSelection != null && isSeries) {
                            val episode = currentEpisodeSelection!!
                            val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            val prefLang = prefs.getString("preferred_subtitle_lang", "") ?: ""

                            if (prefLang.isNotEmpty()) {
                                try {
                                    val allSubs = subtitleService.getSubtitlesForEpisode(resolvedImdbId!!, selectedSeason, episode.episode ?: 0)
                                    allSubs.mapNotNull { it.url }
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            } else {
                                emptyList()
                            }
                        } else if (resolvedImdbId != null && !isSeries) {
                            val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            val prefLang = prefs.getString("preferred_subtitle_lang", "") ?: ""
                            if (prefLang.isNotEmpty()) {
                                try {
                                    val allSubs = subtitleService.getSubtitlesForMovie(resolvedImdbId!!)
                                    allSubs.mapNotNull { it.url }
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            } else {
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }

                        val effectiveRuntime = if (isSeries) tvDetails?.typicalEpisodeRuntimeMinutes
                                               else movieDetails?.runtime
                        val estimatedMbps = StreamSelector.estimatedMbps(resolved, effectiveRuntime)

                        val payload = buildContentPayload(
                            isSeries = isSeries,
                            rawId = id,
                            resolvedImdbId = resolvedImdbId,
                            resolvedTmdbId = resolvedTmdbId,
                            displayTitle = displayTitle,
                            displayYear = displayYear,
                            displayRating = displayRating,
                            displayRuntime = displayRuntime,
                            displayOverview = displayOverview,
                            displayGenres = displayGenres,
                            displayCast = displayCast,
                            displayDirector = displayDirector,
                            displayBackdrop = displayBackdrop,
                            displayPoster = addonMeta?.poster ?: tvDetails?.posterUrl ?: movieDetails?.posterUrl,
                            displayLogo = displayLogo,
                            selectedSeason = selectedSeason,
                            currentEpisodeSelection = currentEpisodeSelection,
                            addonMeta = addonMeta,
                            addonRepository = addonRepository,
                            forcePicker = false, // already in picker
                            autoQuality = resolved.stream.qualityTier,
                            autoMaxMbps = estimatedMbps,
                            preferredAddonName = resolved.addonName,
                            preferredSourceTypeKeys = autoSourceTypes.map { it.key },
                            episodeRuntimeMinutes = effectiveRuntime
                        )

                        if (payload != null) {
                            onPlayContent(payload)
                        } else {
                            // Fallback if payload build fails (rare)
                            onPlayStream(streamUrl, streamPickerTitle)
                        }

                        resolutionState = ResolutionState()
                        if (isSeries && currentEpisodeSelection != null) {
                            onNowPlayingStarted(resolvedTmdbId ?: 0, selectedSeason, currentEpisodeSelection!!.episode ?: 1)
                        }
                        Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()
                    }
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
                            omdbDetails = omdbDetails,
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
                                watchProviders = watchProviders,
                                tvName = tvName,
                                isTvConnected = isTvConnected,
                                watchOnTv = watchOnTv,
                                onWatchOnTvChange = {
    watchOnTv = it
    browserPrefs.edit().putBoolean("watch_on_tv", it).apply()
},
                                availableTvDevices = availableTvDevices,
                                selectedTvDevice = selectedTvDevice,
                                onTvDeviceSelect = onTvDeviceSelect,
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
                                    playerMode = mode
                                    browserPrefs.edit().putString("tv_player_mode", mode).apply()
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
                                    watchProviders = watchProviders,
                                    tvName = tvName,
                                    isTvConnected = isTvConnected,
                                    watchOnTv = watchOnTv,
                                    onWatchOnTvChange = {
    watchOnTv = it
    browserPrefs.edit().putBoolean("watch_on_tv", it).apply()
},
                                    watchLabel = "Watch $epLabel",
                                    availableTvDevices = availableTvDevices,
                                    selectedTvDevice = selectedTvDevice,
                                    onTvDeviceSelect = onTvDeviceSelect,
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
                                        playerMode = mode
                                        browserPrefs.edit().putString("tv_player_mode", mode).apply()
                                    },
                                    proxyAvailable = proxyAvailable,
                                    proxyMode = proxyMode,
                                    onProxyModeChange = { proxyMode = it },
                                    themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Action buttons (watchlist, trailer) — only if TMDB resolved
                    if (resolvedTmdbId != null) {
                        item {
                            ActionButtons(
                                isWatchlisted = isWatchlisted,
                                tracked = tracked,
                                onToggleWatchlist = {
                                    if (isSeries && tvDetails != null) {
                                        viewModel.toggleWatchlist(
                                            tmdbId = tvDetails!!.id,
                                            mediaType = "tv",
                                            title = tvDetails!!.name,
                                            posterUrl = tvDetails!!.posterUrl,
                                            year = tvDetails!!.year,
                                            rating = tvDetails!!.rating
                                        )
                                    } else if (movieDetails != null) {
                                        viewModel.toggleWatchlist(
                                            tmdbId = movieDetails!!.id,
                                            mediaType = "movie",
                                            title = movieDetails!!.title,
                                            posterUrl = movieDetails!!.posterUrl,
                                            year = movieDetails!!.year,
                                            rating = movieDetails!!.rating
                                        )
                                    }
                                },
                                trailerUrl = trailerUrl,
                                onPlayTrailer = onPlayTrailer,
                                themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Overview, cast, director
                    item {
                        Text(
                            text = displayOverview,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            color = Color.White
                        )

                        if (displayCast.isNotEmpty()) {
                            Text(
                                text = "Starring: ${displayCast.take(5).joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }

                        if (displayDirector.isNotBlank()) {
                            Text(
                                text = "Director: $displayDirector",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                            )
                        }
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
                        val isActivePlaylistSeason = highlightSeason != null &&
                                selectedSeason == highlightSeason &&
                                playlistState != null
                        val startEpisodeNumber = if (highlightEpisode != null && playlistState != null) {
                            highlightEpisode - playlistState.currentIndex
                        } else null
                        items(episodes.size) { index ->
                            val episode = episodes[index]
                            val epNum = episode.episode ?: 0
                            val epPlaylistIndex = if (startEpisodeNumber != null) {
                                epNum - startEpisodeNumber
                            } else -1
                            val isEpPlaying = isActivePlaylistSeason &&
                                    epPlaylistIndex == playlistState?.currentIndex
                            val isEpQueued = isActivePlaylistSeason &&
                                    epPlaylistIndex >= 0 &&
                                    epPlaylistIndex < (playlistState?.totalCount ?: 0) &&
                                    !isEpPlaying
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
                                isPlaying = isEpPlaying,
                                isResolving = resolutionState.isResolving && resolutionState.episodeId == epNum,
                                isInActivePlaylist = isEpQueued,
                                isWatched = isEpWatched,
                                onClick = {
                                    if (canResolveStreams) {
                                        if (isEpPlaying || isEpQueued) {
                                            onPlaylistJump(epPlaylistIndex)
                                        } else {
                                            val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${epNum}" else episode.id
                                            val streamType = if (resolvedImdbId != null) "series" else addonType
                                            val title = "$displayTitle S${selectedSeason}E${epNum}"
                                            triggerWatch(streamId, streamType, title, !watchOnTv, false, episode)
                                        }
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
                                    val tmdbId = resolvedTmdbId
                                    if (tmdbId != null) {
                                        if (isEpWatched) {
                                            viewModel.setEpisodeProgress(tmdbId, selectedSeason, (epNum - 1).coerceAtLeast(0))
                                        } else {
                                            viewModel.upsertTracked(
                                                tmdbId = tmdbId,
                                                mediaType = "tv",
                                                title = displayTitle,
                                                posterUrl = tvDetails?.posterUrl ?: addonMeta?.poster,
                                                year = displayYear,
                                                rating = displayRating,
                                                status = WatchlistStatus.WATCHING,
                                            )
                                            viewModel.setEpisodeProgress(tmdbId, selectedSeason, epNum)
                                        }
                                    }
                                },
                                themeColor = dominantColor ?: MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // ID + metadata-source info chips
                    // addonNativeId: a non-numeric, non-IMDB id such as "kitsu:12345"
                    val addonNativeId = id.takeIf { id.toIntOrNull() == null && !id.startsWith("tt") }
                    val hasTmdbData = movieDetails != null || tvDetails != null
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
                                val sourceLabel = when {
                                    hasTmdbData && hasAddonMeta ->
                                        "TMDB + ${addonMetaSource ?: "Addon"}"
                                    hasTmdbData -> "via TMDB"
                                    hasAddonMeta -> "via ${addonMetaSource ?: "Addon"}"
                                    else -> null
                                }
                                sourceLabel?.let { DetailInfoChip(label = "Source", value = it) }
                            }
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
    }
}
// ==================== Shared Components ====================

@Composable
private fun BackdropSection(
    backdropUrl: String?,
    logoUrl: String?,
    title: String,
    year: String,
    certification: String,
    rating: String,
    runtime: String,
    genres: List<String>,
    omdbDetails: OmdbResponse? = null,
    onColorExtracted: (Color) -> Unit = {}
) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val height = (config.screenHeightDp * 0.65).dp
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startY = size.height * 0.4f,
                            endY = size.height
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            if (backdropUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(backdropUrl)
                        .crossfade(true)
                        .allowHardware(false) // Required for Palette extraction
                        .build(),
                    onSuccess = { result ->
                        val bitmap = (result.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            Palette.from(bitmap).generate { palette ->
                                // Try to get a light vibrant or just vibrant color
                                val swatch = palette?.lightVibrantSwatch ?: palette?.vibrantSwatch ?: palette?.dominantSwatch
                                swatch?.let {
                                    val extracted = Color(it.rgb)
                                    // Make it significantly lighter (40% towards white)
                                    onColorExtracted(lerp(extracted, Color.White, 0.4f))
                                }
                            }
                        }
                    },
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
        }

        // Contents at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (logoUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(logoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    placeholder = ColorPainter(Color.Transparent),
                    error = ColorPainter(Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(130.dp)
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (genres.isNotEmpty()) {
                Text(
                    text = genres.take(3).joinToString("  "),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                if (year.isNotBlank()) {
                    Text(
                        text = year,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                if (certification.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Text(
                            text = certification,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (runtime.isNotBlank()) {
                    Text(
                        text = runtime,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }

                if (rating.isNotBlank() && rating != "0.0") {
                    RatingBadge("TMDB", rating, Color(0xFF01B4E4))
                }

                if (omdbDetails != null) {
                    val imdb = omdbDetails.imdbRating
                    val rt = omdbDetails.rottenTomatoesRating

                    if (!imdb.isNullOrBlank() && imdb != "N/A") {
                        RatingBadge("IMDb", imdb, Color(0xFFF5C518))
                    }

                    if (!rt.isNullOrBlank() && rt != "N/A") {
                        val color = if (rt.contains("%") && rt.replace("%", "").toIntOrNull() ?: 0 >= 60) {
                            Color(0xFFFA320A) // Fresh red
                        } else {
                            Color(0xFF43C00A) // Rotten green splat
                        }
                        RatingBadge("RT", rt, color)
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingBadge(provider: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(provider, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = color)
            Spacer(modifier = Modifier.width(4.dp))
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

/**
 * A small pill chip showing a [label] (dimmed) followed by a [value] (brighter).
 * Used at the bottom of detail screens to show IDs and metadata sources.
 */
@Composable
private fun DetailInfoChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "$label:",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SplitPlayButton(
    isTvResolving: Boolean = false,
    isPhoneResolving: Boolean = false,
    resolvingLabel: String = "Resolving…",
    hasAddons: Boolean,
    hasImdbId: Boolean,
    watchProviders: List<TmdbWatchProvider>,
    tvName: String? = null,
    isTvConnected: Boolean = false,
    watchOnTv: Boolean,
    onWatchOnTvChange: (Boolean) -> Unit,
    watchLabel: String = "Watch",
    availableTvDevices: List<TvDevice> = emptyList(),
    selectedTvDevice: TvDevice? = null,
    onTvDeviceSelect: ((TvDevice) -> Unit)? = null,
    onWatchOnTv: () -> Unit,
    onWatchOnTvLongClick: (() -> Unit)? = null,
    onWatchOnPhone: () -> Unit = {},
    onWatchOnPhoneLongClick: (() -> Unit)? = null,
    playerMode: String = "tv",
    onPlayerModeChange: (String) -> Unit = {},
    proxyAvailable: Boolean = false,
    proxyMode: MediaflowProxy.Mode = MediaflowProxy.Mode.OFF,
    onProxyModeChange: (MediaflowProxy.Mode) -> Unit = {},
    themeColor: Color = MaterialTheme.colorScheme.primary
) {
    // Player-mode options mirror CastSheet's "play" mode list.
    val playerOptions = listOf(
        "tv"           to "TV Default",
        "internal"     to "ExoPlayer",
        "internal_vlc" to "LibVLC",
        "internal_mpv" to "MPV",
        "external"     to "External",
        "external_mpv" to "Ext. MPV"
    )
    val selectedPlayerLabel = playerOptions.find { it.first == playerMode }?.second ?: "TV Default"
    var showProvidersSheet by remember { mutableStateOf(false) }
    var showDeviceMenu by remember { mutableStateOf(false) }

    val topProvider = watchProviders.firstOrNull()
    val isBusy = isTvResolving || isPhoneResolving
    val isResolving = if (watchOnTv) isTvResolving else isPhoneResolving
    val canPlay = hasAddons && hasImdbId || watchProviders.isNotEmpty()
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Mode toggle chip ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Watching on",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f)
            )
            val connectedGreen = Color(0xFF4CAF50)

            ChipDropdown(
                selectedLabel = if (watchOnTv) {
                    if (!tvName.isNullOrBlank()) "TV ($tvName)" else "TV"
                } else "Phone",
                options = listOf("phone" to "Phone") + availableTvDevices.map { (it.uuid.ifBlank { it.ip }) to (it.name.ifBlank { it.ip }) },
                selectedValue = if (watchOnTv) (selectedTvDevice?.uuid ?: selectedTvDevice?.ip ?: "tv") else "phone",
                onSelect = { value ->
                    if (value == "phone") {
                        onWatchOnTvChange(false)
                    } else {
                        onWatchOnTvChange(true)
                        availableTvDevices.find { it.uuid == value || it.ip == value }?.let {
                            onTvDeviceSelect?.invoke(it)
                        }
                    }
                },
                toggleAction = {
                    val togglingToTv = !watchOnTv
                    onWatchOnTvChange(togglingToTv)
                    // If switching to TV and not connected, try to connect to the selected device
                    if (togglingToTv && !isTvConnected && selectedTvDevice != null) {
                        onTvDeviceSelect?.invoke(selectedTvDevice)
                    }
                },
                chipLabelColor = if (watchOnTv && isTvConnected) connectedGreen else Color.Unspecified,
                themeColor = themeColor,
                leadingIcon = {
                    Icon(
                        imageVector = if (watchOnTv) Icons.Default.Tv else Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (watchOnTv && isTvConnected) connectedGreen else Color.White.copy(alpha = 0.7f)
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Switch destination",
                        modifier = Modifier.size(13.dp),
                        tint = Color.White.copy(alpha = 0.45f)
                    )
                }
            )

            // Player-mode + proxy chips (only meaningful when casting to TV).
            // Mirrors the CastSheet chips so users can tweak engine / proxy
            // without having to open the bottom sheet.
            if (watchOnTv) {
                ChipDropdown(
                    selectedLabel = selectedPlayerLabel,
                    options = playerOptions,
                    selectedValue = playerMode,
                    onSelect = onPlayerModeChange,
                    themeColor = themeColor
                )
                if (proxyAvailable) {
                    ChipDropdown(
                        selectedLabel = proxyMode.label,
                        options = MediaflowProxy.Mode.entries.map { it.name to it.label },
                        selectedValue = proxyMode.name,
                        onSelect = { value -> onProxyModeChange(MediaflowProxy.Mode.valueOf(value)) },
                        themeColor = themeColor
                    )
                }
            }
        }

        // ── Single Watch button ───────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .combinedClickable(
                    enabled = !isBusy && canPlay,
                    onClick = {
                        if (hasAddons && hasImdbId) {
                            if (watchOnTv) onWatchOnTv() else onWatchOnPhone()
                        } else if (watchProviders.isNotEmpty()) showProvidersSheet = true
                    },
                    onLongClick = if (!isBusy) {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (watchOnTv) onWatchOnTvLongClick?.invoke()
                            else onWatchOnPhoneLongClick?.invoke()
                        }
                    } else null
                ),
            shape = RoundedCornerShape(16.dp),
            color = if (!isBusy && canPlay)
                themeColor
            else
                themeColor.copy(alpha = 0.4f)
        ) {
            val contentColor = if (themeColor.luminance() > 0.5f) Color.Black else Color.White
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                // Icon anchored to the left
                Icon(
                    imageVector = if (watchOnTv) Icons.Default.Tv else Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(24.dp),
                    tint = if (!isBusy && canPlay)
                        contentColor
                    else
                        contentColor.copy(alpha = 0.4f)
                )
                // Text centered in the button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isResolving) {
                        Text(
                            text = resolvingLabel.ifBlank { "Resolving…" },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = contentColor.copy(alpha = 0.4f)
                        )
                    } else {
                        Text(
                            text = watchLabel,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (!isBusy && canPlay)
                                contentColor
                            else
                                contentColor.copy(alpha = 0.4f)
                        )
                        if (topProvider != null) {
                            Text(
                                text = topProvider.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }

        // No-addons hint when TMDB has no provider data either
        if (!hasAddons && watchProviders.isEmpty() && hasImdbId) {
            Text(
                text = "Install a Stremio addon to stream on TV",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    // ── Watch Providers info sheet ────────────────────────────────────────────
    if (showProvidersSheet) {
        WatchProvidersSheet(
            providers = watchProviders,
            onDismiss = { showProvidersSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchProvidersSheet(
    providers: List<TmdbWatchProvider>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Where to Watch",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Available on these streaming services",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp)
            )
            providers.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (provider.logoUrl != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(48.dp)
                        ) {
                            AsyncImage(
                                model = provider.logoUrl,
                                contentDescription = provider.providerName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        text = provider.providerName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (provider != providers.last()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    isWatchlisted: Boolean,
    tracked: WatchlistEntity? = null,
    onToggleWatchlist: () -> Unit,
    trailerUrl: String? = null,
    onPlayTrailer: ((String) -> Unit)? = null,
    themeColor: Color = MaterialTheme.colorScheme.primary
) {
    val trailerReady = trailerUrl != null && onPlayTrailer != null
    val status = tracked?.let { WatchlistStatus.from(it.status) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status chip — shown when the item is tracked with a meaningful status
        if (status != null && status != WatchlistStatus.PLAN_TO_WATCH) {
            val statusColor = when (status) {
                WatchlistStatus.WATCHING      -> themeColor
                WatchlistStatus.COMPLETED     -> Color(0xFF4CAF50)
                WatchlistStatus.ON_HOLD       -> Color(0xFFFF9800)
                WatchlistStatus.DROPPED       -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.secondary
            }
            val progressLabel = if (status == WatchlistStatus.WATCHING &&
                tracked.seasonProgress != null && tracked.episodeProgress != null
            ) " · S${tracked.seasonProgress} E${tracked.episodeProgress}" else ""

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = statusColor.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.6f))
            ) {
                Text(
                    text = status.displayName + progressLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onToggleWatchlist() }
        ) {
            Icon(
                if (isWatchlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (isWatchlisted) "Remove from Watchlist" else "Add to Watchlist",
                tint = if (isWatchlisted) themeColor else Color.White.copy(alpha=0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (isWatchlisted) "In Watchlist" else "Add to Watchlist",
                style = MaterialTheme.typography.labelSmall,
                color = if (isWatchlisted) themeColor else Color.White.copy(alpha=0.7f)
            )
        }
        Spacer(modifier = Modifier.width(48.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (trailerReady) Modifier.clickable { onPlayTrailer!!(trailerUrl!!) } else Modifier
        ) {
            Icon(
                Icons.Default.Movie,
                contentDescription = "Play Trailer",
                tint = if (trailerReady) Color.White else Color.White.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (trailerReady) "Play Trailer" else "No Trailer",
                style = MaterialTheme.typography.labelSmall,
                color = if (trailerReady) Color.White else Color.White.copy(alpha = 0.3f)
            )
        }
        } // close Row
    } // close outer Column
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeItem(
    episode: StremioVideo,
    hasAddon: Boolean = false,
    isPlaying: Boolean = false,
    isResolving: Boolean = false,
    isInActivePlaylist: Boolean = false,
    isWatched: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleWatched: () -> Unit = {},
    themeColor: Color = MaterialTheme.colorScheme.primary
) {
    val haptic = LocalHapticFeedback.current
    val containerColor = when {
        isPlaying -> themeColor.copy(alpha = 0.15f)
        isInActivePlaylist -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> Color.Transparent
    }

    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxSwipePx = with(density) { 100.dp.toPx() }
    val triggerThresholdPx = with(density) { 60.dp.toPx() }

    val currentOnToggleWatched by rememberUpdatedState(onToggleWatched)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX.value < -triggerThresholdPx) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnToggleWatched()
                        }
                        scope.launch { offsetX.animateTo(0f, androidx.compose.animation.core.spring()) }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, androidx.compose.animation.core.spring()) }
                    },
                    onHorizontalDrag = { change: PointerInputChange, dragAmount: Float ->
                        change.consume()
                        val newOffset = (offsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                        scope.launch { offsetX.snapTo(newOffset) }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(containerColor)
                .combinedClickable(
                    enabled = hasAddon && !isResolving,
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Episode Image with badge
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (episode.thumbnail != null) {
                    AsyncImage(
                        model = episode.thumbnail,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        placeholder = ColorPainter(themeColor.copy(alpha = 0.1f)),
                        error = ColorPainter(themeColor.copy(alpha = 0.1f)),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(themeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = themeColor.copy(alpha = 0.4f))
                    }
                }

                // Badge overlay
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = (episode.episode ?: 0).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (isPlaying || isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isResolving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Playing", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Title, Date
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                val formattedDate = remember(episode.released) { formatEpisodeDate(episode.released) }
                if (formattedDate != null) {
                    val isFuture = episode.released?.let { isEpisodeFuture(it) } == true
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isFuture)
                            themeColor.copy(alpha = 0.9f)
                        else
                            Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // Watched indicator — animated pop-in
            AnimatedVisibility(
                visible = isWatched,
                enter = fadeIn() + scaleIn(initialScale = 0.6f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                exit = fadeOut() + scaleOut(targetScale = 0.6f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Watched",
                        tint = themeColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Description
        Text(
            text = episode.overview ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
    }
}

// ==================== Series Context Builder ====================

/**
 * Builds a [SeriesContext] to attach to a play command when casting a Stremio series episode.
 * Returns null for movies, when no IMDB ID is available, or when no stream-capable addons
 * are installed (TV would have nothing to resolve with).
 *
 * Must be called from a coroutine (needs [AddonRepository.getInstalledAddons]).
 */
private suspend fun buildSeriesContext(
    isSeries: Boolean,
    resolvedImdbId: String?,
    selectedSeason: Int,
    currentEpisodeSelection: com.playbridge.sender.data.library.StremioVideo?,
    displayTitle: String,
    addonVideos: List<com.playbridge.sender.data.library.StremioVideo>?,
    addonRepository: AddonRepository,
    preferredAddonName: String? = null
): com.playbridge.protocol.SeriesContext? {
    if (!isSeries || resolvedImdbId == null || currentEpisodeSelection == null) return null

    val installedStreamAddons = addonRepository.getInstalledAddons()
        .filter { it.isEnabled && (it.resources.isBlank() || it.supportsResource("stream")) }

    val addonBaseUrls = installedStreamAddons.map { it.baseUrl }
    val addonNames = installedStreamAddons.map { it.name }

    if (addonBaseUrls.isEmpty()) return null   // TV would have nothing to resolve with

    // Resolve name → base URL for the preferred addon (null if not set or not found)
    val preferredAddonBaseUrl = preferredAddonName?.takeIf { it.isNotEmpty() }?.let { name ->
        installedStreamAddons.firstOrNull { it.name == name }?.baseUrl
    }

    val allEpisodes = addonVideos
        ?.filter { it.season != null && it.episode != null && it.season > 0 }
        ?.distinctBy { Pair(it.season, it.episode) }
        ?.map { vid ->
            com.playbridge.protocol.SeriesEpisodeRef(
                season  = vid.season!!,
                episode = vid.episode!!,
                title   = vid.title.ifBlank { null }
            )
        }
        ?.sortedWith(compareBy({ it.season }, { it.episode }))

    return com.playbridge.protocol.SeriesContext(
        imdbId       = resolvedImdbId,
        season       = selectedSeason,
        episode      = currentEpisodeSelection.episode ?: 1,
        seriesTitle  = displayTitle.ifBlank { null },
        episodeTitle = currentEpisodeSelection.title.ifBlank { null },
        addonBaseUrls = addonBaseUrls,
        addonNames   = addonNames,
        allEpisodes  = allEpisodes,
        preferredAddonBaseUrl = preferredAddonBaseUrl,
        preferredAddonName = preferredAddonName
    )
}

/**
 * Builds a [ContentPlayPayload] to send metadata to the TV for it to resolve streams independently.
 * Normalises Kitsu/MAL/IMDb/TMDB/TVDB IDs to a stream-capable ID.
 */
private suspend fun buildContentPayload(
    isSeries: Boolean,
    rawId: String,
    resolvedImdbId: String?,
    resolvedTmdbId: Int?,
    displayTitle: String,
    displayYear: String?,
    displayRating: String?,
    displayRuntime: String?,
    displayOverview: String?,
    displayGenres: List<String>,
    displayCast: List<String>,
    displayDirector: String?,
    displayBackdrop: String?,
    displayPoster: String?,
    displayLogo: String?,
    selectedSeason: Int,
    currentEpisodeSelection: StremioVideo?,
    addonMeta: StremioMetaDetail?,
    addonRepository: AddonRepository,
    forcePicker: Boolean = false,
    autoQuality: String? = null,
    autoMaxMbps: Double? = null,
    preferredAddonName: String? = null,
    preferredSourceTypeKeys: List<String> = emptyList(),
    episodeRuntimeMinutes: Int? = null
): com.playbridge.protocol.ContentPlayPayload? {
    // 1. Resolve canonical stream-capable ID
    var contentId = resolvedImdbId ?: rawId

    // Special handling for MAL -> Kitsu mapping
    if (contentId.startsWith("mal:")) {
        val malId = contentId.removePrefix("mal:")
        val kitsuId = addonRepository.lookupKitsuFromMal(malId)
        if (kitsuId != null) contentId = "kitsu:$kitsuId"
    }

    // Convert numeric TMDB ID to tmdb: prefix if it's the only ID we have
    if (contentId.toIntOrNull() != null && resolvedTmdbId != null) {
        contentId = "tmdb:$resolvedTmdbId"
    }

    val installedStreamAddons = addonRepository.getInstalledAddons()
        .filter { it.isEnabled && (it.resources.isBlank() || it.supportsResource("stream")) }
    val addonBaseUrls = installedStreamAddons.map { it.baseUrl }
    val addonNames = installedStreamAddons.map { it.name }
    if (addonBaseUrls.isEmpty()) return null

    // Resolve name -> base URL for the preferred addon (null if not set or not found)
    val preferredAddonBaseUrl = preferredAddonName?.takeIf { it.isNotEmpty() }?.let { name ->
        installedStreamAddons.firstOrNull { it.name == name }?.baseUrl
    }

    val allEpisodes = if (isSeries) {
        addonMeta?.videos
            ?.filter { it.season != null && it.episode != null && it.season > 0 }
            ?.distinctBy { Pair(it.season, it.episode) }
            ?.map { vid ->
                com.playbridge.protocol.SeriesEpisodeRef(
                    season = vid.season!!,
                    episode = vid.episode!!,
                    title = vid.title.ifBlank { null }
                )
            }
            ?.sortedWith(compareBy({ it.season }, { it.episode }))
    } else null

    return com.playbridge.protocol.ContentPlayPayload(
        contentId = contentId,
        contentType = if (isSeries) "series" else "movie",
        title = displayTitle,
        year = displayYear,
        rating = displayRating,
        runtime = displayRuntime,
        overview = displayOverview,
        genres = displayGenres,
        cast = displayCast,
        director = displayDirector,
        backdropUrl = displayBackdrop,
        posterUrl = displayPoster,
        logoUrl = displayLogo,
        season = if (isSeries) selectedSeason else null,
        episode = if (isSeries) currentEpisodeSelection?.episode ?: 1 else null,
        episodeTitle = if (isSeries) currentEpisodeSelection?.title else null,
        allEpisodes = allEpisodes,
        addonBaseUrls = addonBaseUrls,
        addonNames = addonNames,
        preferredAddonBaseUrl = preferredAddonBaseUrl,
        preferredAddonName = preferredAddonName,
        defaultVideoQuality = autoQuality?.takeIf { it.isNotEmpty() },
        maxBitrateCapMbps = autoMaxMbps,
        preferredSourceTypes = preferredSourceTypeKeys.takeIf { it.isNotEmpty() },
        episodeRuntimeMinutes = episodeRuntimeMinutes?.takeIf { it > 0 },
        forcePicker = forcePicker
    )
}

// ==================== Episode Date Helpers ====================

private fun formatEpisodeDate(released: String?): String? {
    if (released.isNullOrBlank()) return null
    return try {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        var date: java.util.Date? = null
        for (fmt in formats) {
            date = runCatching {
                java.text.SimpleDateFormat(fmt, java.util.Locale.US).also {
                    it.isLenient = false
                }.parse(released)
            }.getOrNull()
            if (date != null) break
        }
        if (date == null) return null

        val now = java.util.Date()
        val diffMs = date.time - now.time
        val diffDays = (diffMs / (1000L * 60 * 60 * 24)).toInt()

        when {
            diffDays > 1  -> "in $diffDays days"
            diffDays == 1 -> "tomorrow"
            diffDays == 0 -> "today"
            else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(date)
        }
    } catch (_: Exception) { null }
}

private fun isEpisodeFuture(released: String): Boolean {
    if (released.isBlank()) return false
    return try {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            val d = runCatching {
                java.text.SimpleDateFormat(fmt, java.util.Locale.US).also {
                    it.isLenient = false
                }.parse(released)
            }.getOrNull()
            if (d != null) return d.after(java.util.Date())
        }
        false
    } catch (_: Exception) { false }
}

// ==================== Resolution States ====================

enum class ResolutionTarget { NONE, TV, PHONE }

data class ResolutionState(
    val isResolving: Boolean = false,
    val target: ResolutionTarget = ResolutionTarget.NONE,
    val episodeId: Int? = null
)

// ==================== Playlist Data Classes ====================

/**
 * UI state for the playlist synced from the TV
 */
data class PlaylistUiState(
    val currentIndex: Int = 0,
    val totalCount: Int = 0
)

@Composable
fun TranslucentBackground(backdropUrl: String?, dominantColor: Color? = null) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (backdropUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backdropUrl)
                    .size(400, 225) // blurred — no need for full resolution
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(100.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            )
            // Apply a uniform dark tint that retains the vibrant color of the blurred backdrop
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }

        // Apply dominant color overlay if available
        dominantColor?.let { color ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color.copy(alpha = 0.15f))
            )
        }
    }
}
