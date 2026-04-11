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

/**
 * Detail screen for a movie — shows metadata, backdrop, and Play button
 * that resolves streams from installed addons and shows a picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailScreen(
    movieId: Int,
    addonRepository: AddonRepository,
    onPlayStream: (url: String, title: String, subtitles: List<String>?) -> Unit,
    onPlayTrailer: ((String) -> Unit)? = null,
    viewModel: LibraryViewModel,
    tvName: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val omdb = remember { OmdbRepository(context) }
    val subtitleService = remember { StremioSubtitleService(addonRepository) }
    val scope = rememberCoroutineScope()

    var details by remember { mutableStateOf<TmdbMovieDetails?>(null) }
    var addonMeta by remember { mutableStateOf<StremioMetaDetail?>(null) }
    var omdbDetails by remember { mutableStateOf<OmdbResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var trailerUrl by remember { mutableStateOf<String?>(null) }
    var watchProviders by remember { mutableStateOf<List<TmdbWatchProvider>>(emptyList()) }
    var hasAddons by remember { mutableStateOf(false) }

    // Stream resolution state
    var resolvedStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var resolutionState by remember { mutableStateOf(ResolutionState()) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var forceManualInPicker by remember { mutableStateOf(false) }

    LaunchedEffect(movieId) {
        isLoading = true
        val movieDetails = tmdb.getMovieDetails(movieId)
        details = movieDetails
        val imdbId = movieDetails?.imdbId
        if (imdbId != null) {
            if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(imdbId)
            // Addon metadata is the primary display source
            addonMeta = runCatching { addonRepository.fetchMeta("movie", imdbId) }.getOrNull()
        }
        isLoading = false
        trailerUrl = tmdb.getMovieVideos(movieId)?.bestTrailerUrl
        watchProviders = tmdb.getMovieWatchProviders(movieId)
        hasAddons = addonRepository.hasAnyAddons()
    }

    val isWatchlisted by viewModel.isWatchlisted(movieId).collectAsState(initial = false)
    val tracked by viewModel.getTracked(movieId).collectAsState(initial = null)

    // Stream picker sheet
    if (showStreamPicker) {
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = resolutionState.isResolving,
            title = details?.title ?: "Select Stream",
            episodeRuntimeMinutes = details?.runtime,
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
                        val subtitles = details?.imdbId?.let { imdbId ->
                            val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            val prefLang = prefs.getString("preferred_subtitle_lang", "") ?: ""
                            if (prefLang.isNotEmpty()) {
                                try {
                                    val allSubs = subtitleService.getSubtitlesForMovie(imdbId)
                                    allSubs.mapNotNull { it.url }
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            } else {
                                emptyList()
                            }
                        } ?: emptyList()

                        onPlayStream(streamUrl, details?.title ?: "Movie", subtitles.ifEmpty { null })
                        Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showStreamPicker = false; resolutionState = ResolutionState() }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TranslucentBackground(backdropUrl = details?.backdropUrl)
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (details == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Failed to load movie details", color = Color.White)
            }
        } else {
            val movie = details!!
            val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp + navBarPadding)
            ) {
                // Backdrop
                item {
                    BackdropSection(
                        backdropUrl = addonMeta?.background ?: movie.backdropUrl,
                        logoUrl = movie.logoUrl,
                        title = addonMeta?.name ?: movie.title,
                        year = addonMeta?.year ?: movie.year,
                        certification = movie.certification,
                        rating = addonMeta?.imdbRating ?: movie.rating,
                        runtime = addonMeta?.runtime ?: movie.runtimeFormatted,
                        genres = addonMeta?.genres?.takeIf { it.isNotEmpty() } ?: movie.genres.map { it.name },
                        omdbDetails = omdbDetails
                    )
                }

                // Play / Watch buttons
                item {
                    val imdbId = movie.imdbId
                    val autoQualityKey = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE).getString("auto_stream_quality", "") ?: "" }
                    val autoMaxMbps = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE).getString("auto_stream_max_mbps", "")?.toDoubleOrNull() }

                    val startResolution: (Boolean, Boolean) -> Unit = start@{ forPhone, forcePicker ->
                        if (imdbId != null && hasAddons) {
                            resolutionState = ResolutionState(
                                isResolving = true,
                                target = if (forPhone) ResolutionTarget.PHONE else ResolutionTarget.TV
                            )
                            forceManualInPicker = forcePicker
                            resolvedStreams = emptyList()
                            if (forcePicker || autoQualityKey.isEmpty()) {
                                showStreamPicker = true
                            }
                            scope.launch {
                                addonRepository.resolveMovieStreamsFlow(imdbId).collect { latest ->
                                    resolvedStreams = latest
                                }
                                resolutionState = resolutionState.copy(isResolving = false)

                                // Auto-pick logic if not showing picker
                                if (!showStreamPicker && !forcePicker && autoQualityKey.isNotEmpty()) {
                                    val best = StreamSelector.selectBest(
                                        streams = resolvedStreams,
                                        preferredQuality = QualityFilter.fromKey(autoQualityKey),
                                        maxMbps = autoMaxMbps,
                                        runtimeMinutes = movie.runtime
                                    )
                                    val finalSelection = best ?: resolvedStreams.firstOrNull()
                                    if (finalSelection != null) {
                                        val streamUrl = finalSelection.stream.url
                                        if (streamUrl != null) {
                                            if (forPhone) {
                                                openInExternalPlayer(context, streamUrl, null, null)
                                            } else {
                                                onPlayStream(streamUrl, movie.title, null)
                                                Toast.makeText(context, "Auto-selected: ${finalSelection.stream.name}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        showStreamPicker = true
                                    }
                                }
                            }
                        }
                    }

                    SplitPlayButton(
                        isTvResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.TV,
                        isPhoneResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.PHONE,
                        hasAddons = hasAddons,
                        hasImdbId = imdbId != null,
                        watchProviders = watchProviders,
                        tvName = tvName,
                        onWatchOnTv = { startResolution(false, false) },
                        onWatchOnTvLongClick = { startResolution(false, true) },
                        onWatchOnPhone = { startResolution(true, false) },
                        onWatchOnPhoneLongClick = { startResolution(true, true) }
                    )
                }

                item {
                    ActionButtons(
                        isWatchlisted = isWatchlisted,
                        tracked = tracked,
                        onToggleWatchlist = {
                            details?.let { movie ->
                                viewModel.toggleWatchlist(
                                    tmdbId = movie.id,
                                    mediaType = "movie",
                                    title = movie.title,
                                    posterUrl = movie.posterUrl,
                                    year = movie.year,
                                    rating = movie.rating
                                )
                            }
                        },
                        trailerUrl = trailerUrl,
                        onPlayTrailer = onPlayTrailer
                    )
                }

                // Overview
                item {
                    Text(
                        text = addonMeta?.description ?: movie.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        color = Color.White
                    )

                    val displayCast = addonMeta?.cast?.takeIf { it.isNotEmpty() } ?: movie.cast
                    if (displayCast.isNotEmpty()) {
                        Text(
                            text = "Starring: ${displayCast.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                    if (movie.director.isNotBlank()) {
                        Text(
                            text = "Director: ${movie.director}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    }
                }

                // IMDb ID info
                item {
                    if (movie.imdbId != null) {
                        Surface(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "IMDB: ${movie.imdbId}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
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

/**
 * Detail screen for a TV show — shows metadata, seasons, episodes,
 * and Play button that resolves streams and shows a picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvShowDetailScreen(
    tvId: Int,
    addonRepository: AddonRepository,
    onPlayStream: (url: String, title: String, subtitles: List<String>?) -> Unit,
    onPlayTrailer: ((String) -> Unit)? = null,
    onPlayPlaylist: (items: List<com.playbridge.protocol.PlayPayload>) -> Unit,
    onQueueAdd: (com.playbridge.protocol.PlayPayload) -> Unit = {},
    onPlaylistJump: (Int) -> Unit = {},
    playlistState: PlaylistUiState? = null,
    onNowPlayingStarted: (tvId: Int, season: Int, startEpisode: Int) -> Unit = { _, _, _ -> },
    highlightSeason: Int? = null,
    highlightEpisode: Int? = null,
    viewModel: LibraryViewModel,
    tvName: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val omdb = remember { OmdbRepository(context) }
    val subtitleService = remember { StremioSubtitleService(addonRepository) }
    val scope = rememberCoroutineScope()
    
    var details by remember { mutableStateOf<TmdbTvDetails?>(null) }
    var addonMeta by remember { mutableStateOf<StremioMetaDetail?>(null) }
    var omdbDetails by remember { mutableStateOf<OmdbResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var trailerUrl by remember { mutableStateOf<String?>(null) }
    var watchProviders by remember { mutableStateOf<List<TmdbWatchProvider>>(emptyList()) }
    var hasAddons by remember { mutableStateOf(false) }
    var selectedSeason by remember { mutableIntStateOf(highlightSeason ?: 1) }

    // Stream resolution state
    var resolvedStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var resolutionState by remember { mutableStateOf(ResolutionState()) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var streamPickerTitle by remember { mutableStateOf("Select Stream") }
    var forceManualInPicker by remember { mutableStateOf(false) }

    // Season play state
    var seasonResolutionState by remember { mutableStateOf(SeasonResolutionState()) }

    // Throttled queue state
    var queuedCount by remember { mutableIntStateOf(0) }
    var queueTotalCount by remember { mutableIntStateOf(0) }
    var isQueueing by remember { mutableStateOf(false) }

    // Episode selection for stream picker
    var currentEpisodeSelection by remember { mutableStateOf<StremioVideo?>(null) }
    // Context for season queue operations (holds episode list & preferences while Ep1 stream picker is open)
    var seasonQueueContext by remember { mutableStateOf<SeasonQueueContext?>(null) }

    val isWatchlisted by viewModel.isWatchlisted(tvId).collectAsState(initial = false)
    val tracked by viewModel.getTracked(tvId).collectAsState(initial = null)

    val episodeListState = rememberLazyListState()
    var episodesAscending by remember { mutableStateOf(true) }

    LaunchedEffect(tvId) {
        isLoading = true
        val tvDetails = tmdb.getTvDetails(tvId)
        details = tvDetails
        val imdbId = tvDetails?.imdbId
        if (imdbId != null) {
            if (omdb.isConfigured()) omdbDetails = omdb.getDetailsByImdbId(imdbId)
            addonMeta = runCatching { addonRepository.fetchMeta("series", imdbId) }.getOrNull()
        }
        isLoading = false
        trailerUrl = tmdb.getTvVideos(tvId)?.bestTrailerUrl
        watchProviders = tmdb.getTvWatchProviders(tvId)
        hasAddons = addonRepository.hasAnyAddons()

        // Prefer the tracked season, otherwise first season from addon videos
        val trackedSeason = tracked
            ?.takeIf { it.status == WatchlistStatus.WATCHING.value }
            ?.seasonProgress
        val firstAddonSeason = addonMeta?.videos
            ?.mapNotNull { it.season }
            ?.filter { it > 0 }
            ?.minOrNull()
        val targetSeason = trackedSeason ?: highlightSeason ?: firstAddonSeason ?: 1
        selectedSeason = targetSeason
    }

    // Stream picker sheet — when seasonQueueContext is set, picking a stream for Ep1
    // triggers the throttled queue for the rest of the season

    if (showStreamPicker) {
        val episodeRuntime: Int? = null // StremioVideo has no runtime
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = resolutionState.isResolving,
            title = streamPickerTitle,
            episodeRuntimeMinutes = episodeRuntime,
            forceManual = forceManualInPicker,
            onStreamSelected = { resolved ->
                val forPhone = resolutionState.target == ResolutionTarget.PHONE
                showStreamPicker = false
                resolutionState = resolutionState.copy(isResolving = false) // Target stays same during EP1 pick
                val streamUrl = resolved.stream.url ?: return@StreamPickerSheet

                if (forPhone) {
                    // Play locally on phone — open system video player chooser
                    seasonQueueContext = null
                    openInExternalPlayer(context, streamUrl, null, null)
                } else {
                    scope.launch {
                        val subtitles = if (details?.imdbId != null && currentEpisodeSelection != null) {
                            val imdbId = details!!.imdbId!!
                            val episode = currentEpisodeSelection!!
                            val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            val prefLang = prefs.getString("preferred_subtitle_lang", "") ?: ""

                            if (prefLang.isNotEmpty()) {
                                try {
                                    val allSubs = subtitleService.getSubtitlesForEpisode(imdbId, selectedSeason, episode.episode ?: 0)
                                    allSubs.mapNotNull { it.url }
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            } else {
                                emptyList()
                            }
                        } else emptyList()

                        val ep1Payload = com.playbridge.protocol.PlayPayload(
                            url = streamUrl,
                            title = streamPickerTitle,
                            subtitles = subtitles.ifEmpty { null }
                        )

                        val sqCtx = seasonQueueContext
                        if (sqCtx != null) {
                            // Season play: send Ep1 as a 1-item playlist so the TV enters
                            // playlist mode immediately (ExoPlayer playlist button shows)
                            seasonQueueContext = null
                            resolutionState = ResolutionState() // Clear Ep1 resolution state
                            onPlayPlaylist(listOf(ep1Payload))
                            // Notify BrowserActivity of which show/season is now playing
                            val startEp = sqCtx.episodes.firstOrNull()?.episode ?: 1
                            onNowPlayingStarted(tvId, sqCtx.season, startEp)
                            Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()

                            // Derive target bitrate + bingeGroup from selected Ep1 stream
                            val ep1Runtime: Int? = null // StremioVideo has no runtime
                            val targetMbps = resolved.stream.behaviorHints?.calculateMbps(ep1Runtime)
                            val preferredBingeGroup = resolved.stream.behaviorHints?.bingeGroup

                            // Start background queue for Eps 2+
                            val totalEpisodes = sqCtx.episodes.size
                            queueTotalCount = totalEpisodes
                            queuedCount = 1 // Ep1 is already playing
                            isQueueing = true
                            seasonResolutionState = SeasonResolutionState(
                                isResolving = true,
                                progressLabel = "Queuing: 1/$totalEpisodes"
                            )

                            val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            val prefLang = prefs.getString("preferred_subtitle_lang", "") ?: ""

                            addonRepository.resolveSeasonStreamsFlow(
                                imdbId = sqCtx.imdbId,
                                season = sqCtx.season,
                                episodeCount = totalEpisodes,
                                showName = sqCtx.showName,
                                qualityFilter = sqCtx.qualityFilter,
                                targetBitrateMbps = targetMbps,
                                preferredBingeGroup = preferredBingeGroup,
                                episodeRuntimeMinutes = sqCtx.runtimeMap,
                                delayBetweenMs = 2000
                            ).collect { episodeStream ->
                                // Skip Ep1 — already playing
                                if (episodeStream != null && episodeStream.episode > 1) {
                                    val epUrl = episodeStream.stream.stream.url
                                    if (!epUrl.isNullOrBlank()) {
                                        val epSubs = if (prefLang.isNotEmpty()) {
                                            try {
                                                val allSubs = subtitleService.getSubtitlesForEpisode(
                                                    sqCtx.imdbId, sqCtx.season, episodeStream.episode
                                                )
                                                allSubs.mapNotNull { it.url }
                                            } catch (_: Exception) { emptyList() }
                                        } else emptyList()

                                        onQueueAdd(
                                            com.playbridge.protocol.PlayPayload(
                                                url = epUrl,
                                                title = episodeStream.title,
                                                subtitles = epSubs.ifEmpty { null }
                                            )
                                        )
                                        queuedCount++
                                        seasonResolutionState = seasonResolutionState.copy(
                                            progressLabel = "Queuing: $queuedCount/$totalEpisodes"
                                        )
                                    }
                                }
                            }

                            seasonResolutionState = SeasonResolutionState()
                            isQueueing = false
                            Toast.makeText(context, "All $queuedCount episodes queued", Toast.LENGTH_SHORT).show()
                        } else {
                            // Single episode play (not season queue)
                            onPlayStream(streamUrl, streamPickerTitle, subtitles.ifEmpty { null })
                            resolutionState = ResolutionState() // Clear
                            Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDismiss = {
                showStreamPicker = false
                resolutionState = ResolutionState()
                seasonQueueContext = null
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TranslucentBackground(backdropUrl = details?.backdropUrl)
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (details == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Failed to load TV show details", color = Color.White)
            }
        } else {
            val show = details!!
            val imdbId = show.imdbId

            val autoQualityKey = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE).getString("auto_stream_quality", "") ?: "" }
            val autoMaxMbps = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE).getString("auto_stream_max_mbps", "")?.toDoubleOrNull() }
            val episodesInSeason = addonMeta?.videos?.filter { it.season == selectedSeason } ?: emptyList()

            val startResolution: (StremioVideo, Boolean, Boolean) -> Unit = start@{ episode, forPhone, forcePicker ->
                if (imdbId != null && hasAddons) {
                    resolutionState = ResolutionState(
                        isResolving = true,
                        target = if (forPhone) ResolutionTarget.PHONE else ResolutionTarget.TV,
                        episodeId = episode.episode ?: 0
                    )
                    forceManualInPicker = forcePicker
                    currentEpisodeSelection = episode
                    streamPickerTitle = "${show.name} S${selectedSeason}E${episode.episode ?: 0}"
                    resolvedStreams = emptyList()

                    if (forcePicker || autoQualityKey.isEmpty()) {
                        showStreamPicker = true
                    }

                    scope.launch {
                        addonRepository.resolveEpisodeStreamsFlow(
                            imdbId, selectedSeason, episode.episode ?: 0
                        ).collect { latest ->
                            resolvedStreams = latest
                        }
                        resolutionState = resolutionState.copy(isResolving = false)

                        // Auto-pick logic
                        if (!showStreamPicker && !forcePicker && autoQualityKey.isNotEmpty()) {
                            val best = StreamSelector.selectBest(
                                streams = resolvedStreams,
                                preferredQuality = QualityFilter.fromKey(autoQualityKey),
                                maxMbps = autoMaxMbps,
                                runtimeMinutes = null
                            )
                            val finalSelection = best ?: resolvedStreams.firstOrNull()
                            if (finalSelection != null) {
                                val streamUrl = finalSelection.stream.url
                                if (streamUrl != null) {
                                    if (forPhone) {
                                        openInExternalPlayer(context, streamUrl, null, null)
                                    } else {
                                        // Simplified payload for auto-pick TV
                                        onPlayStream(streamUrl, streamPickerTitle, null)
                                        Toast.makeText(context, "Auto-selected: ${finalSelection.stream.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                showStreamPicker = true
                            }
                        }
                    }

                    if (!forPhone && episode == episodesInSeason.firstOrNull()) {
                        seasonQueueContext = SeasonQueueContext(
                            imdbId = imdbId,
                            season = selectedSeason,
                            episodes = episodesInSeason,
                            showName = show.name,
                            qualityFilter = emptyList(),
                            runtimeMap = emptyMap()
                        )
                    }
                }
            }

            val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                state = episodeListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp + navBarPadding)
            ) {
                // Backdrop
                item {
                    BackdropSection(
                        backdropUrl = addonMeta?.background ?: show.backdropUrl,
                        logoUrl = show.logoUrl,
                        title = addonMeta?.name ?: show.name,
                        year = addonMeta?.year ?: show.year,
                        certification = show.certification,
                        rating = addonMeta?.imdbRating ?: show.rating,
                        runtime = addonMeta?.runtime ?: "${show.numberOfSeasons} Season${if (show.numberOfSeasons != 1) "s" else ""}",
                        genres = addonMeta?.genres?.takeIf { it.isNotEmpty() } ?: show.genres.map { it.name },
                        omdbDetails = omdbDetails
                    )
                }

                item {
                    SplitPlayButton(
                        isTvResolving = seasonResolutionState.isResolving || (resolutionState.isResolving && resolutionState.target == ResolutionTarget.TV),
                        isPhoneResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.PHONE,
                        resolvingLabel = if (seasonResolutionState.isResolving) seasonResolutionState.progressLabel else "Finding streams…",
                        hasAddons = hasAddons,
                        hasImdbId = imdbId != null,
                        watchProviders = watchProviders,
                        tvName = tvName,
                        onWatchOnTv = { episodesInSeason.firstOrNull()?.let { startResolution(it, false, false) } },
                        onWatchOnTvLongClick = { episodesInSeason.firstOrNull()?.let { startResolution(it, false, true) } },
                        onWatchOnPhone = { episodesInSeason.firstOrNull()?.let { startResolution(it, true, false) } },
                        onWatchOnPhoneLongClick = { episodesInSeason.firstOrNull()?.let { startResolution(it, true, true) } }
                    )
                }
                
                item {
                    ActionButtons(
                        isWatchlisted = isWatchlisted,
                        tracked = tracked,
                        onToggleWatchlist = {
                            details?.let { show ->
                                viewModel.toggleWatchlist(
                                    tmdbId = show.id,
                                    mediaType = "tv",
                                    title = show.name,
                                    posterUrl = show.posterUrl,
                                    year = show.year,
                                    rating = show.rating
                                )
                            }
                        },
                        trailerUrl = trailerUrl,
                        onPlayTrailer = onPlayTrailer
                    )
                }

                // Overview
                item {
                    Text(
                        text = addonMeta?.description ?: show.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        color = Color.White
                    )

                    val displayCast = addonMeta?.cast?.takeIf { it.isNotEmpty() } ?: show.cast
                    if (displayCast.isNotEmpty()) {
                        Text(
                            text = "Starring: ${displayCast.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }

                // Season chips — left button scrolls the row (hidden when chips fit on screen),
                // right button toggles episode sort order.
                item {
                    val seasons = addonMeta?.videos
                        ?.mapNotNull { it.season }
                        ?.filter { it > 0 }
                        ?.distinct()
                        ?.sorted()
                        ?: emptyList()
                    val chipScrollState = rememberScrollState()
                    // True once layout has run and chips overflow the available width
                    val isScrollable = chipScrollState.maxValue > 0
                    // Flip when the row has been scrolled all the way to the end
                    val isAtEnd = isScrollable && chipScrollState.value >= chipScrollState.maxValue
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Scroll helper — only visible when chips overflow; icon flips based on position
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

                        // Scrollable season chips
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

                        // Sort toggle — ascending / descending episode order
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

                // Queuing progress — slim bar
                if (isQueueing && queueTotalCount > 0) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { queuedCount.toFloat() / queueTotalCount },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp)),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Text(
                                text = "Queuing $queuedCount / $queueTotalCount episodes…",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha=0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                // Episodes list
                val episodes = (addonMeta?.videos?.filter { it.season == selectedSeason } ?: emptyList())
                    .let { if (episodesAscending) it else it.reversed() }
                val isActivePlaylistSeason = highlightSeason != null &&
                        selectedSeason == highlightSeason &&
                        playlistState != null
                // Episode number of playlist index 0, derived from current playing ep and current index
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
                    // Mark episodes as watched based on tracking progress
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
                        hasAddon = imdbId != null,
                        isPlaying = isEpPlaying,
                        isResolving = resolutionState.isResolving && resolutionState.episodeId == epNum,
                        isInActivePlaylist = isEpQueued,
                        isWatched = isEpWatched,
                        onClick = {
                            if (imdbId != null) {
                                // Jump if this episode is in the active playlist and queuing is done
                                if (!isQueueing && (isEpPlaying || isEpQueued)) {
                                    onPlaylistJump(epPlaylistIndex)
                                } else {
                                    startResolution(episode, false, false)
                                }
                            }
                        },
                        onLongClick = {
                            if (imdbId != null) {
                                startResolution(episode, false, true)
                            }
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
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
    onWatchOnTv: () -> Unit,
    onWatchOnTvLongClick: (() -> Unit)? = null,
    onWatchOnPhone: () -> Unit = {},
    onWatchOnPhoneLongClick: (() -> Unit)? = null
) {
    var showProvidersSheet by remember { mutableStateOf(false) }
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
            Surface(
                modifier = Modifier.clickable { watchOnTv = !watchOnTv },
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, Color.White.copy(alpha = 0.2f)
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
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = if (watchOnTv) {
                            if (!tvName.isNullOrBlank()) "TV ($tvName)" else "TV"
                        } else "Phone",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Switch destination",
                        modifier = Modifier.size(13.dp),
                        tint = Color.White.copy(alpha = 0.45f)
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
                            text = "Watch",
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
    onLongClick: () -> Unit
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
                if (!episode.released.isNullOrBlank()) {
                    Text(
                        text = episode.released,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                if (isWatched) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Watched",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
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

// ==================== Resolution States ====================

enum class ResolutionTarget { NONE, TV, PHONE }

data class ResolutionState(
    val isResolving: Boolean = false,
    val target: ResolutionTarget = ResolutionTarget.NONE,
    val episodeId: Int? = null
)

data class SeasonResolutionState(
    val isResolving: Boolean = false,
    val progressLabel: String = ""
)

// ==================== Queue / Playlist Data Classes ====================

/**
 * Context for a season queue operation (stored while the stream picker is open for Ep1)
 */
