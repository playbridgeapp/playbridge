package com.playbridge.sender.browser

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.painter.ColorPainter
import com.playbridge.sender.data.library.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.ripple.rememberRipple
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDetailScreen(
    id: String,
    type: String,
    addonRepository: AddonRepository,
    onPlayStream: (url: String, title: String, subtitles: List<String>?, seriesContext: com.playbridge.protocol.SeriesContext?, streamQuality: String?, streamMaxMbps: Double?) -> Unit = { _, _, _, _, _, _ -> },
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val omdb = remember { OmdbRepository(context) }
    val subtitleService = remember { StremioSubtitleService(addonRepository) }
    val scope = rememberCoroutineScope()

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

    // Episode selection for stream picker
    var currentEpisodeSelection by remember { mutableStateOf<StremioVideo?>(null) }
    // Triple: (episode, season number, isWatched at time of long-press)
    var episodeSheetTarget by remember { mutableStateOf<Triple<StremioVideo, Int, Boolean>?>(null) }

    val episodeListState = rememberLazyListState()
    var episodesAscending by remember { mutableStateOf(true) }

    // Load TMDB content based on ID type
    LaunchedEffect(id, type) {
        isLoading = true
        errorMessage = null

        when {
            id.toIntOrNull() != null -> {
                // Numeric TMDB ID — direct lookup
                val numericId = id.toInt()
                resolvedTmdbId = numericId
                if (isSeries) {
                    val tv = tmdb.getTvDetails(numericId)
                    tvDetails = tv
                    val imdbId = tv?.imdbId
                    if (imdbId != null) {
                        resolvedImdbId = imdbId
                        if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(imdbId)
                        val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, imdbId) }.getOrNull()
                        addonMeta = metaResult?.first
                        addonMetaSource = metaResult?.second
                    }
                    trailerUrl = tmdb.getTvVideos(numericId)?.bestTrailerUrl
                    watchProviders = tmdb.getTvWatchProviders(numericId)
                } else {
                    val movie = tmdb.getMovieDetails(numericId)
                    movieDetails = movie
                    val imdbId = movie?.imdbId
                    if (imdbId != null) {
                        resolvedImdbId = imdbId
                        if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(imdbId)
                        val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, imdbId) }.getOrNull()
                        addonMeta = metaResult?.first
                        addonMetaSource = metaResult?.second
                    }
                    trailerUrl = tmdb.getMovieVideos(numericId)?.bestTrailerUrl
                    watchProviders = tmdb.getMovieWatchProviders(numericId)
                }
            }
            id.startsWith("tt") -> {
                // IMDb ID — resolve via TMDB /find
                val result = tmdb.findByImdbId(id)
                when {
                    result == null -> {
                        errorMessage = "Could not reach TMDB. Check your API key and connection."
                    }
                    isSeries && result.tvResults.isNotEmpty() -> {
                        val tvId = result.tvResults.first().id
                        resolvedTmdbId = tvId
                        resolvedImdbId = id
                        val tv = tmdb.getTvDetails(tvId)
                        tvDetails = tv
                        if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(id)
                        val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, id) }.getOrNull()
                        addonMeta = metaResult?.first
                        addonMetaSource = metaResult?.second
                        trailerUrl = tmdb.getTvVideos(tvId)?.bestTrailerUrl
                        watchProviders = tmdb.getTvWatchProviders(tvId)
                    }
                    !isSeries && result.movieResults.isNotEmpty() -> {
                        val movieId = result.movieResults.first().id
                        resolvedTmdbId = movieId
                        resolvedImdbId = id
                        val movie = tmdb.getMovieDetails(movieId)
                        movieDetails = movie
                        if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(id)
                        val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, id) }.getOrNull()
                        addonMeta = metaResult?.first
                        addonMetaSource = metaResult?.second
                        trailerUrl = tmdb.getMovieVideos(movieId)?.bestTrailerUrl
                        watchProviders = tmdb.getMovieWatchProviders(movieId)
                    }
                    result.movieResults.isNotEmpty() -> {
                        val movieId = result.movieResults.first().id
                        resolvedTmdbId = movieId
                        resolvedImdbId = id
                        val movie = tmdb.getMovieDetails(movieId)
                        movieDetails = movie
                        if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(id)
                        val metaResult = runCatching { addonRepository.fetchMetaWithSource("movie", id) }.getOrNull()
                        addonMeta = metaResult?.first
                        addonMetaSource = metaResult?.second
                        trailerUrl = tmdb.getMovieVideos(movieId)?.bestTrailerUrl
                        watchProviders = tmdb.getMovieWatchProviders(movieId)
                    }
                    result.tvResults.isNotEmpty() -> {
                        val tvId = result.tvResults.first().id
                        resolvedTmdbId = tvId
                        resolvedImdbId = id
                        val tv = tmdb.getTvDetails(tvId)
                        tvDetails = tv
                        if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(id)
                        val metaResult = runCatching { addonRepository.fetchMetaWithSource("series", id) }.getOrNull()
                        addonMeta = metaResult?.first
                        addonMetaSource = metaResult?.second
                        trailerUrl = tmdb.getTvVideos(tvId)?.bestTrailerUrl
                        watchProviders = tmdb.getTvWatchProviders(tvId)
                    }
                    else -> {
                        errorMessage = "\"$id\" was not found on TMDB."
                    }
                }
            }
            else -> {
                // Addon-native ID (e.g. "kitsu:12345") — no TMDB lookup
                val metaResult = runCatching { addonRepository.fetchMetaWithSource(addonType, id) }.getOrNull()
                addonMeta = metaResult?.first
                addonMetaSource = metaResult?.second
            }
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
    val episodesInSeason = addonMeta?.videos?.filter { it.season == selectedSeason } ?: emptyList()

    // Unified stream resolution function
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

        if (forcePicker || autoQualityKey.isEmpty()) {
            showStreamPicker = true
        }

        scope.launch {
            addonRepository.resolveStreamsFlow(streamType, streamId).collect { latest ->
                resolvedStreams = latest
            }
            resolutionState = resolutionState.copy(isResolving = false)

            // Auto-pick logic
            if (!showStreamPicker && !forcePicker && autoQualityKey.isNotEmpty()) {
                val best = StreamSelector.selectBest(
                    streams = resolvedStreams,
                    preferredQuality = QualityFilter.fromKey(autoQualityKey),
                    maxMbps = autoMaxMbps,
                    runtimeMinutes = if (!isSeries) movieDetails?.runtime else null
                )
                val finalSelection = best ?: resolvedStreams.firstOrNull()
                if (finalSelection != null) {
                    val streamUrl = finalSelection.stream.url
                    if (streamUrl != null) {
                        if (forPhone) {
                            openInExternalPlayer(context, streamUrl, null, null)
                        } else {
                            val seriesCtx = buildSeriesContext(
                                isSeries, resolvedImdbId, selectedSeason,
                                currentEpisodeSelection, displayTitle, addonMeta?.videos,
                                addonRepository
                            )
                            onPlayStream(
                                streamUrl, resTitle, null, seriesCtx,
                                autoQualityKey.takeIf { it.isNotEmpty() }, // configured quality tier, not stream's qualityTier (title can contaminate)
                                autoMaxMbps                                 // configured max bitrate cap
                            )
                            Toast.makeText(context, "Auto-selected: ${finalSelection.stream.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    showStreamPicker = true
                }
            }
        }
    }

    // Stream picker sheet
    if (showStreamPicker) {
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = resolutionState.isResolving,
            title = streamPickerTitle,
            episodeRuntimeMinutes = null,
            forceManual = forceManualInPicker,
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

                        val seriesCtx = buildSeriesContext(
                            isSeries, resolvedImdbId, selectedSeason,
                            currentEpisodeSelection, displayTitle, addonMeta?.videos,
                            addonRepository
                        )
                        onPlayStream(
                            streamUrl, streamPickerTitle, subtitles.ifEmpty { null }, seriesCtx,
                            resolved.stream.qualityTier,                   // quality from stream name (name-first, reliable)
                            StreamSelector.estimatedMbps(resolved, null)   // actual stream bitrate; null if no size metadata
                        )
                        resolutionState = ResolutionState()
                        if (isSeries && currentEpisodeSelection != null) {
                            onNowPlayingStarted(resolvedTmdbId ?: 0, selectedSeason, currentEpisodeSelection!!.episode ?: 1)
                        }
                        Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = {
                showStreamPicker = false
                resolutionState = ResolutionState()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TranslucentBackground(backdropUrl = displayBackdrop)
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
                            omdbDetails = omdbDetails
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
                                availableTvDevices = availableTvDevices,
                                selectedTvDevice = selectedTvDevice,
                                onTvDeviceSelect = onTvDeviceSelect,
                                onWatchOnTv = {
                                    if (isSeries && firstEpisodeForTv != null) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${firstEpisodeForTv.episode ?: 1}" else firstEpisodeForTv.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${firstEpisodeForTv.episode ?: 1}"
                                        startResolution(streamId, streamType, title, false, false, firstEpisodeForTv)
                                    } else {
                                        val streamId = resolvedImdbId ?: id
                                        startResolution(streamId, addonType, displayTitle, false, false, null)
                                    }
                                },
                                onWatchOnTvLongClick = {
                                    if (isSeries && firstEpisodeForTv != null) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${firstEpisodeForTv.episode ?: 1}" else firstEpisodeForTv.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${firstEpisodeForTv.episode ?: 1}"
                                        startResolution(streamId, streamType, title, false, true, firstEpisodeForTv)
                                    } else {
                                        val streamId = resolvedImdbId ?: id
                                        startResolution(streamId, addonType, displayTitle, false, true, null)
                                    }
                                },
                                onWatchOnPhone = {
                                    if (isSeries && firstEpisodeForTv != null) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${firstEpisodeForTv.episode ?: 1}" else firstEpisodeForTv.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${firstEpisodeForTv.episode ?: 1}"
                                        startResolution(streamId, streamType, title, true, false, firstEpisodeForTv)
                                    } else {
                                        val streamId = resolvedImdbId ?: id
                                        startResolution(streamId, addonType, displayTitle, true, false, null)
                                    }
                                },
                                onWatchOnPhoneLongClick = {
                                    if (isSeries && firstEpisodeForTv != null) {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${firstEpisodeForTv.episode ?: 1}" else firstEpisodeForTv.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${firstEpisodeForTv.episode ?: 1}"
                                        startResolution(streamId, streamType, title, true, true, firstEpisodeForTv)
                                    } else {
                                        val streamId = resolvedImdbId ?: id
                                        startResolution(streamId, addonType, displayTitle, true, true, null)
                                    }
                                }
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
                                    watchLabel = "Watch $epLabel",
                                    availableTvDevices = availableTvDevices,
                                    selectedTvDevice = selectedTvDevice,
                                    onTvDeviceSelect = onTvDeviceSelect,
                                    onWatchOnTv = {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${nextUnwatchedEpisode.episode ?: 1}" else nextUnwatchedEpisode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${nextUnwatchedEpisode.episode ?: 1}"
                                        startResolution(streamId, streamType, title, false, false, nextUnwatchedEpisode)
                                    },
                                    onWatchOnTvLongClick = {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${nextUnwatchedEpisode.episode ?: 1}" else nextUnwatchedEpisode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${nextUnwatchedEpisode.episode ?: 1}"
                                        startResolution(streamId, streamType, title, false, true, nextUnwatchedEpisode)
                                    },
                                    onWatchOnPhone = {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${nextUnwatchedEpisode.episode ?: 1}" else nextUnwatchedEpisode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${nextUnwatchedEpisode.episode ?: 1}"
                                        startResolution(streamId, streamType, title, true, false, nextUnwatchedEpisode)
                                    },
                                    onWatchOnPhoneLongClick = {
                                        val streamId = if (resolvedImdbId != null) "$resolvedImdbId:${selectedSeason}:${nextUnwatchedEpisode.episode ?: 1}" else nextUnwatchedEpisode.id
                                        val streamType = if (resolvedImdbId != null) "series" else addonType
                                        val title = "$displayTitle S${selectedSeason}E${nextUnwatchedEpisode.episode ?: 1}"
                                        startResolution(streamId, streamType, title, true, true, nextUnwatchedEpisode)
                                    }
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
                                onPlayTrailer = onPlayTrailer
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
                                        ElevatedFilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                if (isSelected) return@ElevatedFilterChip
                                                selectedSeason = seasonNumber
                                            },
                                            label = { Text("S$seasonNumber") }
                                        )
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
                                            startResolution(streamId, streamType, title, false, false, episode)
                                        }
                                    }
                                },
                                onLongClick = {
                                    episodeSheetTarget = Triple(episode, selectedSeason, isEpWatched)
                                }
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

        // Episode options sheet
        episodeSheetTarget?.let { (ep, epSeason, epIsWatched) ->
            EpisodeOptionsSheet(
                episode = ep,
                season = epSeason,
                isWatched = epIsWatched,
                canResolve = canResolveStreams,
                onPickStream = {
                    episodeSheetTarget = null
                    val epNum = ep.episode ?: 0
                    val streamId = if (resolvedImdbId != null) "$resolvedImdbId:$epSeason:$epNum" else ep.id
                    val streamType = if (resolvedImdbId != null) "series" else addonType
                    val title = "$displayTitle S${epSeason}E$epNum"
                    startResolution(streamId, streamType, title, false, true, ep)
                },
                onMarkWatched = {
                    episodeSheetTarget = null
                    resolvedTmdbId?.let { tmdbId ->
                        viewModel.upsertTracked(
                            tmdbId = tmdbId,
                            mediaType = "tv",
                            title = displayTitle,
                            posterUrl = tvDetails?.posterUrl ?: addonMeta?.poster,
                            year = displayYear,
                            rating = displayRating,
                            status = WatchlistStatus.WATCHING,
                        )
                        viewModel.setEpisodeProgress(tmdbId, epSeason, ep.episode ?: 0)
                    }
                },
                onUnmarkWatched = {
                    episodeSheetTarget = null
                    resolvedTmdbId?.let { tmdbId ->
                        viewModel.setEpisodeProgress(tmdbId, epSeason, ((ep.episode ?: 1) - 1).coerceAtLeast(0))
                    }
                },
                onDismiss = { episodeSheetTarget = null }
            )
        }

        TopAppBar(
            title = { },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
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
    omdbDetails: OmdbResponse? = null
) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val height = (config.screenHeightDp * 0.65).dp

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
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(backdropUrl)
                        .crossfade(true)
                        .build(),
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
    watchLabel: String = "Watch",
    availableTvDevices: List<TvDevice> = emptyList(),
    selectedTvDevice: TvDevice? = null,
    onTvDeviceSelect: ((TvDevice) -> Unit)? = null,
    onWatchOnTv: () -> Unit,
    onWatchOnTvLongClick: (() -> Unit)? = null,
    onWatchOnPhone: () -> Unit = {},
    onWatchOnPhoneLongClick: (() -> Unit)? = null
) {
    var showProvidersSheet by remember { mutableStateOf(false) }
    var showDeviceMenu by remember { mutableStateOf(false) }
    var watchOnTv by remember { mutableStateOf(tvName != null) }

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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Watching on",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(end = 8.dp)
            )
            val connectedGreen = Color(0xFF4CAF50)
            Box(
                modifier = Modifier.combinedClickable(
                    onClick = {
                        val togglingToTv = !watchOnTv
                        watchOnTv = togglingToTv
                        // If switching to TV and not connected, try to connect to the selected device
                        if (togglingToTv && !isTvConnected && selectedTvDevice != null) {
                            onTvDeviceSelect?.invoke(selectedTvDevice)
                        }
                    },
                    onLongClick = if (watchOnTv && availableTvDevices.isNotEmpty()) {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showDeviceMenu = true
                        }
                    } else null
                )
            ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (watchOnTv && isTvConnected)
                    connectedGreen.copy(alpha = 0.15f)
                else
                    Color.White.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (watchOnTv && isTvConnected)
                        connectedGreen.copy(alpha = 0.5f)
                    else
                        Color.White.copy(alpha = 0.2f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (watchOnTv) Icons.Default.Tv else Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (watchOnTv && isTvConnected)
                            connectedGreen
                        else
                            Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = if (watchOnTv) {
                            if (!tvName.isNullOrBlank()) "TV ($tvName)" else "TV"
                        } else "Phone",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (watchOnTv && isTvConnected)
                            connectedGreen
                        else
                            Color.White.copy(alpha = 0.75f)
                    )
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Switch destination",
                        modifier = Modifier.size(13.dp),
                        tint = Color.White.copy(alpha = 0.45f)
                    )
                }
            }
                DropdownMenu(
                    expanded = showDeviceMenu,
                    onDismissRequest = { showDeviceMenu = false }
                ) {
                    Text(
                        text = "Choose device",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    availableTvDevices.forEach { device ->
                        val isSelected = device.uuid.isNotEmpty() && device.uuid == selectedTvDevice?.uuid
                            || device.uuid.isEmpty() && device.ip == selectedTvDevice?.ip
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = device.name.ifBlank { device.ip },
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                           else LocalContentColor.current.copy(alpha = 0.7f)
                                )
                            },
                            trailingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            onClick = {
                                showDeviceMenu = false
                                onTvDeviceSelect?.invoke(device)
                            }
                        )
                    }
                }
            } // close Box
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
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        ) {
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
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                )
                // Text centered in the button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isResolving) {
                        Text(
                            text = resolvingLabel.ifBlank { "Resolving…" },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                        )
                    } else {
                        Text(
                            text = watchLabel,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (!isBusy && canPlay)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                        )
                        if (topProvider != null) {
                            Text(
                                text = topProvider.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
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
    onPlayTrailer: ((String) -> Unit)? = null
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
                WatchlistStatus.WATCHING      -> MaterialTheme.colorScheme.primary
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
                tint = if (isWatchlisted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha=0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (isWatchlisted) "In Watchlist" else "Add to Watchlist",
                style = MaterialTheme.typography.labelSmall,
                color = if (isWatchlisted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha=0.7f)
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

@OptIn(ExperimentalFoundationApi::class)
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
) {
    val haptic = LocalHapticFeedback.current
    val containerColor = when {
        isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        isInActivePlaylist -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
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
                        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                        error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
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
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        else
                            Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // Watched indicator — only shown when episode is watched
            if (isWatched) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Watched",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
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
    addonRepository: AddonRepository
): com.playbridge.protocol.SeriesContext? {
    if (!isSeries || resolvedImdbId == null || currentEpisodeSelection == null) return null

    val addonBaseUrls = addonRepository.getInstalledAddons()
        .filter { it.isEnabled && (it.resources.isBlank() || it.supportsResource("stream")) }
        .map { it.baseUrl }

    if (addonBaseUrls.isEmpty()) return null   // TV would have nothing to resolve with

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
        allEpisodes  = allEpisodes
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

// ==================== Episode Options Sheet ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeOptionsSheet(
    episode: StremioVideo,
    season: Int,
    isWatched: Boolean,
    canResolve: Boolean,
    onPickStream: () -> Unit,
    onMarkWatched: () -> Unit,
    onUnmarkWatched: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (episode.thumbnail != null) {
                    AsyncImage(
                        model = episode.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(72.dp)
                            .height(44.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "S${season.toString().padStart(2,'0')}E${(episode.episode ?: 0).toString().padStart(2,'0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider()

            // Pick stream
            if (canResolve) {
                ListItem(
                    headlineContent = { Text("Pick stream") },
                    leadingContent = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface)
                    },
                    modifier = Modifier.clickable { onPickStream() }
                )
            }

            // Mark / Unmark watched
            if (isWatched) {
                ListItem(
                    headlineContent = { Text("Unmark as watched") },
                    leadingContent = {
                        Icon(Icons.Default.Close, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface)
                    },
                    modifier = Modifier.clickable { onUnmarkWatched() }
                )
            } else {
                ListItem(
                    headlineContent = { Text("Mark as watched") },
                    leadingContent = {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface)
                    },
                    modifier = Modifier.clickable { onMarkWatched() }
                )
            }
        }
    }
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
fun TranslucentBackground(backdropUrl: String?) {
    if (backdropUrl != null) {
        Box(modifier = Modifier.fillMaxSize()) {
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
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
    }
}
