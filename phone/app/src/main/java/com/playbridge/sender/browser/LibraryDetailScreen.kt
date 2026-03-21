package com.playbridge.sender.browser

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.playbridge.sender.data.library.*
import kotlinx.coroutines.launch

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
    }

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
                                // We can either filter by language or just pass all and let the player select it
                                // OpenSubtitles v3 addon doesn't expose language info easily without parsing,
                                // but Stremio player usually handles it. Actually, it does expose language in the 'id' or url sometimes,
                                // but just passing all detected subs from the addon is safest.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(details?.title ?: "Movie") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (details == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("Failed to load movie details")
            }
        } else {
            val movie = details!!
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Backdrop
                item {
                    BackdropSection(
                        backdropUrl = movie.backdropUrl,
                        title = movie.title,
                        year = movie.year,
                        rating = movie.rating,
                        runtime = movie.runtimeFormatted,
                        genres = movie.genres.map { it.name },
                        omdbDetails = omdbDetails
                    )
                }

                // Tagline
                item {
                    if (movie.tagline.isNotBlank()) {
                        Text(
                            text = movie.tagline,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // Overview
                item {
                    Text(
                        text = movie.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // IMDb ID info
                item {
                    if (movie.imdbId != null) {
                        Surface(
                            modifier = Modifier.padding(16.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "IMDB: ${movie.imdbId}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
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
    onPlayPlaylist: (items: List<com.playbridge.protocol.PlayPayload>) -> Unit,
    onQueueAdd: (com.playbridge.protocol.PlayPayload) -> Unit = {},
    onPlaylistJump: (Int) -> Unit = {},
    playlistState: PlaylistUiState? = null,
    onNowPlayingStarted: (tvId: Int, season: Int, startEpisode: Int) -> Unit = { _, _, _ -> },
    highlightSeason: Int? = null,
    highlightEpisode: Int? = null,
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

    LaunchedEffect(tvId) {
        isLoading = true
        val tvDetails = tmdb.getTvDetails(tvId)
        details = tvDetails
        val imdbId = tvDetails?.imdbId
        if (imdbId != null && omdb.isConfigured()) {
            omdbDetails = omdb.getDetailsByImdbId(imdbId)
        }
        isLoading = false

        // Auto-load first season (skip specials if possible)
        details?.seasons?.firstOrNull { it.seasonNumber > 0 }?.let { firstSeason ->
            selectedSeason = firstSeason.seasonNumber
            isSeasonLoading = true
            seasonDetails = tmdb.getSeasonDetails(tvId, firstSeason.seasonNumber)
            isSeasonLoading = false
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(details?.name ?: "TV Show") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (details == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("Failed to load TV show details")
            }
        } else {
            val show = details!!
            val imdbId = show.imdbId

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Backdrop
                item {
                    BackdropSection(
                        backdropUrl = show.backdropUrl,
                        title = show.name,
                        year = show.year,
                        rating = show.rating,
                        runtime = "${show.numberOfSeasons} Season${if (show.numberOfSeasons != 1) "s" else ""}",
                        genres = show.genres.map { it.name },
                        omdbDetails = omdbDetails
                    )
                }

                // Tagline
                item {
                    if (show.tagline.isNotBlank()) {
                        Text(
                            text = show.tagline,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                // Overview
                item {
                    Text(
                        text = show.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Season tabs
                item {
                    val seasons = show.seasons.filter { it.seasonNumber > 0 }
                    if (seasons.isNotEmpty()) {
                        Text(
                            text = "Seasons",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons) { season ->
                                FilterChip(
                                    selected = selectedSeason == season.seasonNumber,
                                    onClick = {
                                        selectedSeason = season.seasonNumber
                                        scope.launch {
                                            isSeasonLoading = true
                                            seasonDetails = tmdb.getSeasonDetails(tvId, season.seasonNumber)
                                            isSeasonLoading = false
                                        }
                                    },
                                    label = { Text("S${season.seasonNumber}") },
                                    modifier = Modifier.height(36.dp)
                                )
                            }
                        }
                    }
                }

                // Play Season button
                item {
                    val episodes = seasonDetails?.episodes ?: emptyList()
                    if (imdbId != null && episodes.isNotEmpty() && !isSeasonLoading) {
                        PlayButton(
                            label = if (isResolvingSeason) seasonResolveProgress else "Play Season $selectedSeason (${episodes.size} episodes)",
                            enabled = !isResolvingSeason,
                            onClick = {
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
                        )
                    }
                }

                // Queuing progress — slim bar below the Play Season button
                if (isQueueing && queueTotalCount > 0) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { queuedCount.toFloat() / queueTotalCount },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                            )
                            Text(
                                text = "Queuing $queuedCount / $queueTotalCount episodes…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    EpisodeItem(
                        episode = episode,
                        hasAddon = imdbId != null,
                        isPlaying = isEpPlaying,
                        isInActivePlaylist = isEpQueued,
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
    }
}

// ==================== Shared Components ====================

@Composable
private fun BackdropSection(
    backdropUrl: String?,
    title: String,
    year: String,
    rating: String,
    runtime: String,
    genres: List<String>,
    omdbDetails: OmdbResponse? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        if (backdropUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backdropUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                            MaterialTheme.colorScheme.background
                        ),
                        startY = 50f
                    )
                )
        )

        // Info overlay at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (year.isNotBlank()) {
                    Text(
                        text = year,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (rating.isNotBlank() && rating != "0.0") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = rating,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                if (omdbDetails?.imdbRating != null && omdbDetails.imdbRating != "N/A") {
                    Surface(
                        color = Color(0xFFF5C518),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "IMDb ${omdbDetails.imdbRating}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (omdbDetails?.rottenTomatoesRating != null && omdbDetails.rottenTomatoesRating != "N/A") {
                    Surface(
                        color = Color(0xFFFA320A),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "🍅 ${omdbDetails.rottenTomatoesRating}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (runtime.isNotBlank()) {
                    Text(
                        text = runtime,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (genres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = genres.joinToString(" • "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(48.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EpisodeItem(
    episode: TmdbEpisode,
    hasAddon: Boolean = false,
    isPlaying: Boolean = false,
    isInActivePlaylist: Boolean = false,
    onPlay: (() -> Unit)? = null
) {
    val containerColor = when {
        isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        isInActivePlaylist -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Episode still image
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (episode.stillUrl != null) {
                    AsyncImage(
                        model = episode.stillUrl,
                        contentDescription = episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "E${episode.episodeNumber}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${episode.episodeNumber}. ${episode.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (episode.runtime != null) {
                    Text(
                        text = "${episode.runtime} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action button
            if (hasAddon && onPlay != null) {
                when {
                    isPlaying -> Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Now playing",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    isInActivePlaylist -> IconButton(onClick = onPlay) {
                        Icon(
                            Icons.Default.PlayCircleOutline,
                            contentDescription = "Jump to episode",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    else -> IconButton(onClick = onPlay) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = "Play episode",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
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

