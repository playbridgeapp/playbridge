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

    suspend fun discoverMovies(page: Int = 1, withGenres: String? = null): TmdbPagedResponse<TmdbMovie> {
        val genresParam = withGenres?.let { "&with_genres=$it" } ?: ""
        return fetchPaged("$BASE_URL/discover/movie?language=en-US&page=$page$genresParam")
    }

    suspend fun getPopularMovies(page: Int = 1): TmdbPagedResponse<TmdbMovie> {
        return fetchPaged("$BASE_URL/movie/popular?language=en-US&page=$page")
    }

    suspend fun getMovieDetails(movieId: Int): TmdbMovieDetails? {
        return fetch("$BASE_URL/movie/$movieId?language=en-US")
    }

    // ==================== TV Shows ====================

    suspend fun discoverTvShows(page: Int = 1, withGenres: String? = null): TmdbPagedResponse<TmdbTvShow> {
        val genresParam = withGenres?.let { "&with_genres=$it" } ?: ""
        return fetchPaged("$BASE_URL/discover/tv?language=en-US&page=$page$genresParam")
    }

    suspend fun getPopularTvShows(page: Int = 1): TmdbPagedResponse<TmdbTvShow> {
        return fetchPaged("$BASE_URL/tv/popular?language=en-US&page=$page")
    }

    suspend fun getTvDetails(tvId: Int): TmdbTvDetails? {
        return fetch("$BASE_URL/tv/$tvId?language=en-US&append_to_response=external_ids")
    }

    suspend fun getSeasonDetails(tvId: Int, seasonNumber: Int): TmdbSeason? {
        return fetch("$BASE_URL/tv/$tvId/season/$seasonNumber?language=en-US")
    }

    // ==================== Trending ====================

    suspend fun getTrending(page: Int = 1): TmdbPagedResponse<TmdbMultiSearchResult> {
        return fetchPaged("$BASE_URL/trending/all/day?language=en-US&page=$page")
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
