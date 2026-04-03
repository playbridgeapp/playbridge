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
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.WatchlistEntity

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val tmdb = TmdbRepository(application)
    private val watchlistDao = DatabaseProvider.getDatabase(application).watchlistDao()

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

    init {
        checkConfigAndLoadInitialData()
    }

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

    fun isWatchlisted(tmdbId: Int): Flow<Boolean> {
        return watchlistDao.isWatchlisted(tmdbId)
    }

    fun toggleWatchlist(
        tmdbId: Int,
        mediaType: String,
        title: String,
        posterUrl: String?,
        year: String,
        rating: String
    ) {
        viewModelScope.launch {
            val currentWatchlist = watchlist.value
            val existing = currentWatchlist.find { it.tmdbId == tmdbId }
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
                        rating = rating
                    )
                )
            }
        }
    }
}
