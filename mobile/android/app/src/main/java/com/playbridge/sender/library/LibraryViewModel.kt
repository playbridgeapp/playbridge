package com.playbridge.sender.library

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
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

data class DiscoveryFiltersState(
    val mediaType: LibraryMediaType = LibraryMediaType.ALL,
    val sort: String = "Popular",
    val yearFrom: String = "",
    val yearTo: String = "",
    val selectedGenres: Set<String> = emptySet(),
    val excludedGenres: Set<String> = emptySet(),
    val matchAllGenres: Boolean = false,
    val language: String? = null,
    val originCountry: String? = null,
    val minRating: Double = 0.0,
    val maxRating: Double = 0.0,
    val minVotes: Int = 0,
    val runtimeMin: Int = 0,
    val runtimeMax: Int = 0,
    val watchRegion: String = "US",
    val selectedProviders: Set<Int> = emptySet(),
    val selectedMonetization: Set<String> = emptySet(),
    val certification: String? = null,
    val selectedReleaseTypes: Set<Int> = emptySet(),
    val selectedTvStatuses: Set<Int> = emptySet(),
    val selectedTvTypes: Set<Int> = emptySet(),
    val selectedKeywords: List<TmdbKeyword> = emptyList(),
    val includeAdult: Boolean = false
)

