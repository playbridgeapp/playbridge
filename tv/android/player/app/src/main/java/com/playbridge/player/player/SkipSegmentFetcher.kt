package com.playbridge.player.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetches skip segments (intro/recap/outro/preview) from up to two providers:
 *
 *  - **IntroDB** (https://introdb.app) — the original integration. IMDb-keyed,
 *    episodes only, optional API key. Response: `{ "intro": {start_ms, end_ms}, … }`.
 *  - **TheIntroDB** (https://theintrodb.org) — keyless, community-verified. Works with
 *    TMDB/IMDb ids and covers movies too. `GET /v3/media?tmdb_id=…[&season=…&episode=…]`
 *    → `{ "type": "tv", "recap": [{start_ms, end_ms|null}], "credits": […], … }` —
 *    each segment type is an ARRAY, and `end_ms` may be null (runs to the end of file).
 *
 * The `skip_segments_provider` pref picks the source: "introdb", "theintrodb", or
 * "both" (default — IntroDB first, TheIntroDB fills in any segment types it didn't
 * return, plus everything IntroDB can't cover, like movies).
 */
object SkipSegmentFetcher {
    private const val TAG = "SkipSegmentFetcher"
    private val client = OkHttpClient()

    private const val DEFAULT_INTRODB_URL = "https://api.introdb.app"
    private const val DEFAULT_THEINTRODB_URL = "https://api.theintrodb.org"

    /**
     * Sentinel end for open-ended segments (TheIntroDB reports `end_ms: null` for
     * credits that run to the end of the file). Far past any real duration so the
     * containment check keeps the segment active; engines clamp seeks to the duration,
     * so skipping such a segment jumps to the end of playback.
     */
    const val OPEN_ENDED_MS = Long.MAX_VALUE / 2

    suspend fun fetchSegments(
        context: Context,
        imdbId: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?,
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val provider = prefs.getString("skip_segments_provider", "both") ?: "both"

        val fromIntroDb =
            if (provider != "theintrodb" && !imdbId.isNullOrBlank() && season != null && episode != null) {
                fetchIntroDb(prefs, imdbId, season, episode)
            } else {
                emptyList()
            }

        val fromTheIntroDb =
            if (provider != "introdb" && (!imdbId.isNullOrBlank() || !tmdbId.isNullOrBlank())) {
                // In "both" mode IntroDB wins per segment type; TheIntroDB fills the gaps.
                val covered = fromIntroDb.map { it.type }.toSet()
                fetchTheIntroDb(prefs, imdbId, tmdbId, season, episode)
                    .filter { it.type !in covered }
            } else {
                emptyList()
            }

        (fromIntroDb + fromTheIntroDb).sortedBy { it.startMs }
    }

    // ── IntroDB (introdb.app) ───────────────────────────────────────────────

    private fun fetchIntroDb(
        prefs: android.content.SharedPreferences,
        imdbId: String,
        season: Int,
        episode: Int,
    ): List<SkipSegment> {
        val baseUrl = prefs.getString("introdb_api_url", DEFAULT_INTRODB_URL)
            ?.trim()?.removeSuffix("/") ?: DEFAULT_INTRODB_URL
        val apiKey = prefs.getString("introdb_api_key", "") ?: ""

        val url = "$baseUrl/segments?imdb_id=$imdbId&season=$season&episode=$episode"
        Log.i(TAG, "Fetching skip segments from IntroDB: $url")

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

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "IntroDB fetch failed: HTTP ${response.code}")
                    return emptyList()
                }
                val bodyText = response.body?.string() ?: return emptyList()
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
                Log.i(TAG, "IntroDB returned ${segments.size} segments: $segments")
                segments
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from IntroDB", e)
            emptyList()
        }
    }

    // ── TheIntroDB (theintrodb.org) ─────────────────────────────────────────

    private fun fetchTheIntroDb(
        prefs: android.content.SharedPreferences,
        imdbId: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?,
    ): List<SkipSegment> {
        val baseUrl = prefs.getString("theintrodb_api_url", DEFAULT_THEINTRODB_URL)
            ?.trim()?.removeSuffix("/") ?: DEFAULT_THEINTRODB_URL

        val idQuery = when {
            !tmdbId.isNullOrBlank() -> "tmdb_id=$tmdbId"
            !imdbId.isNullOrBlank() -> "imdb_id=$imdbId"
            else -> return emptyList()
        }
        // TV needs season+episode; without them the same endpoint is a movie lookup.
        val episodeQuery =
            if (season != null && episode != null) "&season=$season&episode=$episode" else ""
        val url = "$baseUrl/v3/media?$idQuery$episodeQuery"
        Log.i(TAG, "Fetching skip segments from TheIntroDB: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PlayBridgeTV/1.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "TheIntroDB fetch failed: HTTP ${response.code}")
                    return emptyList()
                }
                val bodyText = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyText)
                val segments = mutableListOf<SkipSegment>()

                // TheIntroDB types → the app's segment types ("credits" feeds the same
                // UI/auto-skip pref as "outro"). Each value is an array of ranges.
                val typeMap = listOf(
                    "intro" to "intro",
                    "recap" to "recap",
                    "outro" to "outro",
                    "credits" to "outro",
                    "preview" to "preview",
                )
                val seenTypes = mutableSetOf<String>()
                for ((apiType, uiType) in typeMap) {
                    if (!json.has(apiType) || json.isNull(apiType)) continue
                    if (!seenTypes.add(uiType)) continue // e.g. both "outro" and "credits"
                    val arr = json.optJSONArray(apiType) ?: continue
                    for (i in 0 until arr.length()) {
                        val segObj = arr.optJSONObject(i) ?: continue
                        val startMs = segObj.optLong("start_ms", -1L)
                        if (startMs < 0) continue
                        val endMs = if (segObj.isNull("end_ms")) {
                            OPEN_ENDED_MS // runs to the end of the file
                        } else {
                            segObj.optLong("end_ms", -1L).takeIf { it > startMs } ?: continue
                        }
                        segments.add(SkipSegment(uiType, startMs, endMs))
                    }
                }
                Log.i(TAG, "TheIntroDB returned ${segments.size} segments: $segments")
                segments
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from TheIntroDB", e)
            emptyList()
        }
    }
}
