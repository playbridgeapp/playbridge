package com.playbridge.sender.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.library.TmdbMovie
import com.playbridge.sender.data.library.TmdbMultiSearchResult
import com.playbridge.sender.data.library.TmdbRepository
import com.playbridge.sender.data.library.TmdbTvShow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.library.WatchlistStatus
import coil.Coil
import coil.request.ImageRequest

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdb = TmdbRepository(application)
    private val watchlistDao = DatabaseProvider.getDatabase(application).watchlistDao()

    /**
     * Serialises all watchlist write operations so that rapid taps cannot
     * observe stale StateFlow snapshots and fire duplicate inserts/deletes.
     */
    private val watchlistMutex = Mutex()

    val watchlist: StateFlow<List<WatchlistEntity>> = watchlistDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Scroll States
    val mainListState = LazyListState()
    val trendingDayListState = LazyListState()
    val popularMoviesListState = LazyListState()
    val popularTvShowsListState = LazyListState()
    val newReleasesListState = LazyListState()
    val discoveredMoviesListState = LazyListState()
    val discoveredTvShowsListState = LazyListState()
    val discoverGridState = LazyGridState()
    val searchResultsListState = LazyListState()

    private val _isConfigured = MutableStateFlow(tmdb.isConfigured())
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Popular Movies
    private val _popularMovies = MutableStateFlow<List<TmdbMovie>>(emptyList())
    val popularMovies: StateFlow<List<TmdbMovie>> = _popularMovies.asStateFlow()
    private var popularMoviesPage = 1
    private val _isLoadingMorePopularMovies = MutableStateFlow(false)
    val isLoadingMorePopularMovies: StateFlow<Boolean> = _isLoadingMorePopularMovies.asStateFlow()
    private val _hasMorePopularMovies = MutableStateFlow(true)
    val hasMorePopularMovies: StateFlow<Boolean> = _hasMorePopularMovies.asStateFlow()

    // Popular TV Shows
    private val _popularTvShows = MutableStateFlow<List<TmdbTvShow>>(emptyList())
    val popularTvShows: StateFlow<List<TmdbTvShow>> = _popularTvShows.asStateFlow()
    private var popularTvShowsPage = 1
    private val _isLoadingMorePopularTvShows = MutableStateFlow(false)
    val isLoadingMorePopularTvShows: StateFlow<Boolean> = _isLoadingMorePopularTvShows.asStateFlow()
    private val _hasMorePopularTvShows = MutableStateFlow(true)
    val hasMorePopularTvShows: StateFlow<Boolean> = _hasMorePopularTvShows.asStateFlow()

    // Trending Week (Carousel)
    private val _trendingWeek = MutableStateFlow<List<TmdbMultiSearchResult>>(emptyList())
    val trendingWeek: StateFlow<List<TmdbMultiSearchResult>> = _trendingWeek.asStateFlow()
    private var trendingWeekPage = 1

    // Trending Day
    private val _trendingDay = MutableStateFlow<List<TmdbMultiSearchResult>>(emptyList())
    val trendingDay: StateFlow<List<TmdbMultiSearchResult>> = _trendingDay.asStateFlow()
    private var trendingDayPage = 1
    private val _isLoadingMoreTrendingDay = MutableStateFlow(false)
    val isLoadingMoreTrendingDay: StateFlow<Boolean> = _isLoadingMoreTrendingDay.asStateFlow()
    private val _hasMoreTrendingDay = MutableStateFlow(true)
    val hasMoreTrendingDay: StateFlow<Boolean> = _hasMoreTrendingDay.asStateFlow()

    // New & Upcoming (Merged)
    private val _newReleases = MutableStateFlow<List<TmdbMovie>>(emptyList())
    val newReleases: StateFlow<List<TmdbMovie>> = _newReleases.asStateFlow()
    private val _nowPlayingMovieIds = MutableStateFlow<Set<Int>>(emptySet())
    val nowPlayingMovieIds: StateFlow<Set<Int>> = _nowPlayingMovieIds.asStateFlow()

    private var nowPlayingMoviesPage = 1
    private var upcomingMoviesPage = 1
    private val _isLoadingMoreNewReleases = MutableStateFlow(false)
    val isLoadingMoreNewReleases: StateFlow<Boolean> = _isLoadingMoreNewReleases.asStateFlow()
    private val _hasMoreNewReleases = MutableStateFlow(true)
    val hasMoreNewReleases: StateFlow<Boolean> = _hasMoreNewReleases.asStateFlow()

    // Internal raw lists used for the merge
    private val _nowPlayingRaw = MutableStateFlow<List<TmdbMovie>>(emptyList())
    private val _upcomingRaw = MutableStateFlow<List<TmdbMovie>>(emptyList())

    // Search state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<TmdbMultiSearchResult>>(emptyList())
    val searchResults: StateFlow<List<TmdbMultiSearchResult>> = _searchResults.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

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
                val fourteenDaysAgo = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
                tvItems.forEach { entity ->
                    try {
                        val details = tmdb.getTvDetails(entity.tmdbId) ?: return@forEach
                        val lastAirMs = parseIsoDate(details.lastAirDate ?: return@forEach)
                            ?: return@forEach
                        // "New" = aired within the last 14 days AND after the user added the show
                        val referenceMs = entity.startedAt ?: entity.addedAt
                        if (lastAirMs > referenceMs && lastAirMs > fourteenDaysAgo) {
                            ids.add(entity.tmdbId)
                        }
                    } catch (_: Exception) { }
                }
                _newEpisodeTmdbIds.value = ids
            }
        }
    }

    private fun parseIsoDate(dateStr: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dateStr)?.time
    } catch (_: Exception) { null }

    fun checkConfigAndLoadInitialData() {
        val configured = tmdb.isConfigured()
        _isConfigured.value = configured

        if (configured && _popularMovies.value.isEmpty() && _trendingDay.value.isEmpty()) {
            loadInitialData()
        } else if (!configured) {
            _isLoading.value = false
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val movies = tmdb.getPopularMovies(page = 1)
                val tvShows = tmdb.getPopularTvShows(page = 1)
                val trendDay = tmdb.getTrending(page = 1, timeWindow = "day")
                val trendWeek = tmdb.getTrending(page = 1, timeWindow = "week")
                val upcoming = tmdb.getUpcomingMovies(page = 1)
                val nowPlaying = tmdb.getNowPlayingMovies(page = 1)

                _popularMovies.value = movies.results
                popularMoviesPage = 1

                _popularTvShows.value = tvShows.results
                popularTvShowsPage = 1

                _trendingDay.value = trendDay.results.filter { it.isMovie || it.isTvShow }
                trendingDayPage = 1

                _trendingWeek.value = trendWeek.results.filter { it.isMovie || it.isTvShow }
                trendingWeekPage = 1

                _nowPlayingRaw.value = nowPlaying.results
                _upcomingRaw.value = upcoming.results
                _nowPlayingMovieIds.value = nowPlaying.results.map { it.id }.toSet()
                _newReleases.value = (nowPlaying.results + upcoming.results).distinctBy { it.id }

                nowPlayingMoviesPage = 1
                upcomingMoviesPage = 1
                _hasMoreNewReleases.value = nowPlaying.results.isNotEmpty() || upcoming.results.isNotEmpty()

                // Prefetch the first page of poster images so they're in cache before
                // the user scrolls — trending week first (carousel), then popular.
                val posterUrls = buildList {
                    addAll(trendWeek.results.mapNotNull { it.posterUrl })
                    addAll(movies.results.mapNotNull { it.posterUrl })
                    addAll(tvShows.results.mapNotNull { it.posterUrl })
                    addAll(trendDay.results.mapNotNull { it.posterUrl })
                }.distinct()
                prefetchPosters(posterUrls)
            } catch (e: Exception) {
                // Handle error if needed
            } finally {
                _isLoading.value = false
                triggerDiscovery()
            }
        }
    }

    fun loadMorePopularMovies() {
        if (_isLoadingMorePopularMovies.value || !_hasMorePopularMovies.value) return

        viewModelScope.launch {
            _isLoadingMorePopularMovies.value = true
            try {
                val nextPage = popularMoviesPage + 1
                val newMovies = tmdb.getPopularMovies(page = nextPage)
                if (newMovies.results.isNotEmpty()) {
                    _popularMovies.value = _popularMovies.value + newMovies.results
                    popularMoviesPage = nextPage
                } else {
                    _hasMorePopularMovies.value = false
                }
            } finally {
                _isLoadingMorePopularMovies.value = false
            }
        }
    }

    fun loadMorePopularTvShows() {
        if (_isLoadingMorePopularTvShows.value || !_hasMorePopularTvShows.value) return

        viewModelScope.launch {
            _isLoadingMorePopularTvShows.value = true
            try {
                val nextPage = popularTvShowsPage + 1
                val newTvShows = tmdb.getPopularTvShows(page = nextPage)
                if (newTvShows.results.isNotEmpty()) {
                    _popularTvShows.value = _popularTvShows.value + newTvShows.results
                    popularTvShowsPage = nextPage
                } else {
                    _hasMorePopularTvShows.value = false
                }
            } finally {
                _isLoadingMorePopularTvShows.value = false
            }
        }
    }

    fun loadMoreTrendingDay() {
        if (_isLoadingMoreTrendingDay.value || !_hasMoreTrendingDay.value) return

        viewModelScope.launch {
            _isLoadingMoreTrendingDay.value = true
            try {
                val nextPage = trendingDayPage + 1
                val newTrending = tmdb.getTrending(page = nextPage, timeWindow = "day")
                if (newTrending.results.isNotEmpty()) {
                    _trendingDay.value = _trendingDay.value + newTrending.results.filter { it.isMovie || it.isTvShow }
                    trendingDayPage = nextPage
                } else {
                    _hasMoreTrendingDay.value = false
                }
            } finally {
                _isLoadingMoreTrendingDay.value = false
            }
        }
    }

    fun loadMoreNewReleases() {
        if (_isLoadingMoreNewReleases.value || !_hasMoreNewReleases.value) return

        viewModelScope.launch {
            _isLoadingMoreNewReleases.value = true
            try {
                // To keep it simple, we fetch both next pages and re-merge
                val nextNowPlayingPage = nowPlayingMoviesPage + 1
                val nextUpcomingPage = upcomingMoviesPage + 1
                
                val newNowPlaying = tmdb.getNowPlayingMovies(page = nextNowPlayingPage)
                val newUpcoming = tmdb.getUpcomingMovies(page = nextUpcomingPage)
                
                if (newNowPlaying.results.isNotEmpty() || newUpcoming.results.isNotEmpty()) {
                    _nowPlayingRaw.value = _nowPlayingRaw.value + newNowPlaying.results
                    _upcomingRaw.value = _upcomingRaw.value + newUpcoming.results
                    
                    // Update the label set
                    _nowPlayingMovieIds.value = _nowPlayingMovieIds.value + newNowPlaying.results.map { it.id }
                    
                    // Re-merge
                    _newReleases.value = (_nowPlayingRaw.value + _upcomingRaw.value).distinctBy { it.id }
                    
                    nowPlayingMoviesPage = nextNowPlayingPage
                    upcomingMoviesPage = nextUpcomingPage
                } else {
                    _hasMoreNewReleases.value = false
                }
            } finally {
                _isLoadingMoreNewReleases.value = false
            }
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
            _searchResults.value = emptyList()
        }
    }

    fun performSearch() {
        val query = _searchQuery.value
        if (query.isBlank()) return

        viewModelScope.launch {
            _isSearchLoading.value = true
            try {
                val results = tmdb.searchMulti(query)
                _searchResults.value = results.results.filter { it.isMovie || it.isTvShow }
            } finally {
                _isSearchLoading.value = false
            }
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

    /**
     * Fires-and-forgets Coil enqueue requests for a list of poster URLs so they land
     * in the disk/memory cache before the user scrolls to them.
     * Takes the first 60 distinct URLs to avoid hammering the network.
     */
    private fun prefetchPosters(urls: List<String>) {
        val context = getApplication<android.app.Application>()
        val imageLoader = Coil.imageLoader(context)
        urls.take(60).forEach { url ->
            imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).build()
            )
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
