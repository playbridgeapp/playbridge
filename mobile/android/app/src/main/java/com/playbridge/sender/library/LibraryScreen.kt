package com.playbridge.sender.library

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.res.painterResource
import com.playbridge.sender.R
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.painter.ColorPainter
import com.playbridge.sender.data.library.*
import com.playbridge.sender.settings.SettingsScreen
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
    addonRepository: AddonRepository,
    installedAddons: List<InstalledAddonEntity>,
    onOpenUrl: (String) -> Unit,
    tvIp: String?,
    tvPort: Int?,
    tvName: String? = null,
    onMenuClick: () -> Unit,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit,
    onRemoteClick: (() -> Unit)? = null,
    onAddonItemClick: (id: String, type: String, source: String?) -> Unit = { _, _, _ -> },
    mainListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    discoveredMoviesListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    discoveredTvShowsListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    discoverGridState: LazyGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() },
    searchResultsListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    catalogRowScrollStates: MutableMap<String, LazyListState> = remember { mutableStateMapOf() },
    shouldFocusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
    onStartSearch: () -> Unit = {},
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
            addonRepository = addonRepository,
            installedAddons = installedAddons,
            onOpenUrl = onOpenUrl,
            tvIp = tvIp,
            tvPort = tvPort,
            tvName = tvName,
            onMenuClick = onMenuClick,
            onMovieClick = onMovieClick,
            onTvShowClick = onTvShowClick,
            onRemoteClick = onRemoteClick,
            onAddonItemClick = onAddonItemClick,
            mainListState = mainListState,
            discoveredMoviesListState = discoveredMoviesListState,
            discoveredTvShowsListState = discoveredTvShowsListState,
            discoverGridState = discoverGridState,
            searchResultsListState = searchResultsListState,
            catalogRowScrollStates = catalogRowScrollStates,
            shouldFocusSearch = shouldFocusSearch,
            onSearchFocused = onSearchFocused,
            onStartSearch = onStartSearch,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    viewModel: LibraryViewModel,
    addonRepository: AddonRepository,
    installedAddons: List<InstalledAddonEntity>,
    onOpenUrl: (String) -> Unit,
    tvIp: String?,
    tvPort: Int?,
    tvName: String?,
    onMenuClick: () -> Unit,
    onMovieClick: (Int) -> Unit,
    onTvShowClick: (Int) -> Unit,
    onRemoteClick: (() -> Unit)? = null,
    onAddonItemClick: (id: String, type: String, source: String?) -> Unit = { _, _, _ -> },
    mainListState: LazyListState,
    discoveredMoviesListState: LazyListState,
    discoveredTvShowsListState: LazyListState,
    discoverGridState: LazyGridState,
    searchResultsListState: LazyListState,
    catalogRowScrollStates: MutableMap<String, LazyListState>,
    shouldFocusSearch: Boolean,
    onSearchFocused: () -> Unit,
    onStartSearch: () -> Unit,
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
    val isSearchLoading by viewModel.isSearchLoading.collectAsState()
    val addonSearchGroups by viewModel.addonSearchGroups.collectAsState()
    val addonSearchResults = remember(addonSearchGroups) { addonSearchGroups.flatMap { it.items }.distinctBy { it.id } }
    val searchHistory by viewModel.searchHistory.collectAsState()



    // Discovery state - Collected from a single unified filters StateFlow
    val filters by viewModel.filters.collectAsState()

    val selectedGenres = filters.selectedGenres
    val excludedGenres = filters.excludedGenres
    val matchAllGenres = filters.matchAllGenres

    val discoveredMovies by viewModel.discoveredMovies.collectAsState()
    val isLoadingMoreDiscoveredMovies by viewModel.isLoadingMoreDiscoveredMovies.collectAsState()
    val hasMoreDiscoveredMovies by viewModel.hasMoreDiscoveredMovies.collectAsState()

    val discoveredTvShows by viewModel.discoveredTvShows.collectAsState()
    val isLoadingMoreDiscoveredTvShows by viewModel.isLoadingMoreDiscoveredTvShows.collectAsState()
    val hasMoreDiscoveredTvShows by viewModel.hasMoreDiscoveredTvShows.collectAsState()

    val selectedMediaType = filters.mediaType
    val selectedSort = filters.sort
    val selectedYearFrom = filters.yearFrom
    val selectedYearTo = filters.yearTo
    val selectedLanguage = filters.language
    val selectedOriginCountry = filters.originCountry
    val selectedMinRating = filters.minRating
    val selectedMaxRating = filters.maxRating
    val selectedMinVotes = filters.minVotes
    val selectedRuntimeMin = filters.runtimeMin
    val selectedRuntimeMax = filters.runtimeMax
    val selectedWatchRegion = filters.watchRegion
    val selectedProviders = filters.selectedProviders
    val selectedMonetization = filters.selectedMonetization
    val watchProviders by viewModel.watchProviders.collectAsState()
    val selectedCertification = filters.certification
    val selectedReleaseTypes = filters.selectedReleaseTypes
    val selectedTvStatuses = filters.selectedTvStatuses
    val selectedTvTypes = filters.selectedTvTypes
    val selectedKeywords = filters.selectedKeywords
    val keywordResults by viewModel.keywordResults.collectAsState()
    val isSearchingKeywords by viewModel.isSearchingKeywords.collectAsState()
    val includeAdult = filters.includeAdult

    val isDiscoveryLoading by viewModel.isDiscoveryLoading.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    // Home tab: which addon is selected in the filter chip row (null = All)
    var selectedAddonFilter by remember { mutableStateOf<String?>(null) }

    var selectedSearchSource by remember { mutableStateOf("") }

    BackHandler(enabled = isSearching) {
        viewModel.setIsSearching(false)
        selectedSearchSource = ""
    }

    BackHandler(enabled = selectedTab != 0 && !isSearching) {
        viewModel.setSelectedTab(0)
    }

    // Reset search source chip when search session ends
    LaunchedEffect(isSearching) { if (!isSearching) selectedSearchSource = "" }

    // Focus the search field (and open the keyboard) when a search session starts.
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(shouldFocusSearch) {
        if (shouldFocusSearch && isSearching) {
            delay(50) // let the TextField attach before requesting focus
            val success = runCatching { searchFocusRequester.requestFocus() }.isSuccess
            if (success) {
                onSearchFocused()
            }
        }
    }
    // Reset addon filter if the installed addons change (new addon installed, etc.)
    LaunchedEffect(catalogRows) {
        if (selectedAddonFilter != null &&
            catalogRows.none { it.addonName == selectedAddonFilter }) {
            selectedAddonFilter = null
        }
    }

    var showFilterSheet by remember { mutableStateOf(false) }

    // Tracking sheet state
    var trackingTarget by remember { mutableStateOf<TrackingTarget?>(null) }

    // Per-status lists for My List tab
    val watchlistAll by viewModel.watchlist.collectAsState()

    // New episode detection
    val newEpisodeTmdbIds by viewModel.newEpisodeTmdbIds.collectAsState()

    // Badge counts every non-default filter that's currently active.
    val activeFilterCount = listOf(
        selectedSort != "Popular",
        selectedGenres.isNotEmpty(),
        excludedGenres.isNotEmpty(),
        selectedYearFrom.isNotBlank(),
        selectedYearTo.isNotBlank(),
        selectedLanguage != null,
        selectedOriginCountry != null,
        selectedMinRating > 0.0,
        selectedMaxRating > 0.0,
        selectedMinVotes > 0,
        selectedRuntimeMin > 0,
        selectedRuntimeMax > 0,
        selectedProviders.isNotEmpty(),
        selectedMonetization.isNotEmpty(),
        selectedCertification != null,
        selectedReleaseTypes.isNotEmpty(),
        selectedTvStatuses.isNotEmpty(),
        selectedTvTypes.isNotEmpty(),
        selectedKeywords.isNotEmpty(),
        includeAdult
    ).count { it }

    // Load initial data
    LaunchedEffect(Unit) {
        viewModel.checkConfigAndLoadInitialData()
    }

    if (showFilterSheet) {
        LibraryFilterSheet(
            viewModel = viewModel,
            activeFilterCount = activeFilterCount,
            onDismiss = { showFilterSheet = false },
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.End,
        floatingActionButton = {
            // Remote shortcut — available on every library tab when a TV is connected.
            val onRemote = onRemoteClick
            if (onRemote != null && !isSearching && selectedTab < 3) {
                FloatingActionButton(
                    onClick = onRemote,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        Icons.Default.SettingsRemote,
                        contentDescription = "Remote"
                    )
                }
            }
        },
        topBar = {
            if (selectedTab < 3) {
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester)
                                )
                            } else {
                                // Centered title with Connected TV details
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "PlayBridge",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        val isConnected = !tvName.isNullOrBlank()
                                        Icon(
                                            imageVector = if (isConnected) Icons.Default.Tv else Icons.Default.Smartphone,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isConnected) "Watching on: $tvName" else "Watching on: Phone",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
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
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_playbridge_logo),
                                        contentDescription = "PlayBridge",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!isSearching) {
                                IconButton(
                                    onClick = {
                                        onStartSearch()
                                        viewModel.setIsSearching(true)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    ) // closes TopAppBar
                } // closes Column
            }
        },
        bottomBar = {
            if (!isSearching) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 8.dp
                ) {
                    // 1. Home
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { viewModel.setSelectedTab(0) },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") }
                    )

                    // 2. Discover
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { viewModel.setSelectedTab(1) },
                        icon = { Icon(Icons.Default.Explore, contentDescription = "Discover") },
                        label = { Text("Discover") }
                    )

                    // 3. Library
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { viewModel.setSelectedTab(2) },
                        icon = { Icon(Icons.Default.Bookmark, contentDescription = "Library") },
                        label = { Text("Library") }
                    )

                    // 4. Addons
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { viewModel.setSelectedTab(3) },
                        icon = { Icon(Icons.Default.Extension, contentDescription = "Addons") },
                        label = { Text("Addons") }
                    )

                    // 5. Settings
                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = { viewModel.setSelectedTab(4) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        }
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
        } else if (searchQuery.isBlank()) {
            // Show search history
            SearchHistoryList(
                history = searchHistory,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = contentBottomPadding + 8.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                onQueryClick = { query ->
                    viewModel.setSearchQuery(query)
                    viewModel.performSearch()
                },
                onRemoveClick = { viewModel.removeSearchHistory(it) },
                onClearAll = { viewModel.clearSearchHistory() }
            )
        } else if (addonSearchResults.isEmpty()) {
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
            // Derive which results to show based on selected source chip
            val filteredAddon = when {
                selectedSearchSource.isEmpty() -> addonSearchResults
                else -> addonSearchGroups.find { it.addonName == selectedSearchSource }?.items ?: emptyList()
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Source filter chips
                val addonSources = addonSearchGroups.map { it.addonName }
                if (addonSearchResults.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                top = innerPadding.calculateTopPadding() + 4.dp,
                                bottom = 4.dp
                            ),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedSearchSource.isEmpty(),
                                onClick = { selectedSearchSource = "" },
                                label = { Text("All") }
                            )
                        }
                        items(addonSources) { addonName ->
                            FilterChip(
                                selected = selectedSearchSource == addonName,
                                onClick = { selectedSearchSource = addonName },
                                label = { Text(addonName) }
                            )
                        }
                    }
                }

                AddonSearchResultsList(
                    listState = searchResultsListState,
                    results = filteredAddon,
                    contentPadding = PaddingValues(
                        top = if (addonSources.isNotEmpty()) 4.dp
                               else innerPadding.calculateTopPadding() + 8.dp,
                        bottom = contentBottomPadding + 8.dp,
                        start = 8.dp,
                        end = 8.dp
                    ),
                    onAddonItemClick = onAddonItemClick,
                    addonSearchGroups = addonSearchGroups
                )
            }
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
            if (tab == 3) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    AddonSettingsScreen(
                        addonRepository = addonRepository,
                        installedAddons = installedAddons,
                        onBack = { viewModel.setSelectedTab(0) },
                        onOpenUrl = onOpenUrl,
                        showBack = false,
                        onRefreshCatalogs = { viewModel.refreshCatalogsNow() },
                        onClearCatalogCache = { viewModel.clearCatalogCache() },
                        onCatalogsChanged = { viewModel.refreshCatalogsNow() }
                    )
                }
            } else if (tab == 4) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding())
                ) {
                    SettingsScreen(
                        onBack = { viewModel.setSelectedTab(0) },
                        tvIp = tvIp,
                        tvPort = tvPort,
                        showBack = false,
                        isFromLibrary = true
                    )
                }
            } else if (tab == 2) {
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
                    Column(modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding())) {
                        DiscoverTypeHeader(
                            selectedMediaType = selectedMediaType,
                            onTypeSelect = { viewModel.setMediaType(it) },
                            activeFilterCount = activeFilterCount,
                            onFilterClick = { showFilterSheet = true }
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            DiscoverGrid(
                                movies = discoveredMovies,
                                tvShows = discoveredTvShows,
                                gridState = discoverGridState,
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
                }
            } else {
                // Home tab — entirely addon-driven, no TMDB required
                // Derive the distinct addon names for the filter chips
                val addonNames = remember(catalogRows) {
                    catalogRows.map { it.addonName }.distinct()
                }
                val filteredCatalogRows = remember(catalogRows, selectedAddonFilter) {
                    if (selectedAddonFilter == null) catalogRows
                    else catalogRows.filter { it.addonName == selectedAddonFilter }
                }

                LazyColumn(
                    state = mainListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = contentBottomPadding
                    )
                ) {
                    // Addon filter chips — shown when more than one addon is installed
                    if (addonNames.size > 1) {
                        item(key = "addon_filter_chips") {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    FilterChip(
                                        selected = selectedAddonFilter == null,
                                        onClick = { selectedAddonFilter = null },
                                        label = { Text("All") }
                                    )
                                }
                                items(addonNames) { name ->
                                    FilterChip(
                                        selected = selectedAddonFilter == name,
                                        onClick = { selectedAddonFilter = name },
                                        label = { Text(name) }
                                    )
                                }
                            }
                        }
                    }

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
                    if (filteredCatalogRows.isNotEmpty()) {
                        filteredCatalogRows.forEach { row ->
                            val rowKey = "${row.addonBaseUrl}:${row.type}:${row.catalogId}"
                            item(key = rowKey) {
                                AddonMediaRow(
                                    row = row,
                                    listState = catalogRowScrollStates.getOrPut(rowKey) { LazyListState() },
                                    onItemClick = { item -> onAddonItemClick(item.id, item.type, row.addonName) },
                                    onLoadMore = {
                                        viewModel.loadMoreAddonRow(row.addonBaseUrl, row.type, row.catalogId)
                                    }
                                )
                            }
                        }
                    } else if (catalogRows.isEmpty() && continueWatching.isEmpty()) {
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

