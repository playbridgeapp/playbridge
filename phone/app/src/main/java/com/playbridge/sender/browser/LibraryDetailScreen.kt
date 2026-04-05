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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.painter.ColorPainter
import com.playbridge.sender.data.library.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.CheckCircle

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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val omdb = remember { OmdbRepository(context) }
    val subtitleService = remember { StremioSubtitleService() }
    val scope = rememberCoroutineScope()

    var details by remember { mutableStateOf<TmdbMovieDetails?>(null) }
    var omdbDetails by remember { mutableStateOf<OmdbResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var trailerUrl by remember { mutableStateOf<String?>(null) }

    // Stream resolution state
    var resolvedStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var isResolving by remember { mutableStateOf(false) }
    var showStreamPicker by remember { mutableStateOf(false) }

    LaunchedEffect(movieId) {
        isLoading = true
        val movieDetails = tmdb.getMovieDetails(movieId)
        details = movieDetails
        val imdbId = movieDetails?.imdbId
        if (imdbId != null && omdb.isConfigured()) {
            omdbDetails = omdb.getDetailsByImdbId(imdbId)
        }
        isLoading = false
        trailerUrl = tmdb.getMovieVideos(movieId)?.bestTrailerUrl
    }

    val isWatchlisted by viewModel.isWatchlisted(movieId).collectAsState(initial = false)
    val tracked by viewModel.getTracked(movieId).collectAsState(initial = null)

    // Stream picker sheet
    if (showStreamPicker) {
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = isResolving,
            title = details?.title ?: "Select Stream",
            episodeRuntimeMinutes = details?.runtime,
            onStreamSelected = { resolved ->
                showStreamPicker = false
                val streamUrl = resolved.stream.url ?: return@StreamPickerSheet

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
            },
            onDismiss = { showStreamPicker = false }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Backdrop
                item {
                    BackdropSection(
                        backdropUrl = movie.backdropUrl,
                        logoUrl = movie.logoUrl,
                        title = movie.title,
                        year = movie.year,
                        certification = movie.certification,
                        rating = movie.rating,
                        runtime = movie.runtimeFormatted,
                        genres = movie.genres.map { it.name },
                        omdbDetails = omdbDetails
                    )
                }

                // Play button
                item {
                    val imdbId = movie.imdbId
                    PlayButton(
                        label = if (isResolving) "Resolving…" else "Play",
                        enabled = imdbId != null && !isResolving,
                        onClick = {
                            if (imdbId != null) {
                                resolvedStreams = emptyList()
                                showStreamPicker = true
                                isResolving = true
                                scope.launch {
                                    addonRepository.resolveMovieStreamsFlow(imdbId).collect { latest ->
                                        resolvedStreams = latest
                                    }
                                    isResolving = false
                                }
                            }
                        }
                    )
                    if (imdbId == null) {
                        Text(
                            text = "No IMDB ID available — cannot resolve streams",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
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
                        text = movie.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        color = Color.White
                    )
                    
                    if (movie.cast.isNotEmpty()) {
                        Text(
                            text = "Starring: ${movie.cast.joinToString(", ")}",
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val omdb = remember { OmdbRepository(context) }
    val subtitleService = remember { StremioSubtitleService() }
    val scope = rememberCoroutineScope()
    
    var details by remember { mutableStateOf<TmdbTvDetails?>(null) }
    var omdbDetails by remember { mutableStateOf<OmdbResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var trailerUrl by remember { mutableStateOf<String?>(null) }
    var selectedSeason by remember { mutableIntStateOf(highlightSeason ?: 1) }
    var seasonDetails by remember { mutableStateOf<TmdbSeason?>(null) }
    var isSeasonLoading by remember { mutableStateOf(false) }

    // Stream resolution state
    var resolvedStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var isResolving by remember { mutableStateOf(false) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var streamPickerTitle by remember { mutableStateOf("Select Stream") }

    // Season play state
    var isResolvingSeason by remember { mutableStateOf(false) }
    var seasonResolveProgress by remember { mutableStateOf("") }

    // Throttled queue state
    var queuedCount by remember { mutableIntStateOf(0) }
    var queueTotalCount by remember { mutableIntStateOf(0) }
    var isQueueing by remember { mutableStateOf(false) }

    // Episode selection for stream picker
    var currentEpisodeSelection by remember { mutableStateOf<TmdbEpisode?>(null) }
    // Context for season queue operations (holds episode list & preferences while Ep1 stream picker is open)
    var seasonQueueContext by remember { mutableStateOf<SeasonQueueContext?>(null) }

    val isWatchlisted by viewModel.isWatchlisted(tvId).collectAsState(initial = false)
    val tracked by viewModel.getTracked(tvId).collectAsState(initial = null)

    val episodeListState = rememberLazyListState()

    LaunchedEffect(tvId) {
        isLoading = true
        val tvDetails = tmdb.getTvDetails(tvId)
        details = tvDetails
        val imdbId = tvDetails?.imdbId
        if (imdbId != null && omdb.isConfigured()) {
            omdbDetails = omdb.getDetailsByImdbId(imdbId)
        }
        isLoading = false
        trailerUrl = tmdb.getTvVideos(tvId)?.bestTrailerUrl

        // Prefer the tracked season (if watching), otherwise default to first season
        val trackedSeason = tracked
            ?.takeIf { it.status == WatchlistStatus.WATCHING.value }
            ?.seasonProgress
        val targetSeason = trackedSeason
            ?: details?.seasons?.firstOrNull { it.seasonNumber > 0 }?.seasonNumber
            ?: 1
        selectedSeason = targetSeason
        isSeasonLoading = true
        seasonDetails = tmdb.getSeasonDetails(tvId, targetSeason)
        isSeasonLoading = false
    }

    // Stream picker sheet — when seasonQueueContext is set, picking a stream for Ep1
    // triggers the throttled queue for the rest of the season

    if (showStreamPicker) {
        val episodeRuntime = currentEpisodeSelection?.runtime
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = isResolving,
            title = streamPickerTitle,
            episodeRuntimeMinutes = episodeRuntime,
            onStreamSelected = { resolved ->
                showStreamPicker = false
                val streamUrl = resolved.stream.url ?: return@StreamPickerSheet

                scope.launch {
                    val subtitles = if (details?.imdbId != null && currentEpisodeSelection != null) {
                        val imdbId = details!!.imdbId!!
                        val episode = currentEpisodeSelection!!
                        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                        val prefLang = prefs.getString("preferred_subtitle_lang", "") ?: ""

                        if (prefLang.isNotEmpty()) {
                            try {
                                val allSubs = subtitleService.getSubtitlesForEpisode(imdbId, episode.seasonNumber, episode.episodeNumber)
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
                        onPlayPlaylist(listOf(ep1Payload))
                        // Notify BrowserActivity of which show/season is now playing
                        val startEp = sqCtx.episodes.firstOrNull()?.episodeNumber ?: 1
                        onNowPlayingStarted(tvId, sqCtx.season, startEp)
                        Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()

                        // Derive target bitrate + bingeGroup from selected Ep1 stream
                        val ep1Runtime = currentEpisodeSelection?.runtime
                        val targetMbps = resolved.stream.behaviorHints?.calculateMbps(ep1Runtime)
                        val preferredBingeGroup = resolved.stream.behaviorHints?.bingeGroup

                        // Start background queue for Eps 2+
                        val totalEpisodes = sqCtx.episodes.size
                        queueTotalCount = totalEpisodes
                        queuedCount = 1 // Ep1 is already playing
                        isQueueing = true
                        isResolvingSeason = true
                        seasonResolveProgress = "Queuing: 1/$totalEpisodes"

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
                                    seasonResolveProgress = "Queuing: $queuedCount/$totalEpisodes"
                                }
                            }
                        }

                        isResolvingSeason = false
                        isQueueing = false
                        seasonResolveProgress = ""
                        Toast.makeText(context, "All $queuedCount episodes queued", Toast.LENGTH_SHORT).show()
                    } else {
                        // Single episode play (not season queue)
                        onPlayStream(streamUrl, streamPickerTitle, subtitles.ifEmpty { null })
                        Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = {
                showStreamPicker = false
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

            LazyColumn(
                state = episodeListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Backdrop
                item {
                    BackdropSection(
                        backdropUrl = show.backdropUrl,
                        logoUrl = show.logoUrl,
                        title = show.name,
                        year = show.year,
                        certification = show.certification,
                        rating = show.rating,
                        runtime = "${show.numberOfSeasons} Season${if (show.numberOfSeasons != 1) "s" else ""}",
                        genres = show.genres.map { it.name },
                        omdbDetails = omdbDetails
                    )
                }

                // Play Top-Level button (Plays selected season)
                item {
                    val episodes = seasonDetails?.episodes ?: emptyList()
                    PlayButton(
                        label = if (isResolvingSeason) seasonResolveProgress else "Play",
                        enabled = imdbId != null && episodes.isNotEmpty() && !isSeasonLoading && !isResolvingSeason,
                        onClick = {
                            if (imdbId != null && episodes.isNotEmpty()) {
                                val ep1 = episodes.firstOrNull() ?: return@PlayButton
                                val runtimeMap = episodes.associate { ep ->
                                    ep.episodeNumber to (ep.runtime ?: 45)
                                }
                                currentEpisodeSelection = ep1
                                streamPickerTitle = "${show.name} S${selectedSeason}E${ep1.episodeNumber}"
                                resolvedStreams = emptyList()
                                showStreamPicker = true
                                isResolving = true
                                scope.launch {
                                    addonRepository.resolveEpisodeStreamsFlow(
                                        imdbId, selectedSeason, ep1.episodeNumber
                                    ).collect { latest ->
                                        resolvedStreams = latest
                                    }
                                    isResolving = false
                                }
                                seasonQueueContext = SeasonQueueContext(
                                    imdbId = imdbId,
                                    season = selectedSeason,
                                    episodes = episodes,
                                    showName = show.name,
                                    qualityFilter = emptyList(),
                                    runtimeMap = runtimeMap
                                )
                            }
                        }
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
                        text = show.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        color = Color.White
                    )
                    
                    if (show.cast.isNotEmpty()) {
                        Text(
                            text = "Starring: ${show.cast.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }

                // Season Chips
                item {
                    val seasons = show.seasons.filter { it.seasonNumber > 0 }
                    if (seasons.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            seasons.forEach { season ->
                                ElevatedFilterChip(
                                    selected = selectedSeason == season.seasonNumber,
                                    onClick = {
                                        selectedSeason = season.seasonNumber
                                        scope.launch {
                                            isSeasonLoading = true
                                            seasonDetails = tmdb.getSeasonDetails(tvId, season.seasonNumber)
                                            isSeasonLoading = false
                                        }
                                    },
                                    label = { Text("S${season.seasonNumber}") }
                                )
                            }
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

                // Episodes loading
                item {
                    if (isSeasonLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }

                // Episodes list
                val episodes = seasonDetails?.episodes ?: emptyList()
                val isActivePlaylistSeason = highlightSeason != null &&
                        selectedSeason == highlightSeason &&
                        playlistState != null
                // Episode number of playlist index 0, derived from current playing ep and current index
                val startEpisodeNumber = if (highlightEpisode != null && playlistState != null) {
                    highlightEpisode - playlistState.currentIndex
                } else null
                items(episodes.size) { index ->
                    val episode = episodes[index]
                    val epPlaylistIndex = if (startEpisodeNumber != null) {
                        episode.episodeNumber - startEpisodeNumber
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
                            selectedSeason == trackedSeason -> episode.episodeNumber < trackedEp
                            else -> false
                        }
                    } ?: false
                    EpisodeItem(
                        episode = episode,
                        hasAddon = imdbId != null,
                        isPlaying = isEpPlaying,
                        isInActivePlaylist = isEpQueued,
                        isWatched = isEpWatched,
                        onPlay = {
                            if (imdbId != null) {
                                // Jump if this episode is in the active playlist and queuing is done
                                if (!isQueueing && (isEpPlaying || isEpQueued)) {
                                    onPlaylistJump(epPlaylistIndex)
                                    return@EpisodeItem
                                }
                                // Otherwise open stream picker for this episode
                                currentEpisodeSelection = episode
                                streamPickerTitle = "${show.name} S${episode.seasonNumber}E${episode.episodeNumber}"
                                resolvedStreams = emptyList()
                                showStreamPicker = true
                                isResolving = true
                                scope.launch {
                                    addonRepository.resolveEpisodeStreamsFlow(
                                        imdbId, episode.seasonNumber, episode.episodeNumber
                                    ).collect { latest ->
                                        resolvedStreams = latest
                                    }
                                    isResolving = false
                                }
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

@Composable
private fun PlayButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(52.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
        )
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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

@Composable
private fun EpisodeItem(
    episode: TmdbEpisode,
    hasAddon: Boolean = false,
    isPlaying: Boolean = false,
    isInActivePlaylist: Boolean = false,
    isWatched: Boolean = false,
    onPlay: (() -> Unit)? = null
) {
    val containerColor = when {
        isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        isInActivePlaylist -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .clickable(enabled = hasAddon && onPlay != null) { onPlay?.invoke() }
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
                if (episode.stillUrl != null) {
                    AsyncImage(
                        model = episode.stillUrl,
                        contentDescription = episode.name,
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
                        text = episode.episodeNumber.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (isPlaying) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Playing", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Title, Date, Runtime
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (!episode.airDate.isNullOrBlank()) {
                    Text(
                        text = episode.airDate,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (episode.runtime != null) {
                        Text(
                            text = "${episode.runtime}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                    if (isWatched) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

         // Description
        Text(
            text = episode.overview,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== Queue / Playlist Data Classes ====================

/**
 * Context for a season queue operation (stored while the stream picker is open for Ep1)
 */
data class SeasonQueueContext(
    val imdbId: String,
    val season: Int,
    val episodes: List<TmdbEpisode>,
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
