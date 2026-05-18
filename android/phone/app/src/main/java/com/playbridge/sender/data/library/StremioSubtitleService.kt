package com.playbridge.sender.data.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service to interact with Stremio subtitle addons.
 *
 * Always queries the default OpenSubtitles v3 addon as a baseline. If an
 * [AddonRepository] is provided, any installed addons that declare subtitle
 * support are queried in parallel and their results are merged in.
 * Duplicates are eliminated by (url, lang) pair.
 */
class StremioSubtitleService(
    private val addonRepository: AddonRepository? = null
) {

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
     * @return Merged, deduplicated list of subtitle streams from all sources.
     */
    suspend fun getSubtitlesForMovie(imdbId: String): List<StremioStream> {
        return coroutineScope {
            val defaultDeferred = async(Dispatchers.IO) {
                fetchSubtitles("$DEFAULT_ADDON_URL/movie/$imdbId.json")
            }
            val addonDeferred = async(Dispatchers.IO) {
                addonRepository?.resolveSubtitles("movie", imdbId) ?: emptyList()
            }
            mergeSubtitles(defaultDeferred.await(), addonDeferred.await())
        }
    }

    /**
     * Fetch subtitles for a TV show episode.
     * @param imdbId The IMDB ID of the TV show (e.g., tt1234567).
     * @param season The season number.
     * @param episode The episode number.
     * @return Merged, deduplicated list of subtitle streams from all sources.
     */
    suspend fun getSubtitlesForEpisode(imdbId: String, season: Int, episode: Int): List<StremioStream> {
        return coroutineScope {
            val defaultDeferred = async(Dispatchers.IO) {
                fetchSubtitles("$DEFAULT_ADDON_URL/series/$imdbId:$season:$episode.json")
            }
            val addonDeferred = async(Dispatchers.IO) {
                addonRepository?.resolveSubtitles("series", "$imdbId:$season:$episode") ?: emptyList()
            }
            mergeSubtitles(defaultDeferred.await(), addonDeferred.await())
        }
    }

    /**
     * Merge two subtitle lists, preferring addon-sourced results on duplicate (url, lang) pairs.
     * Addon results are prepended so they appear first in the UI.
     */
    private fun mergeSubtitles(
        default: List<StremioStream>,
        addon: List<StremioStream>
    ): List<StremioStream> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<StremioStream>()

        // Addon results first — they take precedence on duplicates
        for (stream in addon) {
            val key = "${stream.url.orEmpty()}|${stream.name.orEmpty()}"
            if (seen.add(key)) merged.add(stream)
        }
        for (stream in default) {
            val key = "${stream.url.orEmpty()}|${stream.name.orEmpty()}"
            if (seen.add(key)) merged.add(stream)
        }

        Log.d(TAG, "Merged subtitles: ${addon.size} from addons + ${default.size} from default = ${merged.size} total")
        return merged
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
