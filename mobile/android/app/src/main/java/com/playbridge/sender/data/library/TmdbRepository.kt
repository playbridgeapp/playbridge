package com.playbridge.sender.data.library

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * TMDB API v3 client. 
 * API key is read from SharedPreferences ("browser_settings" / "tmdb_api_key").
 */
class TmdbRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "TmdbRepository"
        private const val BASE_URL = "https://api.themoviedb.org/3"
        private const val PREFS_NAME = "browser_settings"
        private const val KEY_TMDB_API = "tmdb_api_key"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Get the user-configured TMDB API key, or null if not set.
     */
    fun getApiKey(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_TMDB_API, null)
        return if (key.isNullOrBlank()) null else key
    }

    /**
     * Check if the API key is configured.
     */
    fun isConfigured(): Boolean = getApiKey() != null

    // ==================== Movies ====================

    suspend fun discoverMovies(
        page: Int = 1,
        filters: DiscoverFilters = DiscoverFilters()
    ): TmdbPagedResponse<TmdbMovie> {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        // Cap "Newest" to today and apply a small vote floor so unranked future titles don't dominate.
        val dateLte = filters.dateLte ?: if (filters.sortBy.contains("primary_release_date")) today else null
        val voteCountGte = filters.voteCountGte ?: if (filters.sortBy.contains("primary_release_date")) 10 else null

        val query = buildQuery(
            "language" to "en-US",
            "page" to page.toString(),
            "include_adult" to filters.includeAdult.toString(),
            "include_video" to "false",
            "sort_by" to filters.sortBy,
            "with_genres" to filters.withGenres,
            "without_genres" to filters.withoutGenres,
            "with_original_language" to filters.withOriginalLanguage,
            "with_origin_country" to filters.withOriginCountry,
            "vote_average.gte" to filters.voteAverageGte?.toString(),
            "vote_average.lte" to filters.voteAverageLte?.toString(),
            "vote_count.gte" to voteCountGte?.toString(),
            "with_runtime.gte" to filters.runtimeGte?.toString(),
            "with_runtime.lte" to filters.runtimeLte?.toString(),
            "with_keywords" to filters.withKeywords,
            "without_keywords" to filters.withoutKeywords,
            "primary_release_date.gte" to filters.dateGte,
            "primary_release_date.lte" to dateLte,
            "watch_region" to filters.watchRegion,
            "with_watch_providers" to filters.withWatchProviders,
            "with_watch_monetization_types" to filters.withWatchMonetizationTypes,
            "certification_country" to filters.certificationCountry,
            "certification" to filters.certification,
            "with_release_type" to filters.withReleaseType
        )
        return fetchPaged("$BASE_URL/discover/movie?$query")
    }

    suspend fun getPopularMovies(page: Int = 1): TmdbPagedResponse<TmdbMovie> {
        return fetchPaged("$BASE_URL/movie/popular?language=en-US&page=$page")
    }

    suspend fun getMovieDetails(movieId: Int): TmdbMovieDetails? {
        return fetch("$BASE_URL/movie/$movieId?language=en-US&append_to_response=credits,release_dates,images&include_image_language=en,null")
    }

    suspend fun getMovieVideos(movieId: Int): TmdbVideoResult? {
        return fetch("$BASE_URL/movie/$movieId/videos?language=en-US")
    }

    suspend fun getMovieWatchProviders(movieId: Int, region: String = "US"): List<TmdbWatchProvider> {
        val response: TmdbWatchProvidersResponse = fetch("$BASE_URL/movie/$movieId/watch/providers")
            ?: return emptyList()
        val regionData = response.results[region] ?: return emptyList()
        return (regionData.flatrate + regionData.rent + regionData.buy)
            .distinctBy { it.providerId }
            .sortedBy { it.displayPriority }
    }



    suspend fun discoverTvShows(
        page: Int = 1,
        filters: DiscoverFilters = DiscoverFilters()
    ): TmdbPagedResponse<TmdbTvShow> {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val dateLte = filters.dateLte ?: if (filters.sortBy.contains("first_air_date")) today else null
        val voteCountGte = filters.voteCountGte ?: if (filters.sortBy.contains("first_air_date")) 5 else null

        val query = buildQuery(
            "language" to "en-US",
            "page" to page.toString(),
            "include_adult" to filters.includeAdult.toString(),
            "sort_by" to filters.sortBy,
            "with_genres" to filters.withGenres,
            "without_genres" to filters.withoutGenres,
            "with_original_language" to filters.withOriginalLanguage,
            "with_origin_country" to filters.withOriginCountry,
            "vote_average.gte" to filters.voteAverageGte?.toString(),
            "vote_average.lte" to filters.voteAverageLte?.toString(),
            "vote_count.gte" to voteCountGte?.toString(),
            "with_runtime.gte" to filters.runtimeGte?.toString(),
            "with_runtime.lte" to filters.runtimeLte?.toString(),
            "with_keywords" to filters.withKeywords,
            "without_keywords" to filters.withoutKeywords,
            "first_air_date.gte" to filters.dateGte,
            "first_air_date.lte" to dateLte,
            "watch_region" to filters.watchRegion,
            "with_watch_providers" to filters.withWatchProviders,
            "with_watch_monetization_types" to filters.withWatchMonetizationTypes,
            "with_status" to filters.withStatus,
            "with_type" to filters.withType
        )
        return fetchPaged("$BASE_URL/discover/tv?$query")
    }

    /** Available streaming providers for Discover filtering in [region] (movie or tv). */
    suspend fun getDiscoverWatchProviders(mediaType: String, region: String): List<TmdbWatchProvider> {
        val resp: TmdbWatchProviderListResponse =
            fetch("$BASE_URL/watch/providers/$mediaType?language=en-US&watch_region=$region")
                ?: return emptyList()
        return resp.results.sortedBy { it.displayPriority }
    }

    suspend fun getPopularTvShows(page: Int = 1): TmdbPagedResponse<TmdbTvShow> {
        return fetchPaged("$BASE_URL/tv/popular?language=en-US&page=$page")
    }

    suspend fun getUpcomingMovies(page: Int = 1): TmdbPagedResponse<TmdbMovie> {
        return fetchPaged("$BASE_URL/movie/upcoming?language=en-US&page=$page")
    }

    suspend fun getNowPlayingMovies(page: Int = 1): TmdbPagedResponse<TmdbMovie> {
        return fetchPaged("$BASE_URL/movie/now_playing?language=en-US&page=$page")
    }

    suspend fun getTvDetails(tvId: Int): TmdbTvDetails? {
        return fetch("$BASE_URL/tv/$tvId?language=en-US&append_to_response=external_ids,credits,content_ratings,images&include_image_language=en,null")
    }

    suspend fun getTvVideos(tvId: Int): TmdbVideoResult? {
        return fetch("$BASE_URL/tv/$tvId/videos?language=en-US")
    }

    suspend fun getTvWatchProviders(tvId: Int, region: String = "US"): List<TmdbWatchProvider> {
        val response: TmdbWatchProvidersResponse = fetch("$BASE_URL/tv/$tvId/watch/providers")
            ?: return emptyList()
        val regionData = response.results[region] ?: return emptyList()
        return (regionData.flatrate + regionData.rent + regionData.buy)
            .distinctBy { it.providerId }
            .sortedBy { it.displayPriority }
    }

    suspend fun getSeasonDetails(tvId: Int, seasonNumber: Int): TmdbSeason? {
        return fetch("$BASE_URL/tv/$tvId/season/$seasonNumber?language=en-US")
    }

    // ==================== Trending ====================

    suspend fun getTrending(page: Int = 1, timeWindow: String = "day"): TmdbPagedResponse<TmdbMultiSearchResult> {
        return fetchPaged("$BASE_URL/trending/all/$timeWindow?language=en-US&page=$page")
    }

    // ==================== Search ====================

    /** Search TMDB keywords for the Discover keyword filter. */
    suspend fun searchKeywords(query: String): List<TmdbKeyword> {
        if (query.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return fetchPaged<TmdbKeyword>("$BASE_URL/search/keyword?query=$encoded&page=1").results
    }

    suspend fun searchMulti(query: String, page: Int = 1): TmdbPagedResponse<TmdbMultiSearchResult> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return fetchPaged("$BASE_URL/search/multi?query=$encoded&language=en-US&page=$page&include_adult=false")
    }

    // ==================== Internal ====================

    private suspend inline fun <reified T> fetch(url: String): T? {
        val apiKey = getApiKey() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = appendApiKey(url, apiKey)
                val request = Request.Builder().url(fullUrl).get().build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    json.decodeFromString<T>(body)
                } else {
                    Log.e(TAG, "TMDB request failed: ${response.code} for $url")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "TMDB request error", e)
                null
            }
        }
    }

    private suspend inline fun <reified T> fetchPaged(url: String): TmdbPagedResponse<T> {
        val apiKey = getApiKey() ?: return TmdbPagedResponse()
        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = appendApiKey(url, apiKey)
                val request = Request.Builder().url(fullUrl).get().build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext TmdbPagedResponse()
                    json.decodeFromString<TmdbPagedResponse<T>>(body)
                } else {
                    Log.e(TAG, "TMDB paged request failed: ${response.code} for $url")
                    TmdbPagedResponse()
                }
            } catch (e: Exception) {
                Log.e(TAG, "TMDB paged request error", e)
                TmdbPagedResponse()
            }
        }
    }

    /**
     * Look up a movie or TV show by its IMDb ID (e.g. "tt1234567").
     * Used to route addon catalog items (which carry IMDb IDs) to existing TMDB detail screens.
     * Returns null if the API key is not configured or the request fails.
     */
    suspend fun findByImdbId(imdbId: String): TmdbFindResponse? {
        return fetch("$BASE_URL/find/$imdbId?external_source=imdb_id&language=en-US")
    }

    private fun appendApiKey(url: String, apiKey: String): String {
        val separator = if ("?" in url) "&" else "?"
        return "${url}${separator}api_key=$apiKey"
    }

    /** Builds a query string from key→value pairs, skipping null/blank values. */
    private fun buildQuery(vararg params: Pair<String, String?>): String =
        params.filter { !it.second.isNullOrBlank() }
            .joinToString("&") { "${it.first}=${it.second}" }
}
