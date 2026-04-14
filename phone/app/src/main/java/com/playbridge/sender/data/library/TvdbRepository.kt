package com.playbridge.sender.data.library

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * TVDB API v4 client.
 *
 * API key stored in SharedPreferences("browser_settings" / "tvdb_api_key").
 * Bearer token is cached in SharedPreferences and refreshed automatically when
 * within 24 hours of its 30-day expiry.
 *
 * Series metadata source preference stored as "series_meta_source":
 *   "auto"  — use TVDB if a key is configured, fall back to TMDB otherwise  (default)
 *   "tvdb"  — always prefer TVDB for series
 *   "tmdb"  — always prefer TMDB for series (disables this repository)
 */
class TvdbRepository(private val context: Context) {

    companion object {
        private const val TAG          = "TvdbRepository"
        private const val BASE         = "https://api4.thetvdb.com/v4"
        private const val PREFS        = "browser_settings"
        private const val KEY_API      = "tvdb_api_key"
        private const val KEY_TOKEN    = "tvdb_auth_token"
        private const val KEY_EXPIRES  = "tvdb_token_expires_at"
        private const val KEY_SOURCE   = "series_meta_source"
        private const val TOKEN_TTL_MS = 30L * 24 * 60 * 60 * 1000   // 30 days
        private const val REFRESH_BEFORE_MS = 24L * 60 * 60 * 1000   // refresh 1 day early
        private const val MAX_PAGES    = 10                            // safety cap: 5 000 episodes
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Configuration ────────────────────────────────────────────────────────

    fun isConfigured(): Boolean = !prefs.getString(KEY_API, null).isNullOrBlank()

    /** Returns the user's series-metadata-source preference. */
    fun seriesMetaSource(): String = prefs.getString(KEY_SOURCE, "auto") ?: "auto"

    /**
     * True when TVDB should be used as the primary series metadata source.
     * Respects the "series_meta_source" preference.
     */
    fun shouldUseTvdb(): Boolean {
        val source = seriesMetaSource()
        return when (source) {
            "tvdb" -> isConfigured()
            "auto" -> isConfigured()
            else   -> false   // "tmdb" or unrecognised
        }
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    /** Returns a valid bearer token, refreshing if expired or near expiry. */
    private suspend fun getToken(): String? = withContext(Dispatchers.IO) {
        val apiKey = prefs.getString(KEY_API, null)?.trim()
            ?: return@withContext null

        val existing   = prefs.getString(KEY_TOKEN, null)
        val expiresAt  = prefs.getLong(KEY_EXPIRES, 0L)
        val now        = System.currentTimeMillis()

        // Reuse cached token if still valid with buffer
        if (!existing.isNullOrBlank() && now < expiresAt - REFRESH_BEFORE_MS) {
            return@withContext existing
        }

        // Fetch a new token
        return@withContext try {
            val body = json.encodeToString(TvdbLoginRequest(apiKey))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$BASE/login").post(body).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Auth failed: ${response.code}")
                return@withContext null
            }
            val token = json.decodeFromString<TvdbAuthResponse>(
                response.body?.string() ?: return@withContext null
            ).data?.token ?: return@withContext null

            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_EXPIRES, now + TOKEN_TTL_MS)
                .apply()

            Log.d(TAG, "TVDB token refreshed")
            token
        } catch (e: Exception) {
            Log.e(TAG, "Auth error", e)
            null
        }
    }

    // ── Internal fetch helpers ────────────────────────────────────────────────

    private suspend inline fun <reified T> get(path: String): T? = withContext(Dispatchers.IO) {
        val token = getToken() ?: return@withContext null
        try {
            val request = Request.Builder()
                .url("$BASE$path")
                .header("Authorization", "Bearer $token")
                .get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "GET $path failed: ${response.code}")
                return@withContext null
            }
            json.decodeFromString<T>(response.body?.string() ?: return@withContext null)
        } catch (e: Exception) {
            Log.e(TAG, "GET $path error", e)
            null
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Find a series by IMDb ID (e.g. "tt0944947"). Returns the first series result. */
    suspend fun findSeriesByImdbId(imdbId: String): TvdbSearchResult? {
        val encoded = java.net.URLEncoder.encode(imdbId, "UTF-8")
        return get<TvdbSearchResponse>("/search?query=$encoded&type=series")
            ?.data
            ?.firstOrNull { it.type == "series" && it.imdbId == imdbId }
            ?: get<TvdbSearchResponse>("/search?query=$encoded")
                ?.data
                ?.firstOrNull { it.type == "series" }
    }

    /** Fetch series header (name, poster, overview, artworks). */
    suspend fun getSeriesDetails(tvdbId: Int): TvdbSeries? =
        get<TvdbSeriesResponse>("/series/$tvdbId/extended")?.data

    /**
     * Fetch all episodes in the "official" ordering (the TVDB canonical season structure).
     * Handles pagination automatically (max [MAX_PAGES] pages = 5 000 episodes).
     * Season 0 = specials/OVAs — included, filtered in UI if desired.
     */
    suspend fun getEpisodes(tvdbId: Int): List<TvdbEpisode> = withContext(Dispatchers.IO) {
        val allEpisodes = mutableListOf<TvdbEpisode>()
        var page = 0
        repeat(MAX_PAGES) {
            val response = get<TvdbEpisodesResponse>(
                "/series/$tvdbId/episodes/official?page=$page"
            ) ?: return@withContext allEpisodes

            allEpisodes.addAll(response.data?.episodes ?: emptyList())

            if (response.links?.next == null) return@withContext allEpisodes
            page++
        }
        allEpisodes
    }

    /**
     * Synthesise a [StremioMetaDetail] from TVDB episode data, supplemented by whatever
     * TMDB series details are already available (for ratings, cast, images, etc.).
     */
    fun buildStremioMeta(
        id: String,
        type: String,
        tvDetails: com.playbridge.sender.data.library.TmdbTvDetails?,
        episodes: List<TvdbEpisode>,
        tvdbSeries: TvdbSeries? = null
    ): StremioMetaDetail = StremioMetaDetail(
        id          = id,
        type        = type,
        name        = tvDetails?.name       ?: tvdbSeries?.name    ?: "",
        poster      = tvDetails?.posterUrl  ?: tvdbSeries?.image,
        background  = tvDetails?.backdropUrl
                        ?: tvdbSeries?.artworks?.filter { it.type == 3 }
                                              ?.maxByOrNull { it.score }?.image,
        description = tvDetails?.overview   ?: tvdbSeries?.overview,
        year        = tvDetails?.year       ?: tvdbSeries?.firstAired?.take(4),
        genres      = tvDetails?.genres?.map { it.name } ?: emptyList(),
        cast        = tvDetails?.cast       ?: emptyList(),
        videos      = episodes
            .sortedWith(compareBy({ it.seasonNumber }, { it.number }))
            .map { it.toStremioVideo() }
    )
}
