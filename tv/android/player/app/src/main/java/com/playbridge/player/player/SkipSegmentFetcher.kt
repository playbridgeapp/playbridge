package com.playbridge.player.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object SkipSegmentFetcher {
    private const val TAG = "SkipSegmentFetcher"
    private val client = OkHttpClient()

    suspend fun fetchSegments(
        context: Context,
        imdbId: String,
        season: Int,
        episode: Int
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("introdb_api_url", "https://api.introdb.app")?.trim()?.removeSuffix("/") ?: "https://api.introdb.app"
        val apiKey = prefs.getString("introdb_api_key", "") ?: ""

        val url = "$baseUrl/segments?imdb_id=$imdbId&season=$season&episode=$episode"
        Log.i(TAG, "Fetching skip segments from: $url")

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "PlayBridgeTV/1.0")

        if (apiKey.isNotBlank()) {
            if (apiKey.startsWith("ey")) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            } else {
                requestBuilder.header("x-api-key", apiKey)
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch skip segments: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val bodyText = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyText)
                val segments = mutableListOf<SkipSegment>()

                val types = listOf("intro", "recap", "outro")
                for (type in types) {
                    if (json.has(type) && !json.isNull(type)) {
                        val segObj = json.getJSONObject(type)
                        val startMs = segObj.optLong("start_ms", -1L).takeIf { it != -1L }
                            ?: (segObj.optDouble("start_sec", -1.0).takeIf { it != -1.0 }?.let { (it * 1000).toLong() })
                        val endMs = segObj.optLong("end_ms", -1L).takeIf { it != -1L }
                            ?: (segObj.optDouble("end_sec", -1.0).takeIf { it != -1.0 }?.let { (it * 1000).toLong() })

                        if (startMs != null && endMs != null) {
                            segments.add(SkipSegment(type, startMs, endMs))
                        }
                    }
                }
                Log.i(TAG, "Successfully fetched ${segments.size} segments: $segments")
                segments
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching skip segments", e)
            emptyList()
        }
    }
}
