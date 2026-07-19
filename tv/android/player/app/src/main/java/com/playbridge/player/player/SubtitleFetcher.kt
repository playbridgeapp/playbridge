package com.playbridge.player.player

import android.util.Log
import com.playbridge.shared.logging.redactUrlForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object SubtitleFetcher {
    private const val TAG = "SubtitleFetcher"
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @kotlinx.serialization.Serializable
    data class StremioSubtitle(
        val id: String = "",
        val url: String = "",
        val SubEncoding: String? = null,
        val lang: String = ""
    )

    @kotlinx.serialization.Serializable
    data class StremioSubtitleResponse(
        val subtitles: List<StremioSubtitle> = emptyList()
    )

    suspend fun fetchSubtitles(imdbId: String, season: Int?, episode: Int?): List<StremioSubtitle> = withContext(Dispatchers.IO) {
        val url = if (season != null && episode != null) {
            "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episode.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
        }

        Log.i(TAG, "Fetching subtitles from: ${redactUrlForLog(url)}")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch subtitles: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyText = response.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<StremioSubtitleResponse>(bodyText)
                parsed.subtitles
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching subtitles from stremio proxy", e)
            emptyList()
        }
    }
}
