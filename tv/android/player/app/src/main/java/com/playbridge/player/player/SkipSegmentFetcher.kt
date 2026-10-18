package com.playbridge.player.player

import android.content.Context
import android.util.Log
import com.playbridge.player.logging.DebugNetworkLogger
import com.playbridge.shared.logging.redactUrlForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetches skip segments (intro/recap/outro/preview) from up to two providers:
 *
 *  - **IntroDB** (https://introdb.app) — the original integration. IMDb-keyed,
 *    episodes only, optional API key. Response: `{ "intro": {start_ms, end_ms}, … }`.
 *  - **TheIntroDB** (https://theintrodb.org) — keyless, community-verified. Works with
 *    TMDB/IMDb ids and covers movies too. `GET /v4/media?tmdb_id=…[&season=…&episode=…]`
 *    → `{ "tvdb_id": …, "type": "tv", "recap": [{start_ms, end_ms|null}], "credits": […], … }` —
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
        Log.i(TAG, "Fetching skip segments from IntroDB: ${redactUrlForLog(url)}")

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

        val request = requestBuilder.build()
        DebugNetworkLogger.request(TAG, "IntroDB", request)
        return try {
            client.newCall(request).execute().use { response ->
                DebugNetworkLogger.response(TAG, "IntroDB", response)
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

    internal fun theIntroDbMediaUrl(
        baseUrl: String,
        imdbId: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?,
    ): HttpUrl? {
        if ((season == null) != (episode == null)) return null
        val id = when {
            !tmdbId.isNullOrBlank() -> "tmdb_id" to tmdbId.trim()
            !imdbId.isNullOrBlank() -> "imdb_id" to imdbId.trim()
            else -> return null
        }
        val base = baseUrl.trim().ifEmpty { DEFAULT_THEINTRODB_URL }.toHttpUrlOrNull() ?: return null
        // Saved URLs may include a previous API version; keep custom proxy paths/query values.
        val path = base.encodedPath.trimEnd('/').replace(Regex("/v[1-4]$"), "")
        return base.newBuilder().encodedPath("$path/v4/media").fragment(null).apply {
            for (key in listOf("tvdb_id", "tmdb_id", "imdb_id", "season", "episode")) {
                removeAllQueryParameters(key)
            }
            addQueryParameter(id.first, id.second)
            // v4 resolves TMDB episode coordinates server-side; don't guess a mapping order.
            if (season != null && episode != null) {
                addQueryParameter("season", season.toString())
                addQueryParameter("episode", episode.toString())
            }
        }.build()
    }

    private fun fetchTheIntroDb(
        prefs: android.content.SharedPreferences,
        imdbId: String?,
        tmdbId: String?,
        season: Int?,
        episode: Int?,
    ): List<SkipSegment> {
        val url = theIntroDbMediaUrl(
            prefs.getString("theintrodb_api_url", DEFAULT_THEINTRODB_URL) ?: DEFAULT_THEINTRODB_URL,
            imdbId, tmdbId, season, episode,
        ) ?: return emptyList()
        Log.i(TAG, "Fetching skip segments from TheIntroDB: ${redactUrlForLog(url.toString())}")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PlayBridgeTV/1.0")
            .build()
        DebugNetworkLogger.request(TAG, "TheIntroDB", request)

        return try {
            client.newCall(request).execute().use { response ->
                DebugNetworkLogger.response(TAG, "TheIntroDB", response)
                if (!response.isSuccessful) {
                    Log.w(TAG, "TheIntroDB fetch failed: HTTP ${response.code}")
                    return emptyList()
                }
                val bodyText = response.body?.string() ?: return emptyList()
                val segments = parseTheIntroDbSegments(JSONObject(bodyText))
                Log.i(TAG, "TheIntroDB returned ${segments.size} segments: $segments")
                segments
            }
        } catch (e: Exception) {
            // Exception messages can contain a custom URL or the rejected JSON body.
            Log.e(TAG, "Error fetching from TheIntroDB (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    internal fun parseTheIntroDbSegments(json: JSONObject): List<SkipSegment> {
        val segments = mutableListOf<SkipSegment>()
        // v4 identity fields are irrelevant to range parsing. Credits share the outro preference.
        val typeMap = listOf(
            "intro" to "intro", "recap" to "recap", "outro" to "outro",
            "credits" to "outro", "preview" to "preview",
        )
        val seenTypes = mutableSetOf<String>()
        for ((apiType, uiType) in typeMap) {
            val arr = json.optJSONArray(apiType) ?: continue
            if (!seenTypes.add(uiType)) continue
            for (i in 0 until arr.length()) {
                val range = arr.optJSONObject(i) ?: continue
                if (!range.has("start_ms") || !range.has("end_ms")) continue
                // The API uses null for an intro/recap starting at the beginning.
                val startMs = if (range.isNull("start_ms") && (uiType == "intro" || uiType == "recap")) {
                    0L
                } else {
                    range.optLong("start_ms", -1L)
                }
                if (startMs < 0) continue
                val endMs = if (range.isNull("end_ms")) {
                    OPEN_ENDED_MS
                } else {
                    range.optLong("end_ms", -1L).takeIf { it > startMs } ?: continue
                }
                segments.add(SkipSegment(uiType, startMs, endMs))
            }
        }
        return segments
    }
}
