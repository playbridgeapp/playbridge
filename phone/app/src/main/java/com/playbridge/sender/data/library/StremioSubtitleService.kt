package com.playbridge.sender.data.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service to interact with Stremio subtitle addons (like OpenSubtitles v3).
 */
class StremioSubtitleService {

    companion object {
        private const val TAG = "StremioSubtitleService"
        private const val DEFAULT_ADDON_URL = "https://opensubtitles-v3.strem.io/subtitles"
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
     * Fetch subtitles for a movie.
     * @param imdbId The IMDB ID of the movie (e.g., tt1234567).
     * @return List of subtitle streams.
     */
    suspend fun getSubtitlesForMovie(imdbId: String): List<StremioStream> {
        val url = "$DEFAULT_ADDON_URL/movie/$imdbId.json"
        return fetchSubtitles(url)
    }

    /**
     * Fetch subtitles for a TV show episode.
     * @param imdbId The IMDB ID of the TV show (e.g., tt1234567).
     * @param season The season number.
     * @param episode The episode number.
     * @return List of subtitle streams.
     */
    suspend fun getSubtitlesForEpisode(imdbId: String, season: Int, episode: Int): List<StremioStream> {
        val url = "$DEFAULT_ADDON_URL/series/$imdbId:$season:$episode.json"
        return fetchSubtitles(url)
    }

    private suspend fun fetchSubtitles(url: String): List<StremioStream> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val subtitleResponse = json.decodeFromString<StremioStreamResponse>(body)
                    subtitleResponse.subtitles ?: subtitleResponse.streams ?: emptyList()
                } else {
                    Log.e(TAG, "Subtitle request failed: ${response.code} for $url")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subtitle request error", e)
                emptyList()
            }
        }
    }
}