data class SeasonQueueContext(
    val imdbId: String,
    val season: Int,
    val episodes: List<StremioVideo>,
    val showName: String,
    val qualityFilter: List<String>,
    val runtimeMap: Map<Int, Int>
)

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

/**
 * Routing + detail screen for items tapped in an addon catalog.
 *
 * - Standard IMDb IDs ("tt..."): performs a TMDB /find lookup and redirects
 *   to the existing [MovieDetailScreen] or [TvShowDetailScreen].
 * - Non-standard IDs: fetches full metadata via [AddonRepository.fetchMeta] and
 *   displays a native detail view with stream resolution (Phase 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDetailScreen(
    id: String,
    type: String,
    addonRepository: AddonRepository,
    onMovieResolved: (tmdbId: Int) -> Unit,
    onTvShowResolved: (tmdbId: Int) -> Unit,
    onPlayStream: (url: String, title: String, subtitles: List<String>?) -> Unit = { _, _, _ -> },
    tvName: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var metaDetail by remember { mutableStateOf<StremioMetaDetail?>(null) }

    // Stream resolution state
    var resolvedStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var resolutionState by remember { mutableStateOf(ResolutionState()) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var resolvingEpisodeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id, type) {
        isLoading = true
        errorMessage = null

        when {
            id.startsWith("tt") -> {
                // Standard IMDb ID — look up TMDB ID via /find
                val result = tmdb.findByImdbId(id)
                when {
                    result == null -> {
                        errorMessage = "Could not reach TMDB. Check your API key and connection."
                    }
                    type == "movie" && result.movieResults.isNotEmpty() -> {
                        onMovieResolved(result.movieResults.first().id)
                        return@LaunchedEffect
                    }
                    type == "series" && result.tvResults.isNotEmpty() -> {
                        onTvShowResolved(result.tvResults.first().id)
                        return@LaunchedEffect
                    }
                    result.movieResults.isNotEmpty() -> {
                        // type mismatch — trust the data over the declared type
                        onMovieResolved(result.movieResults.first().id)
                        return@LaunchedEffect
                    }
                    result.tvResults.isNotEmpty() -> {
                        onTvShowResolved(result.tvResults.first().id)
                        return@LaunchedEffect
                    }
                    else -> {
                        errorMessage = "\"$id\" was not found on TMDB."
                    }
                }
            }
            else -> {
                // Non-IMDb ID — fetch meta from addon
                val meta = addonRepository.fetchMeta(type, id)
                if (meta != null) {
                    metaDetail = meta
                } else {
                    errorMessage = "Metadata for \"$id\" is not available from any installed addon."
                }
            }
        }
        isLoading = false
    }

    // Helper: start stream resolution for a given stream ID
    val startResolution = { streamId: String, forPhone: Boolean ->
        resolvingEpisodeId = streamId
        resolutionState = ResolutionState(
            isResolving = true,
            target = if (forPhone) ResolutionTarget.PHONE else ResolutionTarget.TV
        )
        resolvedStreams = emptyList()
        showStreamPicker = true
        scope.launch {
            addonRepository.resolveStreamsFlow(type, streamId).collect { latest ->
                resolvedStreams = latest
            }
            resolutionState = resolutionState.copy(isResolving = false)
            resolvingEpisodeId = null
        }
        Unit
    }

    // Stream picker sheet
    if (showStreamPicker) {
        val title = metaDetail?.name ?: id
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = resolutionState.isResolving,
            title = title,
            forceManual = false,
            onStreamSelected = { resolved ->
                val forPhone = resolutionState.target == ResolutionTarget.PHONE
                showStreamPicker = false
                resolutionState = ResolutionState()
                val streamUrl = resolved.stream.url ?: return@StreamPickerSheet
                if (forPhone) {
                    openInExternalPlayer(context, streamUrl, null, null)
                } else {
                    onPlayStream(streamUrl, title, null)
                    Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = {
                showStreamPicker = false
                resolutionState = ResolutionState()
                resolvingEpisodeId = null
            }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator()

            errorMessage != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onBack) { Text("Go back") }
            }

            metaDetail != null -> AddonMetaDetailContent(
                meta = metaDetail!!,
                type = type,
                resolutionState = resolutionState,
                resolvingEpisodeId = resolvingEpisodeId,
                tvName = tvName,
                onWatchOnTv = { streamId -> startResolution(streamId, false) },
                onWatchOnPhone = { streamId -> startResolution(streamId, true) }
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
            ),
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

// ==================== Meta Detail UI ====================

@Composable
private fun AddonMetaDetailContent(
    meta: StremioMetaDetail,
    type: String,
    resolutionState: ResolutionState,
    resolvingEpisodeId: String?,
    tvName: String? = null,
    onWatchOnTv: (streamId: String) -> Unit,
    onWatchOnPhone: (streamId: String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TranslucentBackground(backdropUrl = meta.background ?: meta.poster)
        
        val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + navBarPadding)
        ) {
            // Backdrop / poster header
            item {
                BackdropSection(
                    backdropUrl = meta.background ?: meta.poster,
                    logoUrl = null,
                    title = meta.name,
                    year = meta.year ?: "",
                    certification = "",
                    rating = meta.imdbRating ?: "",
                    runtime = meta.runtime ?: "",
                    genres = meta.genres,
                    omdbDetails = null
                )
            }

            // Description
            if (!meta.description.isNullOrBlank()) {
                item {
                    Text(
                        text = meta.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            }

            // Cast
            if (meta.cast.isNotEmpty()) {
                item {
                    Text(
                        text = "Starring: ${meta.cast.take(5).joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
            }

            // Play buttons — shown for movies, or for series that have no episode list
            if (type == "movie" || meta.videos.isEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SplitPlayButton(
                        isTvResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.TV,
                        isPhoneResolving = resolutionState.isResolving && resolutionState.target == ResolutionTarget.PHONE,
                        hasAddons = true,
                        hasImdbId = true,
                        watchProviders = emptyList(),
                        tvName = tvName,
                        onWatchOnTv = { onWatchOnTv(meta.id) },
                        onWatchOnTvLongClick = { onWatchOnTv(meta.id) }, // no picker forcing here yet
                        onWatchOnPhone = { onWatchOnPhone(meta.id) },
                        onWatchOnPhoneLongClick = { onWatchOnPhone(meta.id) }
                    )
                }
            }

            // Episodes list
            if (meta.videos.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                
                // Group by season if available, otherwise just list them
                val groupedEpisodes = meta.videos.groupBy { it.season ?: 1 }
                val sortedSeasons = groupedEpisodes.keys.sorted()
                
                sortedSeasons.forEach { seasonNum ->
                    if (sortedSeasons.size > 1) {
                        item {
                            Text(
                                text = "Season $seasonNum",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }
                    }
                    val episodes = groupedEpisodes[seasonNum] ?: emptyList()
                    items(episodes) { video ->
                        ElegantEpisodeItemRow(
                            video = video,
                            isResolving = resolvingEpisodeId == video.id && resolutionState.isResolving,
                            resolvingTarget = resolutionState.target,
                            onWatchOnTv = { onWatchOnTv(video.id) },
                            onWatchOnPhone = { onWatchOnPhone(video.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ElegantEpisodeItemRow(
    video: StremioVideo,
    isResolving: Boolean,
    resolvingTarget: ResolutionTarget,
    onWatchOnTv: () -> Unit,
    onWatchOnPhone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail Box
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.6f))
        ) {
            if (video.thumbnail != null) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center).size(32.dp)
                )
            }
            
            // Resolving Overlay
            if (isResolving) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)), contentAlignment=Alignment.Center) {
                    CircularProgressIndicator(modifier=Modifier.size(24.dp), color=Color.White, strokeWidth=2.dp)
                }
            }
        }
        
        Column(modifier = Modifier.weight(1f)) {
            val label = when {
                video.season != null && video.episode != null ->
                    "S${video.season}E${video.episode}" + if (video.title.isNotBlank()) " · ${video.title}" else ""
                video.title.isNotBlank() -> video.title
                else -> video.id
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            video.released?.take(10)?.let { date ->
                Spacer(modifier=Modifier.height(2.dp))
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier=Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onWatchOnTv,
                    enabled = !isResolving,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical=4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("TV", style=MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onWatchOnPhone,
                    enabled = !isResolving,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical=4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Phone", style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