class LibraryViewModel(
    application: Application,
    val tmdb: TmdbRepository = TmdbRepository(application),
    private val database: com.playbridge.sender.data.history.HistoryDatabase = DatabaseProvider.getDatabase(application),
    private val addonRepository: AddonRepository = AddonRepository(
        addonDao = database.addonDao(),
        cacheDir = application.cacheDir
    )
) : AndroidViewModel(application) {
    private val watchlistDao = database.watchlistDao()
    private val searchHistoryDao = database.searchHistoryDao()

    /**
     * Serialises all watchlist write operations so that rapid taps cannot
     * observe stale StateFlow snapshots and fire duplicate inserts/deletes.
     */
    private val watchlistMutex = Mutex()

    val watchlist: StateFlow<List<WatchlistEntity>> = watchlistDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    // Consolidated Discovery filters state
    private val _filters = MutableStateFlow(DiscoveryFiltersState())
    val filters: StateFlow<DiscoveryFiltersState> = _filters.asStateFlow()

    fun updateFilters(update: (DiscoveryFiltersState) -> DiscoveryFiltersState) {
        _filters.update { update(it) }
    }

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

    private val _watchProviders = MutableStateFlow<List<TmdbWatchProvider>>(emptyList())
    val watchProviders: StateFlow<List<TmdbWatchProvider>> = _watchProviders.asStateFlow()

    private val _keywordResults = MutableStateFlow<List<TmdbKeyword>>(emptyList())
    val keywordResults: StateFlow<List<TmdbKeyword>> = _keywordResults.asStateFlow()
    private val _isSearchingKeywords = MutableStateFlow(false)
    val isSearchingKeywords: StateFlow<Boolean> = _isSearchingKeywords.asStateFlow()
    private var keywordSearchJob: Job? = null

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
            val slots = addons
                .filter { it.isFeatureEnabled("catalog") }
                .flatMap { addon ->
                    val entries = addon.parsedCatalogEntries()
                    entries.filter { it.type.isNotBlank() && it.id.isNotBlank() }
                        .map { entry -> addon to entry }
                }

            // Stable, ordered working list initialised to loading skeletons.
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

            // Show loading skeletons only on a cold start.
            val showProgressive = cached == null
            fun emitOrdered() {
                _catalogRows.value = ordered.filterNot { !it.isLoading && it.items.isEmpty() }
            }
            if (showProgressive) emitOrdered()

            // Fetch catalogs in parallel.
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
        const val KEY_CATALOG_AUTO_REFRESH = "catalog_auto_refresh_enabled"
        const val KEY_CATALOG_REFRESH_INTERVAL_MIN = "catalog_refresh_interval_min"
        const val DEFAULT_CATALOG_REFRESH_INTERVAL_MIN = 30
        val CATALOG_REFRESH_INTERVAL_OPTIONS = listOf(15, 30, 60)

        @Volatile private var catalogCache: List<AddonCatalogRow>? = null
        @Volatile private var catalogCacheTime: Long = 0L

        fun invalidateCatalogCache() {
            catalogCache = null
            catalogCacheTime = 0L
        }
    }

    // ---------------------------------------------------------------------------
    // New-episode detection
    // ---------------------------------------------------------------------------

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
                        val userSeason = entity.seasonProgress ?: return@forEach
                        val userEp    = entity.episodeProgress ?: return@forEach

                        val details = tmdb.getTvDetails(entity.tmdbId) ?: return@forEach

                        val hasAvailable = when (val next = details.nextEpisodeToAir) {
                            null -> {
                                val lastAirMs = parseIsoDate(details.lastAirDate ?: return@forEach)
                                    ?: return@forEach
                                val referenceMs = entity.startedAt ?: entity.addedAt
                                lastAirMs > referenceMs
                            }
                            else -> when {
                                userSeason < next.seasonNumber -> true
                                userSeason == next.seasonNumber -> userEp < next.episodeNumber - 1
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
     * Discovery query (Browse tab).
     */
    fun checkConfigAndLoadInitialData() {
        _isConfigured.value = tmdb.isConfigured()
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

            runCatching {
                searchHistoryDao.insert(com.playbridge.sender.data.history.SearchHistoryEntity(query, System.currentTimeMillis()))
            }

            try {
                addonRepository.searchAllCatalogsGroupedStreaming(query) { groups ->
                    _addonSearchGroups.value = groups
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

    // ── Discovery filter setters ───────────────────────
    fun toggleGenre(name: String) {
        updateFilters { current ->
            val nextGenres = if (name in current.selectedGenres) current.selectedGenres - name else current.selectedGenres + name
            val nextExcluded = if (name in nextGenres) current.excludedGenres - name else current.excludedGenres
            current.copy(selectedGenres = nextGenres, excludedGenres = nextExcluded)
        }
    }

    fun toggleExcludedGenre(name: String) {
        updateFilters { current ->
            val nextExcluded = if (name in current.excludedGenres) current.excludedGenres - name else current.excludedGenres + name
            val nextGenres = if (name in nextExcluded) current.selectedGenres - name else current.selectedGenres
            current.copy(selectedGenres = nextGenres, excludedGenres = nextExcluded)
        }
    }

    fun setMatchAllGenres(matchAll: Boolean) { updateFilters { it.copy(matchAllGenres = matchAll) } }
    fun setMediaType(mediaType: LibraryMediaType) { updateFilters { it.copy(mediaType = mediaType) } }
    fun setSort(label: String) { updateFilters { it.copy(sort = label) } }
    fun setYearFrom(year: String) { updateFilters { it.copy(yearFrom = year) } }
    fun setYearTo(year: String) { updateFilters { it.copy(yearTo = year) } }
    fun setLanguage(code: String?) { updateFilters { it.copy(language = code) } }
    fun setOriginCountry(code: String?) { updateFilters { it.copy(originCountry = code) } }
    fun setMinRating(rating: Double) { updateFilters { it.copy(minRating = rating) } }
    fun setMaxRating(rating: Double) { updateFilters { it.copy(maxRating = rating) } }
    fun setMinVotes(votes: Int) { updateFilters { it.copy(minVotes = votes) } }
    fun setRuntimeMin(min: Int) { updateFilters { it.copy(runtimeMin = min) } }
    fun setRuntimeMax(max: Int) { updateFilters { it.copy(runtimeMax = max) } }
    fun setCertification(cert: String?) { updateFilters { it.copy(certification = cert) } }
    fun setIncludeAdult(include: Boolean) { updateFilters { it.copy(includeAdult = include) } }

    fun toggleProvider(id: Int) {
        updateFilters { current ->
            val nextProviders = if (id in current.selectedProviders) current.selectedProviders - id else current.selectedProviders + id
            current.copy(selectedProviders = nextProviders)
        }
    }
    fun toggleMonetization(type: String) {
        updateFilters { current ->
            val nextMonetization = if (type in current.selectedMonetization) current.selectedMonetization - type else current.selectedMonetization + type
            current.copy(selectedMonetization = nextMonetization)
        }
    }
    fun toggleReleaseType(value: Int) {
        updateFilters { current ->
            val nextReleaseTypes = if (value in current.selectedReleaseTypes) current.selectedReleaseTypes - value else current.selectedReleaseTypes + value
            current.copy(selectedReleaseTypes = nextReleaseTypes)
        }
    }
    fun toggleTvStatus(value: Int) {
        updateFilters { current ->
            val nextTvStatuses = if (value in current.selectedTvStatuses) current.selectedTvStatuses - value else current.selectedTvStatuses + value
            current.copy(selectedTvStatuses = nextTvStatuses)
        }
    }
    fun toggleTvType(value: Int) {
        updateFilters { current ->
            val nextTvTypes = if (value in current.selectedTvTypes) current.selectedTvTypes - value else current.selectedTvTypes + value
            current.copy(selectedTvTypes = nextTvTypes)
        }
    }

    fun setWatchRegion(region: String) {
        if (region == _filters.value.watchRegion) return
        updateFilters { current ->
            current.copy(watchRegion = region, selectedProviders = emptySet())
        }
        loadWatchProviders()
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
                .filter { kw -> _filters.value.selectedKeywords.none { it.id == kw.id } }
            _isSearchingKeywords.value = false
        }
    }

    fun addKeyword(keyword: TmdbKeyword) {
        if (_filters.value.selectedKeywords.none { it.id == keyword.id }) {
            updateFilters { current ->
                current.copy(selectedKeywords = current.selectedKeywords + keyword)
            }
            _keywordResults.value = emptyList()
        }
    }

    fun removeKeyword(id: Int) {
        updateFilters { current ->
            current.copy(selectedKeywords = current.selectedKeywords.filterNot { it.id == id })
        }
    }

    fun clearKeywordResults() {
        keywordSearchJob?.cancel()
        _keywordResults.value = emptyList()
        _isSearchingKeywords.value = false
    }

    /** Clears every Discover filter back to defaults. */
    fun clearAllFilters() {
        _filters.update { DiscoveryFiltersState() }
        _keywordResults.value = emptyList()
    }

    /** Loads the streaming-provider list for the current watch region. */
    fun loadWatchProviders() {
        viewModelScope.launch {
            val region = _filters.value.watchRegion
            val providers = runCatching { tmdb.getDiscoverWatchProviders("movie", region) }
                .getOrDefault(emptyList())
            _watchProviders.value = providers
        }
    }

    /** Builds the movie filter set from current selections. */
    private fun movieFilters(f: DiscoveryFiltersState = _filters.value): DiscoverFilters {
        val sort = TmdbDiscoverSorts.list.firstOrNull { it.label == f.sort }
        val providers = f.selectedProviders
        return DiscoverFilters(
            sortBy = sort?.movieValue ?: "popularity.desc",
            includeAdult = f.includeAdult,
            withGenres = TmdbDiscoverGenres.movieGenreParam(f.selectedGenres, f.matchAllGenres),
            withoutGenres = TmdbDiscoverGenres.movieGenreParam(f.excludedGenres, matchAll = false),
            withOriginalLanguage = f.language,
            withOriginCountry = f.originCountry,
            voteAverageGte = f.minRating.takeIf { it > 0 },
            voteAverageLte = f.maxRating.takeIf { it > 0 },
            voteCountGte = f.minVotes.takeIf { it > 0 },
            runtimeGte = f.runtimeMin.takeIf { it > 0 },
            runtimeLte = f.runtimeMax.takeIf { it > 0 },
            withKeywords = f.selectedKeywords.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
            dateGte = f.yearFrom.takeIf { it.isNotBlank() }?.let { "$it-01-01" },
            dateLte = f.yearTo.takeIf { it.isNotBlank() }?.let { "$it-12-31" },
            watchRegion = f.watchRegion.takeIf { providers.isNotEmpty() || f.selectedMonetization.isNotEmpty() },
            withWatchProviders = providers.takeIf { it.isNotEmpty() }?.joinToString("|"),
            withWatchMonetizationTypes = f.selectedMonetization.takeIf { it.isNotEmpty() }?.joinToString("|"),
            certificationCountry = f.certification?.let { TmdbMovieCertifications.COUNTRY },
            certification = f.certification,
            withReleaseType = f.selectedReleaseTypes.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("|")
        )
    }

    /** Builds the TV filter set from current selections. */
    private fun tvFilters(f: DiscoveryFiltersState = _filters.value): DiscoverFilters {
        val sort = TmdbDiscoverSorts.list.firstOrNull { it.label == f.sort }
        val providers = f.selectedProviders
        return DiscoverFilters(
            sortBy = sort?.tvValue ?: "popularity.desc",
            includeAdult = f.includeAdult,
            withGenres = TmdbDiscoverGenres.tvGenreParam(f.selectedGenres, f.matchAllGenres),
            withoutGenres = TmdbDiscoverGenres.tvGenreParam(f.excludedGenres, matchAll = false),
            withOriginalLanguage = f.language,
            withOriginCountry = f.originCountry,
            voteAverageGte = f.minRating.takeIf { it > 0 },
            voteAverageLte = f.maxRating.takeIf { it > 0 },
            voteCountGte = f.minVotes.takeIf { it > 0 },
            runtimeGte = f.runtimeMin.takeIf { it > 0 },
            runtimeLte = f.runtimeMax.takeIf { it > 0 },
            withKeywords = f.selectedKeywords.takeIf { it.isNotEmpty() }?.joinToString("|") { it.id.toString() },
            dateGte = f.yearFrom.takeIf { it.isNotBlank() }?.let { "$it-01-01" },
            dateLte = f.yearTo.takeIf { it.isNotBlank() }?.let { "$it-12-31" },
            watchRegion = f.watchRegion.takeIf { providers.isNotEmpty() || f.selectedMonetization.isNotEmpty() },
            withWatchProviders = providers.takeIf { it.isNotEmpty() }?.joinToString("|"),
            withWatchMonetizationTypes = f.selectedMonetization.takeIf { it.isNotEmpty() }?.joinToString("|"),
            withStatus = f.selectedTvStatuses.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("|"),
            withType = f.selectedTvTypes.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("|")
        )
    }

    private suspend fun discover(f: DiscoveryFiltersState) {
        _isDiscoveryLoading.value = true
        try {
            discoveredMoviesPage = 1
            discoveredTvShowsPage = 1
            _hasMoreDiscoveredMovies.value = true
            _hasMoreDiscoveredTvShows.value = true

            val mediaType = f.mediaType

            if (mediaType == LibraryMediaType.ALL || mediaType == LibraryMediaType.MOVIE) {
                _discoveredMovies.value = tmdb.discoverMovies(page = 1, filters = movieFilters(f)).results
            } else {
                _discoveredMovies.value = emptyList()
                _hasMoreDiscoveredMovies.value = false
            }

            if (mediaType == LibraryMediaType.ALL || mediaType == LibraryMediaType.TV_SHOW) {
                _discoveredTvShows.value = tmdb.discoverTvShows(page = 1, filters = tvFilters(f)).results
            } else {
                _discoveredTvShows.value = emptyList()
                _hasMoreDiscoveredTvShows.value = false
            }
        } finally {
            _isDiscoveryLoading.value = false
        }
    }

    fun loadMoreDiscoveredMovies() {
        if (_isLoadingMoreDiscoveredMovies.value || !_hasMoreDiscoveredMovies.value) return
        viewModelScope.launch {
            _isLoadingMoreDiscoveredMovies.value = true
            try {
                if (_filters.value.mediaType == LibraryMediaType.TV_SHOW) return@launch
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
                if (_filters.value.mediaType == LibraryMediaType.MOVIE) return@launch
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

        // Drive discovery reactively to eliminate request storms and races
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            filters
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { f ->
                    if (isConfigured.value) {
                        discover(f)
                    }
                }
        }
    }

    private fun observeAddonsForCatalogs() {
        viewModelScope.launch {
            var firstEmission = true
            addonRepository.observeInstalledAddons()
                .map { addons ->
                    addons.joinToString("|") { "${it.manifestUrl}#${it.catalogsJson}#${it.disabledFeatures}" }
                }
                .distinctUntilChanged()
                .collect {
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
            newStatus != WatchlistStatus.COMPLETED -> null
            else -> existing.completedAt
        }
        watchlistDao.updateStatus(tmdbId, newStatus.value, startedAt, completedAt)
    }

    fun setEpisodeProgress(tmdbId: Int, season: Int, episode: Int) {
        viewModelScope.launch {
            watchlistDao.updateProgress(tmdbId, season, episode)
        }
    }

    fun setUserRating(tmdbId: Int, rating: Int?) {
        viewModelScope.launch {
            watchlistDao.updateRating(tmdbId, rating)
        }
    }

    fun setNotes(tmdbId: Int, notes: String?) {
        viewModelScope.launch {
            watchlistDao.updateNotes(tmdbId, notes)
        }
    }

    fun removeTracked(tmdbId: Int) {
        viewModelScope.launch {
            watchlistDao.deleteById(tmdbId)
        }
    }

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

/** A single entry in the TV's current playlist, synced via playlist_status.
 *  The optional fields are the resolution context the TV echoes back (when present)
 *  so the phone can resume queueing later episodes after an app restart. */
data class PlaylistEpisode(
    val index: Int,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    val bingeGroup: String? = null
)
