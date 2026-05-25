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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    onAddonItemClick: (id: String, type: String, source: String?) -> Unit = { _, _, _ -> },
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
    onAddonItemClick: (id: String, type: String, source: String?) -> Unit = { _, _, _ -> },
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

    // Discovery state
    val selectedGenres by viewModel.selectedGenres.collectAsState()
    val excludedGenres by viewModel.excludedGenres.collectAsState()
    val matchAllGenres by viewModel.matchAllGenres.collectAsState()

    val discoveredMovies by viewModel.discoveredMovies.collectAsState()
    val isLoadingMoreDiscoveredMovies by viewModel.isLoadingMoreDiscoveredMovies.collectAsState()
    val hasMoreDiscoveredMovies by viewModel.hasMoreDiscoveredMovies.collectAsState()

    val discoveredTvShows by viewModel.discoveredTvShows.collectAsState()
    val isLoadingMoreDiscoveredTvShows by viewModel.isLoadingMoreDiscoveredTvShows.collectAsState()
    val hasMoreDiscoveredTvShows by viewModel.hasMoreDiscoveredTvShows.collectAsState()

    val selectedMediaType by viewModel.selectedMediaType.collectAsState()
    val selectedSort by viewModel.selectedSort.collectAsState()
    val selectedYearFrom by viewModel.selectedYearFrom.collectAsState()
    val selectedYearTo by viewModel.selectedYearTo.collectAsState()
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()
    val selectedOriginCountry by viewModel.selectedOriginCountry.collectAsState()
    val selectedMinRating by viewModel.selectedMinRating.collectAsState()
    val selectedMaxRating by viewModel.selectedMaxRating.collectAsState()
    val selectedMinVotes by viewModel.selectedMinVotes.collectAsState()
    val selectedRuntimeMin by viewModel.selectedRuntimeMin.collectAsState()
    val selectedRuntimeMax by viewModel.selectedRuntimeMax.collectAsState()
    val selectedWatchRegion by viewModel.selectedWatchRegion.collectAsState()
    val selectedProviders by viewModel.selectedProviders.collectAsState()
    val selectedMonetization by viewModel.selectedMonetization.collectAsState()
    val watchProviders by viewModel.watchProviders.collectAsState()
    val selectedCertification by viewModel.selectedCertification.collectAsState()
    val selectedReleaseTypes by viewModel.selectedReleaseTypes.collectAsState()
    val selectedTvStatuses by viewModel.selectedTvStatuses.collectAsState()
    val selectedTvTypes by viewModel.selectedTvTypes.collectAsState()
    val selectedKeywords by viewModel.selectedKeywords.collectAsState()
    val keywordResults by viewModel.keywordResults.collectAsState()
    val isSearchingKeywords by viewModel.isSearchingKeywords.collectAsState()
    val includeAdult by viewModel.includeAdult.collectAsState()

    val isDiscoveryLoading by viewModel.isDiscoveryLoading.collectAsState()

    // Home tab: which addon is selected in the filter chip row (null = All)
    var selectedAddonFilter by remember { mutableStateOf<String?>(null) }

    var selectedSearchSource by remember { mutableStateOf("") }

    BackHandler(enabled = isSearching) {
        viewModel.setIsSearching(false)
        selectedSearchSource = ""
    }

    // Reset search source chip when search session ends
    LaunchedEffect(isSearching) { if (!isSearching) selectedSearchSource = "" }

    // Focus the search field (and open the keyboard) when a search session starts.
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            delay(50) // let the TextField attach before requesting focus
            runCatching { searchFocusRequester.requestFocus() }
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
    val selectedTab by viewModel.selectedTab.collectAsState()

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
        val isTv = selectedMediaType == LibraryMediaType.TV_SHOW
        val isMovie = selectedMediaType == LibraryMediaType.MOVIE
        var yearFromInput by remember(selectedYearFrom) { mutableStateOf(selectedYearFrom) }
        var yearToInput by remember(selectedYearTo) { mutableStateOf(selectedYearTo) }
        LaunchedEffect(yearFromInput) { if (yearFromInput != selectedYearFrom) { delay(500); viewModel.setYearFrom(yearFromInput) } }
        LaunchedEffect(yearToInput) { if (yearToInput != selectedYearTo) { delay(500); viewModel.setYearTo(yearToInput) } }

        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (activeFilterCount > 0) {
                        TextButton(onClick = { viewModel.clearAllFilters() }) { Text("Clear all") }
                    }
                }

                // Type (All / Movies / Shows) lives in the Discover header now.

                // ── Sort ──
                FilterSectionLabel("Sort by")
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sorts = TmdbDiscoverSorts.list.filter { !(isTv && TmdbDiscoverSorts.forMovieOnly(it.label)) }
                    items(sorts) { sort ->
                        FilterChip(
                            selected = selectedSort == sort.label,
                            onClick = { viewModel.setSort(sort.label) },
                            label = { Text(sort.label) }
                        )
                    }
                }

                // ── Year range ──
                FilterSectionLabel("Year range")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    YearField(yearFromInput, "From") { yearFromInput = it }
                    Text("–", style = MaterialTheme.typography.bodyLarge)
                    YearField(yearToInput, "To") { yearToInput = it }
                }

                // ── Genres (tap = include, long-press = exclude) ──
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Genres", style = MaterialTheme.typography.titleMedium)
                    if (selectedGenres.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (matchAllGenres) "Match all" else "Match any",
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
                Text(
                    "Tap to include · long-press to exclude",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(3),
                    modifier = Modifier.height(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    gridItems(TmdbDiscoverGenres.list) { genre ->
                        GenreChip(
                            name = genre.name,
                            included = genre.name in selectedGenres,
                            excluded = genre.name in excludedGenres,
                            onInclude = { viewModel.toggleGenre(genre.name) },
                            onExclude = { viewModel.toggleExcludedGenre(genre.name) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Keywords (search TMDB, filter by with_keywords) ──
                var keywordQuery by remember { mutableStateOf("") }
                LaunchedEffect(keywordQuery) { viewModel.searchKeywords(keywordQuery) }
                FilterSectionLabel("Keywords")
                OutlinedTextField(
                    value = keywordQuery,
                    onValueChange = { keywordQuery = it },
                    placeholder = { Text("Search keywords… (e.g. dystopia)") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (isSearchingKeywords) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else if (keywordQuery.isNotEmpty()) {
                            IconButton(onClick = { keywordQuery = ""; viewModel.clearKeywordResults() }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                // Search results — tap to add
                if (keywordResults.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(keywordResults, key = { it.id }) { kw ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    viewModel.addKeyword(kw)
                                    keywordQuery = ""
                                },
                                label = { Text(kw.name) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            )
                        }
                    }
                }
                // Selected keywords — tap to remove
                if (selectedKeywords.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedKeywords, key = { it.id }) { kw ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.removeKeyword(kw.id) },
                                label = { Text(kw.name) },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Language ──
                FilterSectionLabel("Original language")
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(selected = selectedLanguage == null, onClick = { viewModel.setLanguage(null) }, label = { Text("All") })
                    }
                    items(TmdbLanguages.list) { language ->
                        FilterChip(
                            selected = selectedLanguage == language.code,
                            onClick = { viewModel.setLanguage(if (selectedLanguage == language.code) null else language.code) },
                            label = { Text(language.name) }
                        )
                    }
                }

                // ── Origin country ──
                FilterSectionLabel("Origin country")
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(selected = selectedOriginCountry == null, onClick = { viewModel.setOriginCountry(null) }, label = { Text("Any") })
                    }
                    items(TmdbDiscoverCountries.list) { country ->
                        FilterChip(
                            selected = selectedOriginCountry == country.code,
                            onClick = { viewModel.setOriginCountry(if (selectedOriginCountry == country.code) null else country.code) },
                            label = { Text(country.name) }
                        )
                    }
                }

                // ── Rating range ──
                FilterSectionLabel("Minimum rating")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.0 to "Any", 5.0 to "5+", 6.0 to "6+", 7.0 to "7+", 8.0 to "8+").forEach { (v, label) ->
                        FilterChip(selected = selectedMinRating == v, onClick = { viewModel.setMinRating(v) }, label = { Text(label) })
                    }
                }
                FilterSectionLabel("Maximum rating")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.0 to "Any", 6.0 to "≤6", 7.0 to "≤7", 8.0 to "≤8", 9.0 to "≤9").forEach { (v, label) ->
                        FilterChip(selected = selectedMaxRating == v, onClick = { viewModel.setMaxRating(v) }, label = { Text(label) })
                    }
                }

                // ── Minimum votes ──
                FilterSectionLabel("Minimum votes")
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(listOf(0 to "Any", 50 to "50+", 100 to "100+", 500 to "500+", 1000 to "1000+")) { (v, label) ->
                        FilterChip(selected = selectedMinVotes == v, onClick = { viewModel.setMinVotes(v) }, label = { Text(label) })
                    }
                }

                // ── Runtime ──
                FilterSectionLabel("Min runtime (min)")
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(listOf(0 to "Any", 30 to "30+", 60 to "60+", 90 to "90+", 120 to "120+")) { (v, label) ->
                        FilterChip(selected = selectedRuntimeMin == v, onClick = { viewModel.setRuntimeMin(v) }, label = { Text(label) })
                    }
                }
                FilterSectionLabel("Max runtime (min)")
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(listOf(0 to "Any", 90 to "≤90", 120 to "≤120", 150 to "≤150", 180 to "≤180")) { (v, label) ->
                        FilterChip(selected = selectedRuntimeMax == v, onClick = { viewModel.setRuntimeMax(v) }, label = { Text(label) })
                    }
                }

                // ── Watch providers ──
                FilterSectionLabel("Streaming region")
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(TmdbWatchRegions.list) { region ->
                        FilterChip(
                            selected = selectedWatchRegion == region.code,
                            onClick = { viewModel.setWatchRegion(region.code) },
                            label = { Text(region.code) }
                        )
                    }
                }
                if (watchProviders.isNotEmpty()) {
                    FilterSectionLabel("Available on")
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(watchProviders) { provider ->
                            FilterChip(
                                selected = provider.providerId in selectedProviders,
                                onClick = { viewModel.toggleProvider(provider.providerId) },
                                label = { Text(provider.providerName) },
                                leadingIcon = provider.logoUrl?.let { url ->
                                    {
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize).clip(RoundedCornerShape(4.dp))
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
                FilterSectionLabel("Monetization")
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(TmdbMonetizationTypes.list) { type ->
                        FilterChip(
                            selected = type in selectedMonetization,
                            onClick = { viewModel.toggleMonetization(type) },
                            label = { Text(type.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                // ── Include adult content ──
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include adult content", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Show titles marked adult on TMDB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = includeAdult,
                        onCheckedChange = { viewModel.setIncludeAdult(it) }
                    )
                }

                // ── Movie-only: certification + release type ──
                if (!isTv) {
                    FilterSectionLabel("Certification (US)")
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(selected = selectedCertification == null, onClick = { viewModel.setCertification(null) }, label = { Text("Any") })
                        }
                        items(TmdbMovieCertifications.list) { cert ->
                            FilterChip(
                                selected = selectedCertification == cert,
                                onClick = { viewModel.setCertification(if (selectedCertification == cert) null else cert) },
                                label = { Text(cert) }
                            )
                        }
                    }
                    FilterSectionLabel("Release type")
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(TmdbReleaseTypes.list) { rt ->
                            FilterChip(
                                selected = rt.value in selectedReleaseTypes,
                                onClick = { viewModel.toggleReleaseType(rt.value) },
                                label = { Text(rt.label) }
                            )
                        }
                    }
                }

                // ── TV-only: status + type ──
                if (!isMovie) {
                    FilterSectionLabel("TV status")
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(TmdbTvStatuses.list) { status ->
                            FilterChip(
                                selected = status.value in selectedTvStatuses,
                                onClick = { viewModel.toggleTvStatus(status.value) },
                                label = { Text(status.label) }
                            )
                        }
                    }
                    FilterSectionLabel("TV type")
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(TmdbTvTypes.list) { type ->
                            FilterChip(
                                selected = type.value in selectedTvTypes,
                                onClick = { viewModel.toggleTvType(type.value) },
                                label = { Text(type.label) }
                            )
                        }
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
            // Remote shortcut — available on every library tab when a TV is connected.
            val onRemote = onRemoteClick
            if (onRemote != null && !isSearching) {
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
                            // Persistent search bar — fills the header and makes search a
                            // primary, always-visible action instead of a hidden icon.
                            Surface(
                                onClick = { viewModel.setIsSearching(true) },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = "Search movies & shows…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
                        // Search now lives in the persistent search bar in the title slot.
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
                    listState = viewModel.searchResultsListState,
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
                    state = viewModel.mainListState,
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
                                    listState = viewModel.catalogRowScrollState(rowKey),
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

/**
 * Discover header row: a segmented All / Movies / Shows type selector with the
 * Filter button (badged with the active filter count) pinned to its right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverTypeHeader(
    selectedMediaType: LibraryMediaType,
    onTypeSelect: (LibraryMediaType) -> Unit,
    activeFilterCount: Int,
    onFilterClick: () -> Unit,
) {
    val types = listOf(
        LibraryMediaType.ALL to "All",
        LibraryMediaType.MOVIE to "Movies",
        LibraryMediaType.TV_SHOW to "Shows"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            types.forEachIndexed { index, (type, label) ->
                SegmentedButton(
                    selected = selectedMediaType == type,
                    onClick = { onTypeSelect(type) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = types.size),
                    label = { Text(label) }
                )
            }
        }
        IconButton(onClick = onFilterClick) {
            BadgedBox(badge = {
                if (activeFilterCount > 0) Badge { Text("$activeFilterCount") }
            }) {
                Icon(Icons.Default.FilterList, contentDescription = "Filters")
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
private fun AddonSearchResultsList(
    listState: LazyListState,
    results: List<StremioMetaPreview>,
    contentPadding: PaddingValues,
    onAddonItemClick: (id: String, type: String, source: String?) -> Unit = { _, _, _ -> },
    addonSearchGroups: List<AddonSearchResultGroup> = emptyList()
) {
    val movies = results.filter { it.type == "movie" }
    val series = results.filter { it.type == "series" || it.type == "anime" }
    val others = results.filter { it.type != "movie" && it.type != "series" && it.type != "anime" }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // If we have groups, we can show results attributed to their source
        if (addonSearchGroups.isNotEmpty()) {
            addonSearchGroups.forEach { group ->
                val groupMovies = group.items.filter { it.type == "movie" }
                val groupSeries = group.items.filter { it.type == "series" || it.type == "anime" }
                
                if (groupMovies.isNotEmpty()) {
                    item {
                        SearchSectionRow("${group.addonName} - Movies", groupMovies, group.addonName, onAddonItemClick)
                    }
                }
                if (groupSeries.isNotEmpty()) {
                    item {
                        SearchSectionRow("${group.addonName} - Series", groupSeries, group.addonName, onAddonItemClick)
                    }
                }
            }
        } else {
            // Fallback to legacy flat list if no groups provided (e.g. from filteredAddon)
            if (movies.isNotEmpty()) {
                item {
                    SearchSectionRow("Movies", movies, null, onAddonItemClick)
                }
            }
            if (series.isNotEmpty()) {
                item {
                    SearchSectionRow("Series", series, null, onAddonItemClick)
                }
            }
        }
    }
}

@Composable
private fun SearchSectionRow(
    title: String,
    items: List<StremioMetaPreview>,
    source: String?,
    onAddonItemClick: (id: String, type: String, source: String?) -> Unit
) {
    Column {
        SearchSectionHeader(title)
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(items, key = { it.id }) { item ->
                PosterCard(
                    posterUrl = item.poster,
                    title = item.name,
                    year = "",
                    rating = "",
                    onClick = { onAddonItemClick(item.id, item.type, source) }
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        fontWeight = FontWeight.Bold
    )
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
    listState: LazyListState,
    onItemClick: (StremioMetaPreview) -> Unit,
    onLoadMore: () -> Unit
) {
    if (row.isLoading && row.items.isEmpty()) return // hide until first page arrives

    // Trigger load-more when the user scrolls within 4 items of the end.
    val isNearEnd by remember(listState) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }
    }
    LaunchedEffect(isNearEnd) {
        if (isNearEnd && row.hasMore && !row.isLoadingMore) {
            onLoadMore()
        }
    }

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
            state = listState,
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
            // Spinner appended at the end of the row while the next page loads
            if (row.isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(108.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryList(
    history: List<com.playbridge.sender.data.history.SearchHistoryEntity>,
    contentPadding: PaddingValues,
    onQueryClick: (String) -> Unit,
    onRemoveClick: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (history.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "No search history",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Recent Searches",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onClearAll) {
                    Text("Clear All")
                }
            }
        }
        items(history) { item ->
            SearchHistoryItem(
                query = item.query,
                onClick = { onQueryClick(item.query) },
                onRemoveClick = { onRemoveClick(item.query) }
            )
        }
    }
}

@Composable
private fun SearchHistoryItem(
    query: String,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = query,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onRemoveClick) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== Discover filter sheet helpers ====================

@Composable
private fun FilterSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YearField(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 4) onChange(it.filter { c -> c.isDigit() }) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        modifier = Modifier.width(110.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenreChip(
    name: String,
    included: Boolean,
    excluded: Boolean,
    onInclude: () -> Unit,
    onExclude: () -> Unit
) {
    val container = when {
        included -> MaterialTheme.colorScheme.primary
        excluded -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        included -> MaterialTheme.colorScheme.onPrimary
        excluded -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = container,
        modifier = Modifier.combinedClickable(onClick = onInclude, onLongClick = onExclude)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (included || excluded) {
                Icon(
                    imageVector = if (excluded) Icons.Default.Close else Icons.Default.Check,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(name, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
