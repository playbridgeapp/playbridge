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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
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
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    // Check if API key is configured
    val isConfigured by viewModel.isConfigured.collectAsState()

    // Data state
    val popularMovies by viewModel.popularMovies.collectAsState()
    val isLoadingMorePopularMovies by viewModel.isLoadingMorePopularMovies.collectAsState()
    val hasMorePopularMovies by viewModel.hasMorePopularMovies.collectAsState()

    val popularTvShows by viewModel.popularTvShows.collectAsState()
    val isLoadingMorePopularTvShows by viewModel.isLoadingMorePopularTvShows.collectAsState()
    val hasMorePopularTvShows by viewModel.hasMorePopularTvShows.collectAsState()

    val trending by viewModel.trending.collectAsState()
    val isLoadingMoreTrending by viewModel.isLoadingMoreTrending.collectAsState()
    val hasMoreTrending by viewModel.hasMoreTrending.collectAsState()

    val isLoading by viewModel.isLoading.collectAsState()

    // Search state
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearchLoading by viewModel.isSearchLoading.collectAsState()

    // Discovery state
    val selectedGenres by viewModel.selectedGenres.collectAsState()
    val matchAllGenres by viewModel.matchAllGenres.collectAsState()

    val discoveredMovies by viewModel.discoveredMovies.collectAsState()
    val isLoadingMoreDiscoveredMovies by viewModel.isLoadingMoreDiscoveredMovies.collectAsState()
    val hasMoreDiscoveredMovies by viewModel.hasMoreDiscoveredMovies.collectAsState()

    val discoveredTvShows by viewModel.discoveredTvShows.collectAsState()
    val isLoadingMoreDiscoveredTvShows by viewModel.isLoadingMoreDiscoveredTvShows.collectAsState()
    val hasMoreDiscoveredTvShows by viewModel.hasMoreDiscoveredTvShows.collectAsState()

    val isDiscoveryLoading by viewModel.isDiscoveryLoading.collectAsState()

    // Load initial data
    LaunchedEffect(Unit) {
        viewModel.checkConfigAndLoadInitialData()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search movies & TV shows...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboardController?.hide()
                                    viewModel.performSearch()
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
                            viewModel.setIsSearching(false)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (!isSearching) {
                        IconButton(onClick = { viewModel.setIsSearching(true) }) {
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
                        listState = viewModel.searchResultsListState,
                        results = searchResults,
                        onMovieClick = onMovieClick,
                        onTvShowClick = onTvShowClick
                    )
                }
            } else {
                // Main catalog
                LazyColumn(
                    state = viewModel.mainListState,
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
                                            onCheckedChange = { viewModel.setMatchAllGenres(it) },
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
                                        onClick = { viewModel.toggleGenre(genre.id) },
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
                                        listState = viewModel.discoveredMoviesListState,
                                        onItemClick = { onMovieClick(it.id) },
                                        posterUrl = { it.posterUrl },
                                        displayTitle = { it.title },
                                        year = { it.year },
                                        rating = { it.rating },
                                        onLoadMore = { viewModel.loadMoreDiscoveredMovies() },
                                        isLoadingMore = isLoadingMoreDiscoveredMovies,
                                        hasMore = hasMoreDiscoveredMovies
                                    )
                                }
                            }
                            if (discoveredTvShows.isNotEmpty()) {
                                item {
                                    MediaRow(
                                        title = "✨ Discovered TV Shows",
                                        items = discoveredTvShows,
                                        listState = viewModel.discoveredTvShowsListState,
                                        onItemClick = { onTvShowClick(it.id) },
                                        posterUrl = { it.posterUrl },
                                        displayTitle = { it.name },
                                        year = { it.year },
                                        rating = { it.rating },
                                        onLoadMore = { viewModel.loadMoreDiscoveredTvShows() },
                                        isLoadingMore = isLoadingMoreDiscoveredTvShows,
                                        hasMore = hasMoreDiscoveredTvShows
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
                                listState = viewModel.trendingListState,
                                onItemClick = { item ->
                                    if (item.isMovie) onMovieClick(item.id)
                                    else onTvShowClick(item.id)
                                },
                                posterUrl = { it.posterUrl },
                                displayTitle = { it.displayTitle },
                                year = { it.year },
                                rating = { String.format("%.1f", it.voteAverage) },
                                onLoadMore = { viewModel.loadMoreTrending() },
                                isLoadingMore = isLoadingMoreTrending,
                                hasMore = hasMoreTrending
                            )
                        }
                    }

                    // Popular Movies
                    if (popularMovies.isNotEmpty()) {
                        item {
                            MediaRow(
                                title = "🎬 Popular Movies",
                                items = popularMovies,
                                listState = viewModel.popularMoviesListState,
                                onItemClick = { onMovieClick(it.id) },
                                posterUrl = { it.posterUrl },
                                displayTitle = { it.title },
                                year = { it.year },
                                rating = { it.rating },
                                onLoadMore = { viewModel.loadMorePopularMovies() },
                                isLoadingMore = isLoadingMorePopularMovies,
                                hasMore = hasMorePopularMovies
                            )
                        }
                    }

                    // Popular TV Shows
                    if (popularTvShows.isNotEmpty()) {
                        item {
                            MediaRow(
                                title = "📺 Popular TV Shows",
                                items = popularTvShows,
                                listState = viewModel.popularTvShowsListState,
                                onItemClick = { onTvShowClick(it.id) },
                                posterUrl = { it.posterUrl },
                                displayTitle = { it.name },
                                year = { it.year },
                                rating = { it.rating },
                                onLoadMore = { viewModel.loadMorePopularTvShows() },
                                isLoadingMore = isLoadingMorePopularTvShows,
                                hasMore = hasMorePopularTvShows
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
    listState: LazyListState = rememberLazyListState(),
    onItemClick: (T) -> Unit,
    posterUrl: (T) -> String?,
    displayTitle: (T) -> String,
    year: (T) -> String,
    rating: (T) -> String,
    onLoadMore: () -> Unit = {},
    isLoadingMore: Boolean = false,
    hasMore: Boolean = true
) {

    val isNearEnd by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 3
        }
    }

    // Trigger load more when scrolling near the end
    LaunchedEffect(isNearEnd, isLoadingMore, hasMore) {
        if (isNearEnd && !isLoadingMore && hasMore) {
            onLoadMore()
        }
    }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyRow(
            state = listState,
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
            if (isLoadingMore && hasMore) {
                item {
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(195.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
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
    listState: LazyListState = rememberLazyListState(),
    results: List<TmdbMultiSearchResult>,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit
) {
    LazyColumn(
        state = listState,
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
