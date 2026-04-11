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
class TmdbRepository(private val context: Context) {

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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

    suspend fun discoverMovies(page: Int = 1, withGenres: String? = null, sortBy: String = "popularity.desc", year: String? = null): TmdbPagedResponse<TmdbMovie> {
        val genresParam = withGenres?.let { "&with_genres=$it" } ?: ""
        val yearParam = year?.let { "&primary_release_year=$it" } ?: ""
        
        // Stremio-style curation: Capping to today and adding relevance thresholds for newest
        val relevanceParams = if (sortBy.contains("primary_release_date")) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            "&primary_release_date.lte=$today&vote_count.gte=10"
        } else ""

        return fetchPaged("$BASE_URL/discover/movie?language=en-US&page=$page&sort_by=$sortBy$genresParam$yearParam$relevanceParams&include_video=false&include_adult=false&with_runtime.gte=60")
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



    suspend fun discoverTvShows(page: Int = 1, withGenres: String? = null, sortBy: String = "popularity.desc", year: String? = null): TmdbPagedResponse<TmdbTvShow> {
        val genresParam = withGenres?.let { "&with_genres=$it" } ?: ""
        val yearParam = year?.let { "&first_air_date_year=$it" } ?: ""
        val tvSortBy = if (sortBy == "primary_release_date.desc") "first_air_date.desc" else sortBy
        
        // Stremio-style curation: Capping to today and adding relevance thresholds
        val relevanceParams = if (tvSortBy.contains("first_air_date")) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            "&first_air_date.lte=$today&vote_count.gte=5"
        } else ""

        return fetchPaged("$BASE_URL/discover/tv?language=en-US&page=$page&sort_by=$tvSortBy$genresParam$yearParam$relevanceParams&include_video=false&include_adult=false")
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

    private fun appendApiKey(url: String, apiKey: String): String {
        val separator = if ("?" in url) "&" else "?"
        return "${url}${separator}api_key=$apiKey"
    }
}
