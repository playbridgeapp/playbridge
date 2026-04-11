package com.playbridge.sender.browser

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.painter.ColorPainter
import com.playbridge.sender.data.library.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Lightweight holder for the item the TrackingSheet is currently open for.
private data class TrackingTarget(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val year: String,
    val rating: String,
)

/**
 * Main Library screen — browse popular movies, TV shows, trending, and search.
 */
val LocalShowCardTextOverlay = compositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onMenuClick: () -> Unit,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit,
    nowPlayingTvId: Int? = null,
    nowPlayingSeason: Int? = null,
    nowPlayingEpisode: Int? = null,
    onNowPlayingClick: () -> Unit = {},
    onRemoteClick: (() -> Unit)? = null,
    onAddonItemClick: (id: String, type: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val browserPrefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    var showCardTextOverlay by remember { mutableStateOf(browserPrefs.getBoolean("show_card_text_overlay", false)) }

    DisposableEffect(browserPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "show_card_text_overlay") {
                showCardTextOverlay = prefs.getBoolean(key, false)
            }
        }
        browserPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { browserPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    CompositionLocalProvider(LocalShowCardTextOverlay provides showCardTextOverlay) {
        LibraryScreenContent(
            viewModel = viewModel,
            onMenuClick = onMenuClick,
            onMovieClick = onMovieClick,
            onTvShowClick = onTvShowClick,
            nowPlayingTvId = nowPlayingTvId,
            nowPlayingSeason = nowPlayingSeason,
            nowPlayingEpisode = nowPlayingEpisode,
            onNowPlayingClick = onNowPlayingClick,
            onRemoteClick = onRemoteClick,
            onAddonItemClick = onAddonItemClick,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    viewModel: LibraryViewModel,
    onMenuClick: () -> Unit,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit,
    nowPlayingTvId: Int? = null,
    nowPlayingSeason: Int? = null,
    nowPlayingEpisode: Int? = null,
    onNowPlayingClick: () -> Unit = {},
    onRemoteClick: (() -> Unit)? = null,
    onAddonItemClick: (id: String, type: String) -> Unit = { _, _ -> },
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    // Check if API key is configured (only needed for Browse/search)
    val isConfigured by viewModel.isConfigured.collectAsState()

    // Addon catalog rows — collected early so the ambient backdrop can derive from them
    val catalogRows by viewModel.catalogRows.collectAsState()

    // Ambient backdrop derived from addon catalog rows (helper is non-composable
    // so Modifier.background() cannot shadow the StremioMetaPreview.background property).
    val activeHeroBackdropUrl = remember(catalogRows) { firstAddonBackdropUrl(catalogRows) }

    // Search state
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearchLoading by viewModel.isSearchLoading.collectAsState()
    val addonSearchResults by viewModel.addonSearchResults.collectAsState()

    // Discovery state
    val selectedGenres by viewModel.selectedGenres.collectAsState()
    val matchAllGenres by viewModel.matchAllGenres.collectAsState()

    val discoveredMovies by viewModel.discoveredMovies.collectAsState()
    val isLoadingMoreDiscoveredMovies by viewModel.isLoadingMoreDiscoveredMovies.collectAsState()
    val hasMoreDiscoveredMovies by viewModel.hasMoreDiscoveredMovies.collectAsState()

    val discoveredTvShows by viewModel.discoveredTvShows.collectAsState()
    val isLoadingMoreDiscoveredTvShows by viewModel.isLoadingMoreDiscoveredTvShows.collectAsState()
    val hasMoreDiscoveredTvShows by viewModel.hasMoreDiscoveredTvShows.collectAsState()

    val selectedMediaType by viewModel.selectedMediaType.collectAsState()
    val selectedSortBy by viewModel.selectedSortBy.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()

    val isDiscoveryLoading by viewModel.isDiscoveryLoading.collectAsState()

    BackHandler(enabled = isSearching) {
        viewModel.setIsSearching(false)
    }

    var showFilterSheet by remember { mutableStateOf(false) }
    val selectedTab by viewModel.selectedTab.collectAsState()
    var yearInput by remember(selectedYear) { mutableStateOf(selectedYear) }

    // Tracking sheet state
    var trackingTarget by remember { mutableStateOf<TrackingTarget?>(null) }

    // Per-status lists for My List tab
    val watchlistAll by viewModel.watchlist.collectAsState()

    // New episode detection
    val newEpisodeTmdbIds by viewModel.newEpisodeTmdbIds.collectAsState()

    // Type and Sort are now inline chips — badge only counts genres + year
    val activeFilterCount = listOf(
        selectedGenres.isNotEmpty(),
        selectedYear.isNotBlank()
    ).count { it }

    // Debounce year input
    LaunchedEffect(yearInput) {
        if (yearInput != selectedYear) {
            delay(500)
            viewModel.setYear(yearInput)
        }
    }

    // Load initial data
    LaunchedEffect(Unit) {
        viewModel.checkConfigAndLoadInitialData()
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Type Filter
                Text("Type", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val mediaTypes = listOf(
                        LibraryMediaType.ALL to "All",
                        LibraryMediaType.MOVIE to "Movies",
                        LibraryMediaType.TV_SHOW to "TV Shows"
                    )
                    mediaTypes.forEach { (type, label) ->
                        FilterChip(
                            selected = selectedMediaType == type,
                            onClick = { viewModel.setMediaType(type) },
                            label = { Text(label) }
                        )
                    }
                }

                // Sort By Filter
                Text("Sort By", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sortOptions = listOf(
                        LibrarySortBy.POPULARITY_DESC to "Popular",
                        LibrarySortBy.PRIMARY_RELEASE_DATE_DESC to "Newest"
                    )
                    sortOptions.forEach { (sort, label) ->
                        FilterChip(
                            selected = selectedSortBy == sort,
                            onClick = { viewModel.setSortBy(sort) },
                            label = { Text(label) }
                        )
                    }
                }

                // Year filter
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Year", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = yearInput,
                        onValueChange = {
                            if (it.length <= 4) yearInput = it.filter { char -> char.isDigit() }
                        },
                        placeholder = { Text("YYYY") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        modifier = Modifier.width(100.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                // Discovery by Genre
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Genres", style = MaterialTheme.typography.titleMedium)
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

                LazyHorizontalGrid(
                    rows = GridCells.Fixed(2),
                    modifier = Modifier.height(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    gridItems(TmdbCommonGenres.list) { genre ->
                        val isSelected = selectedGenres.contains(genre.id)
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.toggleGenre(genre.id) },
                            label = { Text(genre.name) },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                                    )
                                }
                            } else null
                        )
                    }
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.End,
        floatingActionButton = {
            if (selectedTab == 1 && !isSearching) {
                FloatingActionButton(
                    onClick = { showFilterSheet = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    BadgedBox(badge = {
                        if (activeFilterCount > 0) Badge { Text("$activeFilterCount") }
                    }) {
                        Icon(Icons.Default.FilterList, "Filter")
                    }
                }
            }
        },
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
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
                        } else if (isConfigured) {
                            Surface(
                                shape = RoundedCornerShape(32.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val homeSelected = selectedTab == 0
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = if (homeSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable { viewModel.setSelectedTab(0) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Home,
                                            contentDescription = "Home",
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                                            tint = if (homeSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    val browseSelected = selectedTab == 1
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = if (browseSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable { viewModel.setSelectedTab(1) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Explore,
                                            contentDescription = "Browse",
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                                            tint = if (browseSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    val myListSelected = selectedTab == 2
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = if (myListSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                        modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable { viewModel.setSelectedTab(2) }
                                    ) {
                                        Icon(
                                            imageVector = if (myListSelected) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                            contentDescription = "My List",
                                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                                            tint = if (myListSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                }
                            }
                        }
                    },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isSearching) {
                                viewModel.setIsSearching(false)
                            } else {
                                onMenuClick()
                            }
                        }
                    ) {
                        if (isSearching) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        } else {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    }
                },
                actions = {
                    if (!isSearching) {
                        // Now Playing button — only visible when a season is being queued
                        if (nowPlayingTvId != null) {
                            val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.6f,
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                    animation = androidx.compose.animation.core.tween(800),
                                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                                ),
                                label = "pulseAlpha"
                            )
                            IconButton(onClick = onNowPlayingClick) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.alpha(pulseAlpha)
                                ) {
                                    Icon(
                                        Icons.Default.PlayCircle,
                                        contentDescription = "Now Playing",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.setIsSearching(true) }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                    } // closes if
                } // closes actions
            ) // closes TopAppBar
            } // closes Column
        } // closes topBar
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Dynamic Ambient Background behind TopAppBar
            if (selectedTab == 0 && !isSearching) {
                Box(modifier = Modifier.fillMaxWidth().height(350.dp)) {
                    AnimatedContent(
                        targetState = activeHeroBackdropUrl,
                        transitionSpec = {
                            fadeIn(tween(800)) togetherWith fadeOut(tween(800))
                        },
                        label = "HeroAmbientBackground"
                    ) { targetUrl ->
                        if (targetUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(targetUrl)
                                    .size(400, 225) // blurred — no need for full resolution
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.6f)
                                    .blur(60.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }
                    
                    // Gradient overlay applies persistently on top of the changing images
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.5f), MaterialTheme.colorScheme.background)
                                )
                            )
                    )
                }
            }
        }

        val watchlist by viewModel.watchlist.collectAsState()

        // TrackingSheet — shown when a media card is long-pressed
        trackingTarget?.let { target ->
            val entity = watchlist.find { it.tmdbId == target.tmdbId }
            TrackingSheet(
                entity = entity,
                mediaType = target.mediaType,
                title = target.title,
                onAction = { action ->
                    when (action) {
                        is TrackingAction.Upsert -> {
                            viewModel.upsertTracked(
                                tmdbId = target.tmdbId,
                                mediaType = target.mediaType,
                                title = target.title,
                                posterUrl = target.posterUrl,
                                year = target.year,
                                rating = target.rating,
                                status = action.status,
                            )
                            if (action.status != WatchlistStatus.PLAN_TO_WATCH) {
                                viewModel.setStatus(target.tmdbId, action.status)
                            }
                            if (action.season != null && action.episode != null) {
                                viewModel.setEpisodeProgress(target.tmdbId, action.season, action.episode)
                            }
                            if (action.rating != null) {
                                viewModel.setUserRating(target.tmdbId, action.rating)
                            }
                            viewModel.setNotes(target.tmdbId, action.notes)
                        }
                        is TrackingAction.Remove -> viewModel.removeTracked(target.tmdbId)
                        is TrackingAction.Dismiss -> Unit
                    }
                    trackingTarget = null
                }
            )
        }

        val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentBottomPadding = innerPadding.calculateBottomPadding() + navBarPadding + 80.dp
        if (isSearching) {
        // Search results
        if (isSearchLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (searchResults.isEmpty() && addonSearchResults.isEmpty() && searchQuery.isNotBlank()) {
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
            CombinedSearchResults(
                listState = viewModel.searchResultsListState,
                tmdbResults = searchResults,
                addonResults = addonSearchResults,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                    bottom = contentBottomPadding + 8.dp,
                    start = 8.dp,
                    end = 8.dp
                ),
                onMovieClick = onMovieClick,
                onTvShowClick = onTvShowClick,
                onAddonItemClick = onAddonItemClick
            )
        }
    } else {
        // Main catalog
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                if (targetState > initialState) {
                    // Moving from Home to Browse: Slide left to show new content coming from right
                    (slideInHorizontally { width -> width } + fadeIn(tween(400)))
                        .togetherWith(slideOutHorizontally { width -> -width } + fadeOut(tween(400))) 
                } else {
                    // Moving from Browse to Home: Slide right to show new content coming from left
                    (slideInHorizontally { width -> -width } + fadeIn(tween(400)))
                        .togetherWith(slideOutHorizontally { width -> width } + fadeOut(tween(400)))
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "TabAnimation"
        ) { tab ->
            if (tab == 2) {
                MyListTab(
                    watchlist = watchlistAll,
                    newEpisodeTmdbIds = newEpisodeTmdbIds,
                    contentPadding = innerPadding,
                    onItemClick = { entity ->
                        if (entity.mediaType == "movie") onMovieClick(entity.tmdbId)
                        else onTvShowClick(entity.tmdbId)
                    },
                    onLongPress = { entity ->
                        trackingTarget = TrackingTarget(
                            tmdbId = entity.tmdbId,
                            mediaType = entity.mediaType,
                            title = entity.title,
                            posterUrl = entity.posterUrl,
                            year = entity.year,
                            rating = entity.rating,
                        )
                    },
                )
            } else if (tab == 1) {
                // Browse / Discover tab — requires TMDB API key
                if (!isConfigured) {
                    ApiKeyPrompt()
                } else {
                    Box(modifier = Modifier.padding(top = innerPadding.calculateTopPadding() + WindowInsets.statusBars.asPaddingValues().calculateTopPadding())) {
                        DiscoverGrid(
                            movies = discoveredMovies,
                            tvShows = discoveredTvShows,
                            gridState = viewModel.discoverGridState,
                            selectedMediaType = selectedMediaType,
                            isLoadingMoreMovies = isLoadingMoreDiscoveredMovies,
                            hasMoreMovies = hasMoreDiscoveredMovies,
                            isLoadingMoreTvShows = isLoadingMoreDiscoveredTvShows,
                            hasMoreTvShows = hasMoreDiscoveredTvShows,
                            isDiscoveryLoading = isDiscoveryLoading,
                            onMovieClick = onMovieClick,
                            onTvShowClick = onTvShowClick,
                            onLoadMoreMovies = { viewModel.loadMoreDiscoveredMovies() },
                            onLoadMoreTvShows = { viewModel.loadMoreDiscoveredTvShows() }
                        )
                    }
                }
            } else {
                // Home tab — entirely addon-driven, no TMDB required
                LazyColumn(
                    state = viewModel.mainListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = contentBottomPadding)
                ) {
                    // Continue Watching — local watchlist, always available
                    val continueWatching = watchlist.filter {
                        it.status == WatchlistStatus.WATCHING.value
                    }
                    if (continueWatching.isNotEmpty()) {
                        item {
                            MediaRow(
                                title = "Continue Watching",
                                items = continueWatching,
                                listState = rememberLazyListState(),
                                onItemClick = { item ->
                                    if (item.mediaType == "movie") onMovieClick(item.tmdbId)
                                    else onTvShowClick(item.tmdbId)
                                },
                                onItemLongClick = { item ->
                                    trackingTarget = TrackingTarget(
                                        tmdbId = item.tmdbId,
                                        mediaType = item.mediaType,
                                        title = item.title,
                                        posterUrl = item.posterUrl,
                                        year = item.year,
                                        rating = item.rating,
                                    )
                                },
                                posterUrl = { it.posterUrl },
                                displayTitle = { it.title },
                                year = { it.year },
                                rating = { it.rating },
                                badgeText = { item ->
                                    if (item.mediaType == "tv" && newEpisodeTmdbIds.contains(item.tmdbId)) "New Episode" else null
                                },
                                hasMore = false,
                                isLoadingMore = false,
                                onLoadMore = {}
                            )
                        }
                    }

                    // Addon catalog rows — one horizontal row per installed catalog
                    if (catalogRows.isNotEmpty()) {
                        catalogRows.forEach { row ->
                            item(key = "${row.addonBaseUrl}:${row.type}:${row.catalogId}") {
                                AddonMediaRow(
                                    row = row,
                                    onItemClick = { item -> onAddonItemClick(item.id, item.type) }
                                )
                            }
                        }
                    } else if (continueWatching.isEmpty()) {
                        // Nothing to show — prompt user to install an addon
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Extension,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "No addons installed",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "Install a Stremio addon from Settings to see content here.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
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

@Composable
private fun DiscoverGrid(
    movies: List<TmdbMovie>,
    tvShows: List<TmdbTvShow>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    selectedMediaType: LibraryMediaType,
    isLoadingMoreMovies: Boolean,
    hasMoreMovies: Boolean,
    isLoadingMoreTvShows: Boolean,
    hasMoreTvShows: Boolean,
    isDiscoveryLoading: Boolean,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit,
    onLoadMoreMovies: () -> Unit,
    onLoadMoreTvShows: () -> Unit
) {
    if (isDiscoveryLoading) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp).weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        return
    }

    val hasMovies = movies.isNotEmpty()
    val hasTvShows = tvShows.isNotEmpty()

    if (!hasMovies && !hasTvShows) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp).weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No results found for selected filters.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val isNearEnd by remember {
        derivedStateOf {
            val totalItems = gridState.layoutInfo.totalItemsCount
            val lastVisibleItemIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 6
        }
    }

    LaunchedEffect(isNearEnd, isLoadingMoreMovies, hasMoreMovies, isLoadingMoreTvShows, hasMoreTvShows) {
        if (isNearEnd) {
            when (selectedMediaType) {
                LibraryMediaType.MOVIE -> {
                    if (!isLoadingMoreMovies && hasMoreMovies) onLoadMoreMovies()
                }
                LibraryMediaType.TV_SHOW -> {
                    if (!isLoadingMoreTvShows && hasMoreTvShows) onLoadMoreTvShows()
                }
                LibraryMediaType.ALL -> {
                    // Try loading both if we hit the bottom
                    if (!isLoadingMoreMovies && hasMoreMovies) onLoadMoreMovies()
                    if (!isLoadingMoreTvShows && hasMoreTvShows) onLoadMoreTvShows()
                }
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        if (selectedMediaType == LibraryMediaType.ALL) {
            val maxLen = maxOf(movies.size, tvShows.size)
            val mixed = buildList {
                for (i in 0 until maxLen) {
                    if (i < movies.size) add(Pair(movies[i], true))
                    if (i < tvShows.size) add(Pair(tvShows[i], false))
                }
            }
            gridItems(mixed) { pair ->
                if (pair.second) {
                    val movie = pair.first as TmdbMovie
                    PosterCard(
                        posterUrl = movie.posterUrl,
                        title = movie.title,
                        year = movie.year,
                        rating = movie.rating,
                        onClick = { onMovieClick(movie.id) }
                    )
                } else {
                    val tvShow = pair.first as TmdbTvShow
                    PosterCard(
                        posterUrl = tvShow.posterUrl,
                        title = tvShow.name,
                        year = tvShow.year,
                        rating = tvShow.rating,
                        onClick = { onTvShowClick(tvShow.id) }
                    )
                }
            }
            if ((isLoadingMoreMovies && hasMoreMovies) || (isLoadingMoreTvShows && hasMoreTvShows)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        } else if (selectedMediaType == LibraryMediaType.MOVIE) {
            if (hasMovies) {
                gridItems(movies) { item ->
                    PosterCard(
                        posterUrl = item.posterUrl,
                        title = item.title,
                        year = item.year,
                        rating = item.rating,
                        onClick = { onMovieClick(item.id) }
                    )
                }
                if (isLoadingMoreMovies && hasMoreMovies) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        } else if (selectedMediaType == LibraryMediaType.TV_SHOW) {
            if (hasTvShows) {
                gridItems(tvShows) { item ->
                    PosterCard(
                        posterUrl = item.posterUrl,
                        title = item.name,
                        year = item.year,
                        rating = item.rating,
                        onClick = { onTvShowClick(item.id) }
                    )
                }
                if (isLoadingMoreTvShows && hasMoreTvShows) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> MediaRow(
    title: String,
    items: List<T>,
    listState: LazyListState = rememberLazyListState(),
    onItemClick: (T) -> Unit,
    onItemLongClick: ((T) -> Unit)? = null,
    posterUrl: (T) -> String?,
    displayTitle: (T) -> String,
    year: (T) -> String,
    rating: (T) -> String,
    badgeText: (T) -> String? = { null },
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
                    label = badgeText(item),
                    onClick = { onItemClick(item) },
                    onLongClick = onItemLongClick?.let { handler -> { handler(item) } }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    posterUrl: String?,
    title: String,
    year: String,
    rating: String,
    label: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val showTextOverlay = LocalShowCardTextOverlay.current

    Card(
        modifier = Modifier
            .width(130.dp)
            .height(195.dp)
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else
                    Modifier.clickable(onClick = onClick)
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Poster image
            if (posterUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(posterUrl)
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

            // Label badge (Top End) — always visible regardless of text overlay setting
            if (!label.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 10.dp),
                    color = Color(0xFFE53935).copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            // Badges & Info Overlay
            if (showTextOverlay) {
                // Gradient overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                                startY = 50f
                            )
                        )
                )

                // Rating badge (Top Start)
                if (rating.isNotBlank() && rating != "0.0") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
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
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // Title & Year (Bottom Start)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (year.isNotBlank()) year else " ",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
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
    contentPadding: PaddingValues = PaddingValues(8.dp),
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
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
        shape = RoundedCornerShape(20.dp)
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
                            shape = RoundedCornerShape(20.dp),
                            color = if (result.isMovie)
                                MaterialTheme.colorScheme.secondaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceContainerHighest
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

/**
 * Unified search results list showing TMDB results followed by addon catalog results.
 * Addon results appear under a labelled divider so the user can distinguish the sources.
 */
@Composable
private fun CombinedSearchResults(
    listState: LazyListState,
    tmdbResults: List<TmdbMultiSearchResult>,
    addonResults: List<StremioMetaPreview>,
    contentPadding: PaddingValues,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit,
    onAddonItemClick: (id: String, type: String) -> Unit = { _, _ -> }
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // TMDB results
        items(tmdbResults) { result ->
            SearchResultItem(
                result = result,
                onClick = {
                    if (result.isMovie) onMovieClick(result.id)
                    else onTvShowClick(result.id)
                }
            )
        }

        // Addon results section
        if (addonResults.isNotEmpty()) {
            item {
                val topPad = if (tmdbResults.isNotEmpty()) 4.dp else 0.dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topPad, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text(
                        text = "From Addons",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }
            }
            items(addonResults) { item ->
                AddonSearchResultItem(
                    item = item,
                    onClick = { onAddonItemClick(item.id, item.type) }
                )
            }
        }
    }
}

@Composable
private fun AddonSearchResultItem(
    item: StremioMetaPreview,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp)
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
                if (item.poster != null) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.name,
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
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item.year?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        item.imdbRating?.let {
                            Text(
                                text = "★ $it",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = item.type.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (!item.description.isNullOrBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            shape = RoundedCornerShape(20.dp)
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

// ==================== Addon Media Row (Home tab) ====================

/**
 * A horizontal scrolling row for a single addon catalog, styled to match the
 * TMDB [MediaRow]s above it. The row title is the catalog name; an addon source
 * chip sits beside it so users know where the content comes from.
 */
@Composable
private fun AddonMediaRow(
    row: AddonCatalogRow,
    onItemClick: (StremioMetaPreview) -> Unit
) {
    if (row.isLoading && row.items.isEmpty()) return // hide until first page arrives

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        // Title + source chip
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = row.catalogName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Addon source chip
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = row.addonName,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(row.items) { item ->
                PosterCard(
                    posterUrl = item.poster,
                    title = item.name,
                    year = item.year ?: "",
                    rating = item.imdbRating ?: "",
                    onClick = { onItemClick(item) }
                )
            }
        }
    }
}
