package com.playbridge.sender.browser

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.library.AddonCatalogRow
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.data.library.AddonSearchResultGroup
import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.data.library.StremioMetaPreview
import com.playbridge.sender.data.library.isFeatureEnabled
import com.playbridge.sender.data.library.parsedCatalogEntries
import com.playbridge.sender.data.library.TmdbMovie
import com.playbridge.sender.data.library.TmdbMultiSearchResult
import com.playbridge.sender.data.library.TmdbRepository
import com.playbridge.sender.data.library.TmdbTvShow
import com.playbridge.sender.data.library.parseCatalogTitle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.library.WatchlistStatus
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdb = TmdbRepository(application)
    private val database = DatabaseProvider.getDatabase(application)
    private val watchlistDao = database.watchlistDao()
    private val searchHistoryDao = database.searchHistoryDao()
    private val addonRepository = AddonRepository(
        addonDao = database.addonDao(),
        cacheDir = application.cacheDir
    )

    /**
     * Serialises all watchlist write operations so that rapid taps cannot
     * observe stale StateFlow snapshots and fire duplicate inserts/deletes.
     */
    private val watchlistMutex = Mutex()

    val watchlist: StateFlow<List<WatchlistEntity>> = watchlistDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Scroll States
    val mainListState = LazyListState()
    val discoveredMoviesListState = LazyListState()
    val discoveredTvShowsListState = LazyListState()
    val discoverGridState = LazyGridState()
    val searchResultsListState = LazyListState()

    /**
     * Per-row horizontal scroll states for addon catalog rows, keyed by
     * "${addonBaseUrl}:${type}:${catalogId}".
     * Stored in the ViewModel so position is preserved across recompositions
     * and tab switches.
     */
    private val _catalogRowScrollStates = java.util.concurrent.ConcurrentHashMap<String, LazyListState>()

    fun catalogRowScrollState(key: String): LazyListState =
        _catalogRowScrollStates.getOrPut(key) { LazyListState() }

    private val _isConfigured = MutableStateFlow(tmdb.isConfigured())
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()


    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

    // Addon search results — grouped by addon so the UI can show per-source filter chips.
    // Use .flatMap { it.items } to get the flat list if needed.
    private val _addonSearchGroups = MutableStateFlow<List<AddonSearchResultGroup>>(emptyList())
    val addonSearchGroups: StateFlow<List<AddonSearchResultGroup>> = _addonSearchGroups.asStateFlow()

    // Search history
    val searchHistory: StateFlow<List<com.playbridge.sender.data.history.SearchHistoryEntity>> = searchHistoryDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Navigation state
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    // Discovery state
    private val _selectedMediaType = MutableStateFlow(LibraryMediaType.ALL)
    val selectedMediaType: StateFlow<LibraryMediaType> = _selectedMediaType.asStateFlow()

    private val _selectedSortBy = MutableStateFlow(LibrarySortBy.POPULARITY_DESC)
    val selectedSortBy: StateFlow<LibrarySortBy> = _selectedSortBy.asStateFlow()

    private val _selectedYear = MutableStateFlow("")
    val selectedYear: StateFlow<String> = _selectedYear.asStateFlow()

    private val _selectedGenres = MutableStateFlow<Set<Int>>(emptySet())
    val selectedGenres: StateFlow<Set<Int>> = _selectedGenres.asStateFlow()

    private val _matchAllGenres = MutableStateFlow(false)
    val matchAllGenres: StateFlow<Boolean> = _matchAllGenres.asStateFlow()

    private val _discoveredMovies = MutableStateFlow<List<TmdbMovie>>(emptyList())
    val discoveredMovies: StateFlow<List<TmdbMovie>> = _discoveredMovies.asStateFlow()
    private var discoveredMoviesPage = 1
    private val _isLoadingMoreDiscoveredMovies = MutableStateFlow(false)
    val isLoadingMoreDiscoveredMovies: StateFlow<Boolean> = _isLoadingMoreDiscoveredMovies.asStateFlow()
    private val _hasMoreDiscoveredMovies = MutableStateFlow(true)
    val hasMoreDiscoveredMovies: StateFlow<Boolean> = _hasMoreDiscoveredMovies.asStateFlow()

    private val _discoveredTvShows = MutableStateFlow<List<TmdbTvShow>>(emptyList())
    val discoveredTvShows: StateFlow<List<TmdbTvShow>> = _discoveredTvShows.asStateFlow()
    private var discoveredTvShowsPage = 1
    private val _isLoadingMoreDiscoveredTvShows = MutableStateFlow(false)
    val isLoadingMoreDiscoveredTvShows: StateFlow<Boolean> = _isLoadingMoreDiscoveredTvShows.asStateFlow()
    private val _hasMoreDiscoveredTvShows = MutableStateFlow(true)
    val hasMoreDiscoveredTvShows: StateFlow<Boolean> = _hasMoreDiscoveredTvShows.asStateFlow()

    private val _isDiscoveryLoading = MutableStateFlow(false)
    val isDiscoveryLoading: StateFlow<Boolean> = _isDiscoveryLoading.asStateFlow()

    // ---------------------------------------------------------------------------
    // Addon Catalog rows (Home tab — one horizontal row per catalog, loaded in parallel)
    // ---------------------------------------------------------------------------

    private val _catalogRows = MutableStateFlow<List<AddonCatalogRow>>(emptyList())
    val catalogRows: StateFlow<List<AddonCatalogRow>> = _catalogRows.asStateFlow()

    fun loadCatalogRows(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val addons = addonRepository.getInstalledAddons()
            Log.d("LibraryViewModel", "loadCatalogRows: found ${addons.size} addons")
            if (addons.isEmpty()) {
                _catalogRows.value = emptyList()
                return@launch
            }

            val now = System.currentTimeMillis()

            // Serve cache immediately if fresh enough (< 5 min) and not forced
            val cached = catalogCache
            if (!forceRefresh && cached != null && (now - catalogCacheTime) < CATALOG_CACHE_TTL_MS) {
                Log.d("LibraryViewModel", "loadCatalogRows: serving cache with ${cached.size} rows")
                _catalogRows.value = cached
                return@launch
            }

            // Build skeleton rows so the UI shows shimmer/loading state right away
            val skeletons = addons.filter { it.isFeatureEnabled("catalog") }.flatMap { addon ->
                Log.d("LibraryViewModel", "Addon ${addon.name} catalogsJson: ${addon.catalogsJson}")
                val entries = addon.parsedCatalogEntries()
                Log.d("LibraryViewModel", "Addon ${addon.name} has ${entries.size} catalog entries")
                entries.filter { it.type.isNotBlank() && it.id.isNotBlank() }
                    .map { entry ->
                        val (provider, cleanTitle) = parseCatalogTitle(addon.name, entry.name.ifBlank { entry.id })
                        AddonCatalogRow(
                            catalogName = cleanTitle,
                            addonName = provider,
                            type = entry.type,
                            catalogId = entry.id,
                            addonBaseUrl = addon.baseUrl,
                            isLoading = true
                        )
                    }
            }
            Log.d("LibraryViewModel", "loadCatalogRows: built ${skeletons.size} skeletons")
            _catalogRows.value = skeletons

            // Fetch catalogs in parallel; update the list as each one lands (no need to wait for all)
            val filled = mutableListOf<AddonCatalogRow>()
            val mutex = kotlinx.coroutines.sync.Mutex()

            val deferreds = addons.filter { it.isFeatureEnabled("catalog") }.flatMap { addon ->
                addon.parsedCatalogEntries()
                    .filter { it.type.isNotBlank() && it.id.isNotBlank() }
                    .map { entry ->
                        async {
                            val items = runCatching {
                                Log.d("LibraryViewModel", "Fetching catalog: ${addon.name} -> ${entry.name}")
                                addonRepository.fetchCatalog(addon, entry.type, entry.id, skip = 0)
                            }.onFailure {
                                Log.e("LibraryViewModel", "Failed to fetch catalog ${entry.name} from ${addon.name}", it)
                            }.getOrDefault(emptyList())

                            Log.d("LibraryViewModel", "Fetched ${items.size} items for ${addon.name} -> ${entry.name}")
                            if (items.isNotEmpty()) {
                                val (provider, cleanTitle) = parseCatalogTitle(addon.name, entry.name.ifBlank { entry.id })
                                val row = AddonCatalogRow(
                                    catalogName = cleanTitle,
                                    addonName = provider,
                                    type = entry.type,
                                    catalogId = entry.id,
                                    addonBaseUrl = addon.baseUrl,
                                    items = items,
                                    isLoading = false
                                )
                                mutex.withLock {
                                    filled.add(row)
                                    _catalogRows.value = filled.toList()
                                }
                            }
                        }
                    }
            }
            deferreds.forEach { it.await() }
            Log.d("LibraryViewModel", "loadCatalogRows: all fetches complete. Final row count: ${_catalogRows.value.size}")
            // Store completed result in cache
            catalogCache = _catalogRows.value
            catalogCacheTime = System.currentTimeMillis()
        }
    }

    /**
     * Appends the next page (skip + 100) for a single addon catalog row.
     * Safe to call repeatedly — guards against concurrent fetches via [isLoadingMore]
     * and stops automatically once a page comes back empty ([hasMore] = false).
     *
     * Since [viewModelScope] dispatches on [Dispatchers.Main], the guard check and
     * the [isLoadingMore] flag update both happen before the first suspension point,
     * so there is no risk of two concurrent fetches for the same row.
     */
    fun loadMoreAddonRow(addonBaseUrl: String, type: String, catalogId: String) {
        viewModelScope.launch {
            val currentRows = _catalogRows.value
            val rowIndex = currentRows.indexOfFirst {
                it.addonBaseUrl == addonBaseUrl && it.type == type && it.catalogId == catalogId
            }
            if (rowIndex == -1) return@launch
            val row = currentRows[rowIndex]
            if (!row.hasMore || row.isLoadingMore) return@launch

            // Mark as loading before the first suspension so the guard holds.
            _catalogRows.value = currentRows.toMutableList().apply {
                this[rowIndex] = row.copy(isLoadingMore = true)
            }

            val addon = addonRepository.getInstalledAddons()
                .firstOrNull { it.baseUrl == addonBaseUrl }

            if (addon == null) {
                updateCatalogRow(addonBaseUrl, type, catalogId) {
                    it.copy(isLoadingMore = false, hasMore = false)
                }
                return@launch
            }

            val nextSkip = row.currentSkip + 100
            val newItems = runCatching {
                addonRepository.fetchCatalog(addon, type, catalogId, skip = nextSkip)
            }.getOrDefault(emptyList())

            updateCatalogRow(addonBaseUrl, type, catalogId) { existing ->
                existing.copy(
                    items = existing.items + newItems,
                    isLoadingMore = false,
                    currentSkip = nextSkip,
                    hasMore = newItems.isNotEmpty()
                )
            }
        }
    }

    /** Applies [transform] to the matching row in [_catalogRows], leaving all others unchanged. */
    private fun updateCatalogRow(
        addonBaseUrl: String,
        type: String,
        catalogId: String,
        transform: (AddonCatalogRow) -> AddonCatalogRow
    ) {
        _catalogRows.value = _catalogRows.value.toMutableList().apply {
            val idx = indexOfFirst {
                it.addonBaseUrl == addonBaseUrl && it.type == type && it.catalogId == catalogId
            }
            if (idx != -1) this[idx] = transform(this[idx])
        }
    }

    companion object {
        private const val CATALOG_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        // Shared across ViewModel instances within the same process lifetime
        @Volatile private var catalogCache: List<AddonCatalogRow>? = null
        @Volatile private var catalogCacheTime: Long = 0L

        /** Call when addons are installed/removed to force a fresh fetch on next load. */
        fun invalidateCatalogCache() {
            catalogCache = null
            catalogCacheTime = 0L
        }
    }

    // ---------------------------------------------------------------------------
    // New-episode detection
    // ---------------------------------------------------------------------------

    /**
     * Set of TMDB IDs (TV shows only) for which a new episode has aired since
     * the item was added to the watchlist. Refreshed whenever the Watching list changes.
     */
    private val _newEpisodeTmdbIds = MutableStateFlow<Set<Int>>(emptySet())
    val newEpisodeTmdbIds: StateFlow<Set<Int>> = _newEpisodeTmdbIds.asStateFlow()

    private fun observeNewEpisodes() {
        viewModelScope.launch {
            watching.collect { watchingItems ->
                val tvItems = watchingItems.filter { it.mediaType == "tv" }
                if (tvItems.isEmpty()) {
                    _newEpisodeTmdbIds.value = emptySet()
                    return@collect
                }
                val ids = mutableSetOf<Int>()
                tvItems.forEach { entity ->
                    try {
                        // Only flag shows where the user has explicitly set a progress position
                        val userSeason = entity.seasonProgress ?: return@forEach
                        val userEp    = entity.episodeProgress ?: return@forEach

                        val details = tmdb.getTvDetails(entity.tmdbId) ?: return@forEach

                        val hasAvailable = when (val next = details.nextEpisodeToAir) {
                            // Show is actively airing — everything before nextEpisodeToAir has aired.
                            // Badge if the user's position is at least one episode behind that boundary.
                            null -> {
                                // Show is on hiatus or ended.
                                // Approximate: flag if lastAirDate is after the user started tracking
                                // (best we can do without fetching full season details).
                                val lastAirMs = parseIsoDate(details.lastAirDate ?: return@forEach)
                                    ?: return@forEach
                                val referenceMs = entity.startedAt ?: entity.addedAt
                                lastAirMs > referenceMs
                            }
                            else -> when {
                                // User is in an earlier season than where the show currently is —
                                // there are definitely aired episodes in later seasons ahead of them.
                                userSeason < next.seasonNumber -> true
                                // Same season: badge only when the next-to-air ep is at least
                                // two ahead of the user, meaning at least one ep has aired since their progress.
                                userSeason == next.seasonNumber -> userEp < next.episodeNumber - 1
                                // User is somehow past the boundary (shouldn't happen) — no badge.
                                else -> false
                            }
                        }

                        if (hasAvailable) ids.add(entity.tmdbId)
                    } catch (_: Exception) { }
                }
                _newEpisodeTmdbIds.value = ids
            }
        }
    }

    private fun parseIsoDate(dateStr: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dateStr)?.time
    } catch (_: Exception) { null }

    /**
     * Re-checks whether a TMDB API key is configured and fires the initial
     * Discovery query (Browse tab). The Home tab is addon-driven and loads
     * independently via [loadCatalogRows].
     */
    fun checkConfigAndLoadInitialData() {
        _isConfigured.value = tmdb.isConfigured()
        if (_isConfigured.value) triggerDiscovery()
    }

    // Search
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setIsSearching(searching: Boolean) {
        _isSearching.value = searching
        if (!searching) {
            _searchQuery.value = ""
            _addonSearchGroups.value = emptyList()
        }
    }

    fun performSearch() {
        val query = _searchQuery.value
        if (query.isBlank()) return

        viewModelScope.launch {
            _isSearchLoading.value = true
            _addonSearchGroups.value = emptyList()

            // Save to history
            runCatching {
                searchHistoryDao.insert(com.playbridge.sender.data.history.SearchHistoryEntity(query, System.currentTimeMillis()))
            }

            // Addons: each catalog publishes its group as soon as it responds
            try {
                addonRepository.searchAllCatalogsGroupedStreaming(query) { groups ->
                    _addonSearchGroups.value = groups
                    // Clear loading spinner once we have at least one response
                    _isSearchLoading.value = false
                }
            } catch (e: Exception) {
                // ...
            } finally {
                _isSearchLoading.value = false
            }
        }
    }

    fun removeSearchHistory(query: String) {
        viewModelScope.launch {
            searchHistoryDao.deleteByQuery(query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            searchHistoryDao.deleteAll()
        }
    }

    // Discovery
    fun toggleGenre(genreId: Int) {
        val currentGenres = _selectedGenres.value
        if (currentGenres.contains(genreId)) {
            _selectedGenres.value = currentGenres - genreId
        } else {
            _selectedGenres.value = currentGenres + genreId
        }
        triggerDiscovery()
    }

    fun setMatchAllGenres(matchAll: Boolean) {
        _matchAllGenres.value = matchAll
        triggerDiscovery()
    }

    fun setMediaType(mediaType: LibraryMediaType) {
        _selectedMediaType.value = mediaType
        triggerDiscovery()
    }

    fun setSortBy(sortBy: LibrarySortBy) {
        _selectedSortBy.value = sortBy
        triggerDiscovery()
    }

    fun setYear(year: String) {
        _selectedYear.value = year
        triggerDiscovery()
    }

    private fun triggerDiscovery() {
        val genres = _selectedGenres.value
        val mediaType = _selectedMediaType.value
        val year = _selectedYear.value

        viewModelScope.launch {
            _isDiscoveryLoading.value = true
            try {
                discoveredMoviesPage = 1
                discoveredTvShowsPage = 1
                _hasMoreDiscoveredMovies.value = true
                _hasMoreDiscoveredTvShows.value = true

                val separator = if (_matchAllGenres.value) "," else "|"
                val genreString = if (genres.isNotEmpty()) genres.joinToString(separator) else null
                val sortBy = _selectedSortBy.value.apiValue
                val yearParam = if (year.isNotBlank()) year else null

                if (mediaType == LibraryMediaType.ALL || mediaType == LibraryMediaType.MOVIE) {
                    val movies = tmdb.discoverMovies(page = 1, withGenres = genreString, sortBy = sortBy, year = yearParam)
                    _discoveredMovies.value = movies.results
                } else {
                    _discoveredMovies.value = emptyList()
                    _hasMoreDiscoveredMovies.value = false
                }

                if (mediaType == LibraryMediaType.ALL || mediaType == LibraryMediaType.TV_SHOW) {
                    val tv = tmdb.discoverTvShows(page = 1, withGenres = genreString, sortBy = sortBy, year = yearParam)
                    _discoveredTvShows.value = tv.results
                } else {
                    _discoveredTvShows.value = emptyList()
                    _hasMoreDiscoveredTvShows.value = false
                }
            } finally {
                _isDiscoveryLoading.value = false
            }
        }
    }

    fun loadMoreDiscoveredMovies() {
        if (_isLoadingMoreDiscoveredMovies.value || !_hasMoreDiscoveredMovies.value) return

        viewModelScope.launch {
            _isLoadingMoreDiscoveredMovies.value = true
            try {
                if (_selectedMediaType.value == LibraryMediaType.TV_SHOW) return@launch
                val separator = if (_matchAllGenres.value) "," else "|"
                val genreString = if (_selectedGenres.value.isNotEmpty()) _selectedGenres.value.joinToString(separator) else null
                val sortBy = _selectedSortBy.value.apiValue
                val yearParam = if (_selectedYear.value.isNotBlank()) _selectedYear.value else null
                val nextPage = discoveredMoviesPage + 1
                val newMovies = tmdb.discoverMovies(page = nextPage, withGenres = genreString, sortBy = sortBy, year = yearParam)
                if (newMovies.results.isNotEmpty()) {
                    _discoveredMovies.value = _discoveredMovies.value + newMovies.results
                    discoveredMoviesPage = nextPage
                } else {
                    _hasMoreDiscoveredMovies.value = false
                }
            } finally {
                _isLoadingMoreDiscoveredMovies.value = false
            }
        }
    }

    fun loadMoreDiscoveredTvShows() {
        if (_isLoadingMoreDiscoveredTvShows.value || !_hasMoreDiscoveredTvShows.value) return

        viewModelScope.launch {
            _isLoadingMoreDiscoveredTvShows.value = true
            try {
                if (_selectedMediaType.value == LibraryMediaType.MOVIE) return@launch
                val separator = if (_matchAllGenres.value) "," else "|"
                val genreString = if (_selectedGenres.value.isNotEmpty()) _selectedGenres.value.joinToString(separator) else null
                val sortBy = _selectedSortBy.value.apiValue
                val yearParam = if (_selectedYear.value.isNotBlank()) _selectedYear.value else null
                val nextPage = discoveredTvShowsPage + 1
                val newTvShows = tmdb.discoverTvShows(page = nextPage, withGenres = genreString, sortBy = sortBy, year = yearParam)
                if (newTvShows.results.isNotEmpty()) {
                    _discoveredTvShows.value = _discoveredTvShows.value + newTvShows.results
                    discoveredTvShowsPage = nextPage
                } else {
                    _hasMoreDiscoveredTvShows.value = false
                }
            } finally {
                _isLoadingMoreDiscoveredTvShows.value = false
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Tracking — per-status flows
    // ---------------------------------------------------------------------------

    val watching: StateFlow<List<WatchlistEntity>> =
        watchlistDao.getByStatus(WatchlistStatus.WATCHING.value)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val planToWatch: StateFlow<List<WatchlistEntity>> =
        watchlistDao.getByStatus(WatchlistStatus.PLAN_TO_WATCH.value)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completed: StateFlow<List<WatchlistEntity>> =
        watchlistDao.getByStatus(WatchlistStatus.COMPLETED.value)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val onHold: StateFlow<List<WatchlistEntity>> =
        watchlistDao.getByStatus(WatchlistStatus.ON_HOLD.value)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dropped: StateFlow<List<WatchlistEntity>> =
        watchlistDao.getByStatus(WatchlistStatus.DROPPED.value)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        checkConfigAndLoadInitialData()
        observeNewEpisodes()
        loadCatalogRows()
    }

    // ---------------------------------------------------------------------------
    // Tracking — reads
    // ---------------------------------------------------------------------------

    fun isWatchlisted(tmdbId: Int): Flow<Boolean> =
        watchlistDao.isWatchlisted(tmdbId)

    fun getTracked(tmdbId: Int): Flow<WatchlistEntity?> =
        watchlistDao.getById(tmdbId)

    // ---------------------------------------------------------------------------
    // Tracking — writes
    // ---------------------------------------------------------------------------

    /**
     * Adds an item to the tracked list with the given initial status, or updates
     * the status if it is already tracked.
     *
     * Uses [watchlistMutex] and reads the current DB row directly so that rapid
     * successive calls cannot race on a stale StateFlow snapshot.
     */
    fun upsertTracked(
        tmdbId: Int,
        mediaType: String,
        title: String,
        posterUrl: String?,
        year: String,
        rating: String,
        status: WatchlistStatus = WatchlistStatus.PLAN_TO_WATCH,
    ) {
        viewModelScope.launch {
            watchlistMutex.withLock {
                val existing = watchlistDao.getByIdSync(tmdbId)
                if (existing != null) {
                    applyStatusTimestamps(tmdbId, status, existing)
                } else {
                    watchlistDao.insert(
                        WatchlistEntity(
                            tmdbId = tmdbId,
                            mediaType = mediaType,
                            title = title,
                            posterUrl = posterUrl,
                            year = year,
                            rating = rating,
                            status = status.value,
                            startedAt = if (status == WatchlistStatus.WATCHING) System.currentTimeMillis() else null,
                            completedAt = if (status == WatchlistStatus.COMPLETED) System.currentTimeMillis() else null,
                        )
                    )
                }
            }
        }
    }

    /**
     * Changes the status of an already-tracked item and auto-stamps
     * startedAt / completedAt on the relevant transitions.
     */
    fun setStatus(tmdbId: Int, status: WatchlistStatus) {
        viewModelScope.launch {
            watchlistMutex.withLock {
                val existing = watchlistDao.getByIdSync(tmdbId) ?: return@withLock
                applyStatusTimestamps(tmdbId, status, existing)
            }
        }
    }

    private suspend fun applyStatusTimestamps(
        tmdbId: Int,
        newStatus: WatchlistStatus,
        existing: WatchlistEntity,
    ) {
        val now = System.currentTimeMillis()
        val startedAt = when {
            newStatus == WatchlistStatus.WATCHING && existing.startedAt == null -> now
            else -> existing.startedAt
        }
        val completedAt = when {
            newStatus == WatchlistStatus.COMPLETED && existing.completedAt == null -> now
            newStatus != WatchlistStatus.COMPLETED -> null   // un-complete clears the stamp
            else -> existing.completedAt
        }
        watchlistDao.updateStatus(tmdbId, newStatus.value, startedAt, completedAt)
    }

    /** Sets season + episode progress for a TV show. */
    fun setEpisodeProgress(tmdbId: Int, season: Int, episode: Int) {
        viewModelScope.launch {
            watchlistDao.updateProgress(tmdbId, season, episode)
        }
    }

    /** Sets the user's personal rating (1–10). Pass null to clear. */
    fun setUserRating(tmdbId: Int, rating: Int?) {
        viewModelScope.launch {
            watchlistDao.updateRating(tmdbId, rating)
        }
    }

    /** Updates the free-text note. Pass null to clear. */
    fun setNotes(tmdbId: Int, notes: String?) {
        viewModelScope.launch {
            watchlistDao.updateNotes(tmdbId, notes)
        }
    }

    /** Removes an item from tracking entirely. */
    fun removeTracked(tmdbId: Int) {
        viewModelScope.launch {
            watchlistDao.deleteById(tmdbId)
        }
    }

    // ---------------------------------------------------------------------------
    // Toggle (used by detail screen) — serialised through watchlistMutex so rapid
    // taps cannot observe a stale StateFlow and fire duplicate operations.
    // ---------------------------------------------------------------------------

    fun toggleWatchlist(
        tmdbId: Int,
        mediaType: String,
        title: String,
        posterUrl: String?,
        year: String,
        rating: String,
    ) {
        viewModelScope.launch {
            watchlistMutex.withLock {
                // Read the true current DB state, not the potentially-stale StateFlow.
                val existing = watchlistDao.getByIdSync(tmdbId)
                if (existing != null) {
                    watchlistDao.delete(existing)
                } else {
                    watchlistDao.insert(
                        WatchlistEntity(
                            tmdbId = tmdbId,
                            mediaType = mediaType,
                            title = title,
                            posterUrl = posterUrl,
                            year = year,
                            rating = rating,
                        )
                    )
                }
            }
        }
    }
}
