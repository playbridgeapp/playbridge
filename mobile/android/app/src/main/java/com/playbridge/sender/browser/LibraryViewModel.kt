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
import com.playbridge.sender.data.library.DiscoverFilters
import com.playbridge.sender.data.library.TmdbDiscoverGenres
import com.playbridge.sender.data.library.TmdbDiscoverSorts
import com.playbridge.sender.data.library.TmdbMovieCertifications
import com.playbridge.sender.data.library.TmdbWatchProvider
import com.playbridge.sender.data.library.TmdbKeyword
import kotlinx.coroutines.Job
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.library.WatchlistStatus
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val tmdb = TmdbRepository(application)
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

    private val _selectedSort = MutableStateFlow("Popular")  // label from TmdbDiscoverSorts
    val selectedSort: StateFlow<String> = _selectedSort.asStateFlow()

    private val _selectedYearFrom = MutableStateFlow("")
    val selectedYearFrom: StateFlow<String> = _selectedYearFrom.asStateFlow()
    private val _selectedYearTo = MutableStateFlow("")
    val selectedYearTo: StateFlow<String> = _selectedYearTo.asStateFlow()

    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())  // genre names
    val selectedGenres: StateFlow<Set<String>> = _selectedGenres.asStateFlow()

    private val _excludedGenres = MutableStateFlow<Set<String>>(emptySet())  // genre names
    val excludedGenres: StateFlow<Set<String>> = _excludedGenres.asStateFlow()

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
    
    private val _selectedLanguage = MutableStateFlow<String?>(null)
    val selectedLanguage: StateFlow<String?> = _selectedLanguage.asStateFlow()

    private val _selectedOriginCountry = MutableStateFlow<String?>(null)
    val selectedOriginCountry: StateFlow<String?> = _selectedOriginCountry.asStateFlow()

    private val _selectedMinRating = MutableStateFlow(0.0)
    val selectedMinRating: StateFlow<Double> = _selectedMinRating.asStateFlow()
    private val _selectedMaxRating = MutableStateFlow(0.0)   // 0 = no max
    val selectedMaxRating: StateFlow<Double> = _selectedMaxRating.asStateFlow()

    private val _selectedMinVotes = MutableStateFlow(0)
    val selectedMinVotes: StateFlow<Int> = _selectedMinVotes.asStateFlow()

    private val _selectedRuntimeMin = MutableStateFlow(0)    // minutes, 0 = unset
    val selectedRuntimeMin: StateFlow<Int> = _selectedRuntimeMin.asStateFlow()
    private val _selectedRuntimeMax = MutableStateFlow(0)    // minutes, 0 = unset
    val selectedRuntimeMax: StateFlow<Int> = _selectedRuntimeMax.asStateFlow()

    // Watch providers (streaming)
    private val _selectedWatchRegion = MutableStateFlow("US")
    val selectedWatchRegion: StateFlow<String> = _selectedWatchRegion.asStateFlow()
    private val _selectedProviders = MutableStateFlow<Set<Int>>(emptySet())
    val selectedProviders: StateFlow<Set<Int>> = _selectedProviders.asStateFlow()
    private val _selectedMonetization = MutableStateFlow<Set<String>>(emptySet())
    val selectedMonetization: StateFlow<Set<String>> = _selectedMonetization.asStateFlow()
    private val _watchProviders = MutableStateFlow<List<TmdbWatchProvider>>(emptyList())
    val watchProviders: StateFlow<List<TmdbWatchProvider>> = _watchProviders.asStateFlow()

    // Movie-only
    private val _selectedCertification = MutableStateFlow<String?>(null)
    val selectedCertification: StateFlow<String?> = _selectedCertification.asStateFlow()
    private val _selectedReleaseTypes = MutableStateFlow<Set<Int>>(emptySet())
    val selectedReleaseTypes: StateFlow<Set<Int>> = _selectedReleaseTypes.asStateFlow()

    // TV-only
    private val _selectedTvStatuses = MutableStateFlow<Set<Int>>(emptySet())
    val selectedTvStatuses: StateFlow<Set<Int>> = _selectedTvStatuses.asStateFlow()
    private val _selectedTvTypes = MutableStateFlow<Set<Int>>(emptySet())
    val selectedTvTypes: StateFlow<Set<Int>> = _selectedTvTypes.asStateFlow()

    // Keywords (searched against TMDB, filtered via with_keywords)
    private val _selectedKeywords = MutableStateFlow<List<TmdbKeyword>>(emptyList())
    val selectedKeywords: StateFlow<List<TmdbKeyword>> = _selectedKeywords.asStateFlow()
    private val _keywordResults = MutableStateFlow<List<TmdbKeyword>>(emptyList())
    val keywordResults: StateFlow<List<TmdbKeyword>> = _keywordResults.asStateFlow()
    private val _isSearchingKeywords = MutableStateFlow(false)
    val isSearchingKeywords: StateFlow<Boolean> = _isSearchingKeywords.asStateFlow()
    private var keywordSearchJob: Job? = null

    private val _includeAdult = MutableStateFlow(false)
    val includeAdult: StateFlow<Boolean> = _includeAdult.asStateFlow()

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

            // Cache-first: hydrate the in-memory cache from disk on first use so the Home
            // screen renders instantly from cache, even on a cold start.
            if (catalogCache == null) {
                loadPersistedCatalogCache()?.let { (rows, time) ->
                    catalogCache = rows
                    catalogCacheTime = time
                    Log.d("LibraryViewModel", "loadCatalogRows: hydrated ${rows.size} rows from disk")
                }
            }

            val cached = catalogCache
            if (cached != null) _catalogRows.value = cached  // show cache immediately

            // Only hit the network when forced, or when the cache is missing/stale.
            val intervalMs = refreshIntervalMs()
            val isStale = cached == null ||
                (System.currentTimeMillis() - catalogCacheTime) >= intervalMs
            if (!forceRefresh && !isStale) {
                Log.d("LibraryViewModel", "loadCatalogRows: serving fresh cache (${cached?.size} rows)")
                return@launch
            }

            // Flatten to an ordered list of (addon, catalogEntry) pairs.
            // Order = addon order (the sortOrder shown on the Addons page) then the
            // catalog order declared within each addon's manifest.
            val slots = addons
                .filter { it.isFeatureEnabled("catalog") }
                .flatMap { addon ->
                    val entries = addon.parsedCatalogEntries()
                    entries.filter { it.type.isNotBlank() && it.id.isNotBlank() }
                        .map { entry -> addon to entry }
                }

            // Stable, ordered working list initialised to loading skeletons. Each slot keeps
            // its index so parallel fetches can fill results in place without reordering.
            val ordered = slots.map { (addon, entry) ->
                val (provider, cleanTitle) = parseCatalogTitle(addon.name, entry.name.ifBlank { entry.id })
                AddonCatalogRow(
                    catalogName = cleanTitle,
                    addonName = provider,
                    type = entry.type,
                    catalogId = entry.id,
                    addonBaseUrl = addon.baseUrl,
                    isLoading = true
                )
            }.toMutableList()

            // Show loading skeletons only on a cold start (nothing cached). When cached rows
            // are already on screen, refresh silently and swap in the result at the end.
            val showProgressive = cached == null
            fun emitOrdered() {
                _catalogRows.value = ordered.filterNot { !it.isLoading && it.items.isEmpty() }
            }
            if (showProgressive) emitOrdered()

            // Fetch catalogs in parallel; place each result at its fixed index as it lands.
            val mutex = kotlinx.coroutines.sync.Mutex()
            val deferreds = slots.mapIndexed { index, (addon, entry) ->
                async {
                    val items = runCatching {
                        addonRepository.fetchCatalog(addon, entry.type, entry.id, skip = 0)
                    }.onFailure {
                        Log.e("LibraryViewModel", "Failed to fetch catalog ${entry.name} from ${addon.name}", it)
                    }.getOrDefault(emptyList())

                    mutex.withLock {
                        ordered[index] = ordered[index].copy(items = items, isLoading = false)
                        if (showProgressive) emitOrdered()
                    }
                }
            }
            deferreds.forEach { it.await() }

            // Final swap + persist to disk for the next launch.
            val result = ordered.filterNot { it.items.isEmpty() }
            _catalogRows.value = result
            catalogCache = result
            catalogCacheTime = System.currentTimeMillis()
            persistCatalogCache(result, catalogCacheTime)
            Log.d("LibraryViewModel", "loadCatalogRows: refreshed, cached ${result.size} rows")
        }
    }

    /** Re-fetch catalogs from the network now, bypassing the per-catalog memory cache. */
    fun refreshCatalogsNow() {
        addonRepository.clearCatalogCache()
        loadCatalogRows(forceRefresh = true)
    }

    /** Wipe the persisted + in-memory catalog cache, then reload from the network. */
    fun clearCatalogCache() {
        addonRepository.clearCatalogCache()
        catalogCache = null
        catalogCacheTime = 0L
        _catalogRows.value = emptyList()
        viewModelScope.launch {
            // Delete the cache file before reloading so the reload doesn't re-hydrate stale rows.
            withContext(Dispatchers.IO) { runCatching { catalogCacheFile()?.delete() } }
            loadCatalogRows(forceRefresh = true)
        }
    }

    /** Periodically refresh catalogs in the background while enabled in settings. */
    private fun startCatalogAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(refreshIntervalMs())
                if (catalogAutoRefreshEnabled()) {
                    Log.d("LibraryViewModel", "Auto-refreshing catalog rows")
                    loadCatalogRows(forceRefresh = true)
                }
            }
        }
    }

    // ── Catalog cache persistence + settings ────────────────────────────────────

    @Serializable
    private data class PersistedCatalogCache(val timestamp: Long, val rows: List<AddonCatalogRow>)

    private val catalogCacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun catalogCacheFile(): File? =
        getApplication<Application>().cacheDir?.let { File(it, "home_catalog_cache.json") }

    private fun catalogPrefs() = getApplication<Application>()
        .getSharedPreferences("browser_settings", android.content.Context.MODE_PRIVATE)

    private fun catalogAutoRefreshEnabled(): Boolean =
        catalogPrefs().getBoolean(KEY_CATALOG_AUTO_REFRESH, true)

    private fun refreshIntervalMs(): Long =
        catalogPrefs().getInt(KEY_CATALOG_REFRESH_INTERVAL_MIN, DEFAULT_CATALOG_REFRESH_INTERVAL_MIN)
            .coerceAtLeast(1) * 60_000L

    private suspend fun loadPersistedCatalogCache(): Pair<List<AddonCatalogRow>, Long>? =
        withContext(Dispatchers.IO) {
            val file = catalogCacheFile() ?: return@withContext null
            if (!file.exists()) return@withContext null
            runCatching {
                val p = catalogCacheJson.decodeFromString<PersistedCatalogCache>(file.readText())
                // Cached rows are complete content, never loading skeletons.
                p.rows.map { it.copy(isLoading = false) } to p.timestamp
            }.getOrNull()
        }

    private fun persistCatalogCache(rows: List<AddonCatalogRow>, timestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = catalogCacheFile() ?: return@launch
                file.writeText(catalogCacheJson.encodeToString(PersistedCatalogCache(timestamp, rows)))
            }.onFailure { Log.w("LibraryViewModel", "Failed to persist catalog cache: ${it.message}") }
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
        // Catalog-cache settings (stored in the "browser_settings" SharedPreferences).
        const val KEY_CATALOG_AUTO_REFRESH = "catalog_auto_refresh_enabled"
        const val KEY_CATALOG_REFRESH_INTERVAL_MIN = "catalog_refresh_interval_min"
        const val DEFAULT_CATALOG_REFRESH_INTERVAL_MIN = 30
        val CATALOG_REFRESH_INTERVAL_OPTIONS = listOf(15, 30, 60)

        // Shared across ViewModel instances within the same process lifetime.
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
        // Only kick off the initial Discovery query when nothing has been loaded yet.
        // Re-running it on every screen entry (e.g. returning from a detail page) would
        // reset the lists to page 1, discarding lazy-loaded pages and the saved scroll
        // position. Filter changes still reset deliberately via triggerDiscovery().
        if (_isConfigured.value &&
            _discoveredMovies.value.isEmpty() &&
            _discoveredTvShows.value.isEmpty()
        ) {
            triggerDiscovery()
        }
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

    // ── Discovery filter setters (each re-runs the query) ───────────────────────
    fun toggleGenre(name: String) {
        _selectedGenres.value = _selectedGenres.value.let { if (name in it) it - name else it + name }
        // A genre can't be both included and excluded.
        if (name in _selectedGenres.value) _excludedGenres.value = _excludedGenres.value - name
        triggerDiscovery()
    }

    fun toggleExcludedGenre(name: String) {
        _excludedGenres.value = _excludedGenres.value.let { if (name in it) it - name else it + name }
        if (name in _excludedGenres.value) _selectedGenres.value = _selectedGenres.value - name
        triggerDiscovery()
    }

    fun setMatchAllGenres(matchAll: Boolean) { _matchAllGenres.value = matchAll; triggerDiscovery() }
    fun setMediaType(mediaType: LibraryMediaType) { _selectedMediaType.value = mediaType; triggerDiscovery() }
    fun setSort(label: String) { _selectedSort.value = label; triggerDiscovery() }
    fun setYearFrom(year: String) { _selectedYearFrom.value = year; triggerDiscovery() }
    fun setYearTo(year: String) { _selectedYearTo.value = year; triggerDiscovery() }
    fun setLanguage(code: String?) { _selectedLanguage.value = code; triggerDiscovery() }
    fun setOriginCountry(code: String?) { _selectedOriginCountry.value = code; triggerDiscovery() }
    fun setMinRating(rating: Double) { _selectedMinRating.value = rating; triggerDiscovery() }
    fun setMaxRating(rating: Double) { _selectedMaxRating.value = rating; triggerDiscovery() }
    fun setMinVotes(votes: Int) { _selectedMinVotes.value = votes; triggerDiscovery() }
    fun setRuntimeMin(min: Int) { _selectedRuntimeMin.value = min; triggerDiscovery() }
    fun setRuntimeMax(max: Int) { _selectedRuntimeMax.value = max; triggerDiscovery() }
    fun setCertification(cert: String?) { _selectedCertification.value = cert; triggerDiscovery() }
    fun setIncludeAdult(include: Boolean) { _includeAdult.value = include; triggerDiscovery() }

    fun toggleProvider(id: Int) {
        _selectedProviders.value = _selectedProviders.value.let { if (id in it) it - id else it + id }
        triggerDiscovery()
    }
    fun toggleMonetization(type: String) {
        _selectedMonetization.value = _selectedMonetization.value.let { if (type in it) it - type else it + type }
        triggerDiscovery()
    }
    fun toggleReleaseType(value: Int) {
        _selectedReleaseTypes.value = _selectedReleaseTypes.value.let { if (value in it) it - value else it + value }
        triggerDiscovery()
    }
    fun toggleTvStatus(value: Int) {
        _selectedTvStatuses.value = _selectedTvStatuses.value.let { if (value in it) it - value else it + value }
        triggerDiscovery()
    }
    fun toggleTvType(value: Int) {
        _selectedTvTypes.value = _selectedTvTypes.value.let { if (value in it) it - value else it + value }
        triggerDiscovery()
    }

    fun setWatchRegion(region: String) {
        if (region == _selectedWatchRegion.value) return
        _selectedWatchRegion.value = region
        _selectedProviders.value = emptySet()  // provider ids are region-specific
        loadWatchProviders()
        triggerDiscovery()
    }

    /** Debounced keyword search against TMDB for the keyword filter. */
    fun searchKeywords(query: String) {
        keywordSearchJob?.cancel()
        if (query.isBlank()) {
            _keywordResults.value = emptyList()
            _isSearchingKeywords.value = false
            return
        }
        keywordSearchJob = viewModelScope.launch {
            _isSearchingKeywords.value = true
            delay(350)
            _keywordResults.value = runCatching { tmdb.searchKeywords(query) }.getOrDefault(emptyList())
                .filter { kw -> _selectedKeywords.value.none { it.id == kw.id } }
            _isSearchingKeywords.value = false
        }
    }

    fun addKeyword(keyword: TmdbKeyword) {
        if (_selectedKeywords.value.none { it.id == keyword.id }) {
            _selectedKeywords.value = _selectedKeywords.value + keyword
            _keywordResults.value = emptyList()
            triggerDiscovery()
        }
    }

    fun removeKeyword(id: Int) {
        _selectedKeywords.value = _selectedKeywords.value.filterNot { it.id == id }
        triggerDiscovery()
    }

    fun clearKeywordResults() {
        keywordSearchJob?.cancel()
        _keywordResults.value = emptyList()
        _isSearchingKeywords.value = false
    }

    /** Clears every Discover filter back to defaults. */
    fun clearAllFilters() {
        _selectedSort.value = "Popular"
        _selectedYearFrom.value = ""
        _selectedYearTo.value = ""
        _selectedGenres.value = emptySet()
        _excludedGenres.value = emptySet()
        _matchAllGenres.value = false
        _selectedLanguage.value = null
        _selectedOriginCountry.value = null
        _selectedMinRating.value = 0.0
        _selectedMaxRating.value = 0.0
        _selectedMinVotes.value = 0
        _selectedRuntimeMin.value = 0
        _selectedRuntimeMax.value = 0
        _selectedProviders.value = emptySet()
        _selectedMonetization.value = emptySet()
        _selectedCertification.value = null
        _selectedReleaseTypes.value = emptySet()
        _selectedTvStatuses.value = emptySet()
        _selectedTvTypes.value = emptySet()
        _selectedKeywords.value = emptyList()
        _keywordResults.value = emptyList()
        _includeAdult.value = false
        triggerDiscovery()
    }

    /** Loads the streaming-provider list for the current watch region. */
    fun loadWatchProviders() {
        viewModelScope.launch {
            val region = _selectedWatchRegion.value
            // Movie list is the broadest; it covers the common providers shown as chips.
            val providers = runCatching { tmdb.getDiscoverWatchProviders("movie", region) }
                .getOrDefault(emptyList())
            _watchProviders.value = providers
        }
    }

    /** Builds the movie filter set from current selections. */
    private fun movieFilters(): DiscoverFilters {
        val sort = TmdbDiscoverSorts.list.firstOrNull { it.label == _selectedSort.value }
        val providers = _selectedProviders.value
        return DiscoverFilters(
            sortBy = sort?.movieValue ?: "popularity.desc",
            includeAdult = _includeAdult.value,
            withGenres = TmdbDiscoverGenres.movieGenreParam(_selectedGenres.value, _matchAllGenres.value),
            withoutGenres = TmdbDiscoverGenres.movieGenreParam(_excludedGenres.value, matchAll = false),
            withOriginalLanguage = _selectedLanguage.value,
            withOriginCountry = _selectedOriginCountry.value,
            voteAverageGte = _selectedMinRating.value.takeIf { it > 0 },
            voteAverageLte = _selectedMaxRating.value.takeIf { it > 0 },
            voteCountGte = _selectedMinVotes.value.takeIf { it > 0 },
            runtimeGte = _selectedRuntimeMin.value.takeIf { it > 0 },
            runtimeLte = _selectedRuntimeMax.value.takeIf { it > 0 },
            withKeywords = _selectedKeywords.value.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
            dateGte = _selectedYearFrom.value.takeIf { it.isNotBlank() }?.let { "$it-01-01" },
            dateLte = _selectedYearTo.value.takeIf { it.isNotBlank() }?.let { "$it-12-31" },
            watchRegion = _selectedWatchRegion.value.takeIf { providers.isNotEmpty() || _selectedMonetization.value.isNotEmpty() },
            withWatchProviders = providers.takeIf { it.isNotEmpty() }?.joinToString("|"),
            withWatchMonetizationTypes = _selectedMonetization.value.takeIf { it.isNotEmpty() }?.joinToString("|"),
            certificationCountry = _selectedCertification.value?.let { TmdbMovieCertifications.COUNTRY },
            certification = _selectedCertification.value,
            withReleaseType = _selectedReleaseTypes.value.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("|")
        )
    }

    /** Builds the TV filter set from current selections. */
    private fun tvFilters(): DiscoverFilters {
        val sort = TmdbDiscoverSorts.list.firstOrNull { it.label == _selectedSort.value }
        val providers = _selectedProviders.value
        return DiscoverFilters(
            sortBy = sort?.tvValue ?: "popularity.desc",
            includeAdult = _includeAdult.value,
            withGenres = TmdbDiscoverGenres.tvGenreParam(_selectedGenres.value, _matchAllGenres.value),
            withoutGenres = TmdbDiscoverGenres.tvGenreParam(_excludedGenres.value, matchAll = false),
            withOriginalLanguage = _selectedLanguage.value,
            withOriginCountry = _selectedOriginCountry.value,
            voteAverageGte = _selectedMinRating.value.takeIf { it > 0 },
            voteAverageLte = _selectedMaxRating.value.takeIf { it > 0 },
            voteCountGte = _selectedMinVotes.value.takeIf { it > 0 },
            runtimeGte = _selectedRuntimeMin.value.takeIf { it > 0 },
            runtimeLte = _selectedRuntimeMax.value.takeIf { it > 0 },
            withKeywords = _selectedKeywords.value.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
            dateGte = _selectedYearFrom.value.takeIf { it.isNotBlank() }?.let { "$it-01-01" },
            dateLte = _selectedYearTo.value.takeIf { it.isNotBlank() }?.let { "$it-12-31" },
            watchRegion = _selectedWatchRegion.value.takeIf { providers.isNotEmpty() || _selectedMonetization.value.isNotEmpty() },
            withWatchProviders = providers.takeIf { it.isNotEmpty() }?.joinToString("|"),
            withWatchMonetizationTypes = _selectedMonetization.value.takeIf { it.isNotEmpty() }?.joinToString("|"),
            withStatus = _selectedTvStatuses.value.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("|"),
            withType = _selectedTvTypes.value.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("|")
        )
    }

    private fun triggerDiscovery() {
        val mediaType = _selectedMediaType.value
        viewModelScope.launch {
            _isDiscoveryLoading.value = true
            try {
                discoveredMoviesPage = 1
                discoveredTvShowsPage = 1
                _hasMoreDiscoveredMovies.value = true
                _hasMoreDiscoveredTvShows.value = true

                if (mediaType == LibraryMediaType.ALL || mediaType == LibraryMediaType.MOVIE) {
                    _discoveredMovies.value = tmdb.discoverMovies(page = 1, filters = movieFilters()).results
                } else {
                    _discoveredMovies.value = emptyList()
                    _hasMoreDiscoveredMovies.value = false
                }

                if (mediaType == LibraryMediaType.ALL || mediaType == LibraryMediaType.TV_SHOW) {
                    _discoveredTvShows.value = tmdb.discoverTvShows(page = 1, filters = tvFilters()).results
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
                val nextPage = discoveredMoviesPage + 1
                val newMovies = tmdb.discoverMovies(page = nextPage, filters = movieFilters())
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
                val nextPage = discoveredTvShowsPage + 1
                val newTvShows = tmdb.discoverTvShows(page = nextPage, filters = tvFilters())
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
        observeAddonsForCatalogs()
        startCatalogAutoRefresh()
        loadWatchProviders()
    }

    /**
     * Keep Home catalogs in sync with the installed-addon set. The first emission drives
     * the initial (cache-first) load; later changes — addon installed/removed/refreshed/
     * reordered/enabled — force a reload so new catalogs appear immediately. This also
     * covers addons that land in the DB after this (activity-scoped) ViewModel's init ran,
     * which previously left Home stuck on the "No addons installed" empty state until a
     * manual refresh in the Addons screen.
     */
    private fun observeAddonsForCatalogs() {
        viewModelScope.launch {
            var firstEmission = true
            addonRepository.observeInstalledAddons()
                .map { addons ->
                    // Signature that changes when an addon's catalogs or enabled state change.
                    addons.joinToString("|") { "${it.manifestUrl}#${it.catalogsJson}#${it.disabledFeatures}" }
                }
                .distinctUntilChanged()
                .collect {
                    // Force a network refresh on real changes; the in-memory cache would
                    // otherwise be served back without the newly installed addon's rows.
                    loadCatalogRows(forceRefresh = !firstEmission)
                    firstEmission = false
                }
        }
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

/**
 * UI state for the playlist synced from the TV
 */
data class PlaylistUiState(
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val items: List<PlaylistEpisode> = emptyList()
)

/** A single entry in the TV's current playlist, synced via playlist_status. */
data class PlaylistEpisode(
    val index: Int,
    val title: String
)
