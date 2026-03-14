package com.playbridge.sender.browser

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.playbridge.sender.data.library.*
import kotlinx.coroutines.launch

/**
 * Main Library screen — browse popular movies, TV shows, trending, and search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Check if API key is configured
    val isConfigured = remember { tmdb.isConfigured() }

    // Data state
    var popularMovies by remember { mutableStateOf<List<TmdbMovie>>(emptyList()) }
    var popularTvShows by remember { mutableStateOf<List<TmdbTvShow>>(emptyList()) }
    var trending by remember { mutableStateOf<List<TmdbMultiSearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<TmdbMultiSearchResult>>(emptyList()) }
    var isSearchLoading by remember { mutableStateOf(false) }

    // Discovery state
    var selectedGenres by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var matchAllGenres by remember { mutableStateOf(false) }
    var discoveredMovies by remember { mutableStateOf<List<TmdbMovie>>(emptyList()) }
    var discoveredTvShows by remember { mutableStateOf<List<TmdbTvShow>>(emptyList()) }
    var isDiscoveryLoading by remember { mutableStateOf(false) }

    // Load discovery data when genres or match type changes
    LaunchedEffect(selectedGenres, matchAllGenres) {
        if (selectedGenres.isEmpty()) {
            discoveredMovies = emptyList()
            discoveredTvShows = emptyList()
            return@LaunchedEffect
        }

        isDiscoveryLoading = true
        val separator = if (matchAllGenres) "," else "|"
        val genreString = selectedGenres.joinToString(separator)

        val movies = tmdb.discoverMovies(withGenres = genreString)
        val tv = tmdb.discoverTvShows(withGenres = genreString)

        discoveredMovies = movies.results
        discoveredTvShows = tv.results
        isDiscoveryLoading = false
    }

    // Load initial data
    LaunchedEffect(isConfigured) {
        if (isConfigured) {
            isLoading = true
            val movies = tmdb.getPopularMovies()
            val tvShows = tmdb.getPopularTvShows()
            val trend = tmdb.getTrending()
            popularMovies = movies.results
            popularTvShows = tvShows.results
            trending = trend.results.filter { it.isMovie || it.isTvShow }
            isLoading = false
        } else {
            isLoading = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search movies & TV shows...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboardController?.hide()
                                    if (searchQuery.isNotBlank()) {
                                        scope.launch {
                                            isSearchLoading = true
                                            val results = tmdb.searchMulti(searchQuery)
                                            searchResults = results.results.filter { it.isMovie || it.isTvShow }
                                            isSearchLoading = false
                                        }
                                    }
                                }
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Library")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                            searchResults = emptyList()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (!isSearching) {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!isConfigured) {
                // No API key — show setup prompt
                ApiKeyPrompt()
            } else if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (isSearching) {
                // Search results
                if (isSearchLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (searchResults.isEmpty() && searchQuery.isNotBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No results found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    SearchResultsList(
                        results = searchResults,
                        onMovieClick = onMovieClick,
                        onTvShowClick = onTvShowClick
                    )
                }
            } else {
                // Main catalog
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // Discovery Filters
                    item {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Discover by Genre",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (selectedGenres.size > 1) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (matchAllGenres) "Match All" else "Match Any",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        Switch(
                                            checked = matchAllGenres,
                                            onCheckedChange = { matchAllGenres = it },
                                            modifier = Modifier.scale(0.8f)
                                        )
                                    }
                                }
                            }

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(TmdbCommonGenres.list) { genre ->
                                    val isSelected = selectedGenres.contains(genre.id)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedGenres = if (isSelected) {
                                                selectedGenres - genre.id
                                            } else {
                                                selectedGenres + genre.id
                                            }
                                        },
                                        label = { Text(genre.name) },
                                        leadingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                                )
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }

                    if (selectedGenres.isNotEmpty()) {
                        if (isDiscoveryLoading) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else {
                            if (discoveredMovies.isNotEmpty()) {
                                item {
                                    MediaRow(
                                        title = "✨ Discovered Movies",
                                        items = discoveredMovies,
                                        onItemClick = { onMovieClick(it.id) },
                                        posterUrl = { it.posterUrl },
                                        displayTitle = { it.title },
                                        year = { it.year },
                                        rating = { it.rating }
                                    )
                                }
                            }
                            if (discoveredTvShows.isNotEmpty()) {
                                item {
                                    MediaRow(
                                        title = "✨ Discovered TV Shows",
                                        items = discoveredTvShows,
                                        onItemClick = { onTvShowClick(it.id) },
                                        posterUrl = { it.posterUrl },
                                        displayTitle = { it.name },
                                        year = { it.year },
                                        rating = { it.rating }
                                    )
                                }
                            }
                            if (discoveredMovies.isEmpty() && discoveredTvShows.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "No results found for selected genres.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                    // Trending Today
                    if (trending.isNotEmpty()) {
                        item {
                            MediaRow(
                                title = "🔥 Trending Today",
                                items = trending,
                                onItemClick = { item ->
                                    if (item.isMovie) onMovieClick(item.id)
                                    else onTvShowClick(item.id)
                                },
                                posterUrl = { it.posterUrl },
                                displayTitle = { it.displayTitle },
                                year = { it.year },
                                rating = { String.format("%.1f", it.voteAverage) }
                            )
                        }
                    }

                    // Popular Movies
                    if (popularMovies.isNotEmpty()) {
                        item {
                            MediaRow(
                                title = "🎬 Popular Movies",
                                items = popularMovies,
                                onItemClick = { onMovieClick(it.id) },
                                posterUrl = { it.posterUrl },
                                displayTitle = { it.title },
                                year = { it.year },
                                rating = { it.rating }
                            )
                        }
                    }

                    // Popular TV Shows
                    if (popularTvShows.isNotEmpty()) {
                        item {
                            MediaRow(
                                title = "📺 Popular TV Shows",
                                items = popularTvShows,
                                onItemClick = { onTvShowClick(it.id) },
                                posterUrl = { it.posterUrl },
                                displayTitle = { it.name },
                                year = { it.year },
                                rating = { it.rating }
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

// ==================== Reusable Components ====================

@Composable
private fun <T> MediaRow(
    title: String,
    items: List<T>,
    onItemClick: (T) -> Unit,
    posterUrl: (T) -> String?,
    displayTitle: (T) -> String,
    year: (T) -> String,
    rating: (T) -> String
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                PosterCard(
                    posterUrl = posterUrl(item),
                    title = displayTitle(item),
                    year = year(item),
                    rating = rating(item),
                    onClick = { onItemClick(item) }
                )
            }
        }
    }
}

@Composable
private fun PosterCard(
    posterUrl: String?,
    title: String,
    year: String,
    rating: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            // Poster image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(195.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                if (posterUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(posterUrl)
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
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // Rating badge
                if (rating.isNotBlank() && rating != "0.0") {
                    Surface(
                        shape = RoundedCornerShape(bottomEnd = 12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = rating,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            // Title & Year
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (year.isNotBlank()) {
                    Text(
                        text = year,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<TmdbMultiSearchResult>,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(results) { result ->
            SearchResultItem(
                result = result,
                onClick = {
                    if (result.isMovie) onMovieClick(result.id)
                    else onTvShowClick(result.id)
                }
            )
        }
    }
}

@Composable
private fun SearchResultItem(
    result: TmdbMultiSearchResult,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // Poster
            Box(
                modifier = Modifier
                    .width(70.dp)
                    .height(105.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (result.posterUrl != null) {
                    AsyncImage(
                        model = result.posterUrl,
                        contentDescription = result.displayTitle,
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
                        Icon(Icons.Default.Movie, null, modifier = Modifier.size(24.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(105.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = result.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (result.year.isNotBlank()) {
                            Text(
                                text = result.year,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (result.isMovie)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = if (result.isMovie) "Movie" else "TV",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = result.overview,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ApiKeyPrompt() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "TMDB API Key Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "To browse movies and TV shows, you need a free TMDB API key. Go to Settings to enter your key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Get a free key at themoviedb.org",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
