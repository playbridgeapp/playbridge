# Metadata Routing — Implementation Plan

Hybrid metadata system for the phone app's library detail screen. Series data comes from TVDB
(configurable), movies from TMDB, addon-native IDs (e.g. `kitsu:`) skip both and go straight to
the addon. Where any primary source fails, the addon acts as a universal fallback.

---

## Phase 1 — Quick Win: Fix Addon Meta Slowness

**Problem:** `fetchMetaWithSource` tries every meta-capable addon sequentially in DB order. Cinemeta
gets called with `kitsu:` IDs (returns nothing), Kitsu gets called with `tt` IDs (returns nothing) —
each wasted round-trip adds 1–2 seconds before the right addon is reached.

**Root cause:** The Stremio manifest's `idPrefixes` field (already parsed into `StremioResource`)
is thrown away during addon install — only resource names are saved to `InstalledAddonEntity`.

### Files

#### `data/library/AddonModels.kt`

Add one column to `InstalledAddonEntity`:

```kotlin
@Entity(tableName = "installed_addons")
data class InstalledAddonEntity(
    // ... existing fields ...
    // NEW — full StremioResource JSON (name + types + idPrefixes).
    // Empty string on pre-existing installs; treated as "accepts all IDs".
    val resourceDetailsJson: String = ""
)
```

Add two extension functions:

```kotlin
/** idPrefixes declared by this addon's "meta" resource. Empty = accepts any ID. */
fun InstalledAddonEntity.metaIdPrefixes(): List<String> {
    if (resourceDetailsJson.isBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<StremioResource>>(resourceDetailsJson)
            .firstOrNull { it.name == "meta" }?.idPrefixes ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

/** False only when the addon declares prefixes and none match [id]. */
fun InstalledAddonEntity.canHandleMetaId(id: String): Boolean {
    val prefixes = metaIdPrefixes()
    return prefixes.isEmpty() || prefixes.any { id.startsWith(it) }
}
```

#### `data/library/AddonRepository.kt`

In `installAddon` (and the refresh/update path), save the full resource objects:

```kotlin
val resourceDetailsJson = json.encodeToString(manifest.resources)  // add this

val entity = InstalledAddonEntity(
    // ... existing fields ...
    resourceDetailsJson = resourceDetailsJson                        // add this
)
```

In `fetchMetaWithSource`, add one predicate to the filter:

```kotlin
val addons = addonDao.getAllSync().filter { addon ->
    addon.isFeatureEnabled("meta") &&
        addon.supportsResource("meta") &&
        addon.types.split(",").any { it.trim().equals(type, ignoreCase = true) } &&
        addon.canHandleMetaId(id)   // ← NEW
}
```

#### Room migration

```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE installed_addons ADD COLUMN resourceDetailsJson TEXT NOT NULL DEFAULT ''"
        )
    }
}
```

### Result

| Scenario | Before | After |
|---|---|---|
| `kitsu:12345` | Cinemeta called → ~1 s failure → Kitsu called | Cinemeta skipped, Kitsu called immediately |
| `tt0944947` | Kitsu called → failure → Cinemeta called | Kitsu skipped, Cinemeta called immediately |
| Addon with no `idPrefixes` | Works | Still works — empty prefix list = accepts all |
| Pre-existing installs | n/a | `resourceDetailsJson` is blank → `canHandleMetaId` returns true → no regression |

---

## Phase 2 — Foundation: TVDB Data Layer

Add the TVDB API client and data models. No UI or routing changes yet — just the layer that
later phases build on.

### New file: `data/library/TvdbModels.kt`

```kotlin
package com.playbridge.sender.data.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Auth
@Serializable data class TvdbLoginRequest(val apikey: String)
@Serializable data class TvdbAuthResponse(val status: String = "", val data: TvdbAuthData? = null)
@Serializable data class TvdbAuthData(val token: String = "")

// Search
@Serializable data class TvdbSearchResponse(val data: List<TvdbSearchResult> = emptyList())
@Serializable
data class TvdbSearchResult(
    @SerialName("tvdb_id")  val tvdbId: String  = "",
    @SerialName("imdb_id")  val imdbId: String? = null,
    val name:     String  = "",
    val type:     String  = "",   // "series", "movie"
    val image:    String? = null,
    val overview: String? = null,
    val year:     String? = null
)

// Series
@Serializable data class TvdbSeriesResponse(val data: TvdbSeries? = null)
@Serializable
data class TvdbSeries(
    val id:         Int     = 0,
    val name:       String  = "",
    val overview:   String? = null,
    val image:      String? = null,   // full poster URL
    @SerialName("firstAired") val firstAired: String? = null,
    val status:     TvdbStatus?        = null,
    val artworks:   List<TvdbArtwork>  = emptyList()
)
@Serializable data class TvdbStatus(val name: String? = null)
@Serializable
data class TvdbArtwork(
    val type:  Int    = 0,    // 1=poster, 3=background/fanart
    val image: String = "",
    val score: Double = 0.0
)

// Episodes (official ordering, paginated — 500 per page)
@Serializable
data class TvdbEpisodesResponse(
    val data:  TvdbEpisodesData? = null,
    val links: TvdbLinks?        = null
)
@Serializable
data class TvdbEpisodesData(
    val series:   TvdbSeries?       = null,
    val episodes: List<TvdbEpisode> = emptyList()
)
@Serializable
data class TvdbLinks(
    val next:  String? = null,
    @SerialName("total_items") val totalItems: Int = 0
)
@Serializable
data class TvdbEpisode(
    val id:             Int     = 0,
    @SerialName("seasonNumber")   val seasonNumber:   Int     = 0,
    @SerialName("number")         val number:         Int     = 0,
    @SerialName("absoluteNumber") val absoluteNumber: Int?    = null,
    val name:      String? = null,
    val overview:  String? = null,
    val image:     String? = null,   // full thumbnail URL
    val aired:     String? = null,
    val runtime:   Int?    = null
) {
    /** Convert to a StremioVideo for use as addonMeta.videos. */
    fun toStremioVideo(): StremioVideo = StremioVideo(
        id        = "$seasonNumber:$number",
        title     = name ?: "Episode $number",
        season    = seasonNumber,
        episode   = number,
        released  = aired,
        thumbnail = image,
        overview  = overview
    )
}
```

### New file: `data/library/TvdbRepository.kt`

```kotlin
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
```

---

## Phase 3 — Bridge: Capture `tvdb_id` from TMDB

TMDB already returns `tvdb_id` inside the `external_ids` append for TV details. We just aren't
storing it. This is the free bridge between a TMDB ID and a TVDB ID — no extra API call needed.

### `data/library/TmdbModels.kt`

```kotlin
@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int?    = null   // ← add this
)
```

No other changes needed — `getTvDetails` already appends `external_ids`.

---

## Phase 4 — Settings UI

Add TVDB key input and the series metadata source selector to `LibrarySettingsScreen`.

### `browser/LibrarySettingsScreen.kt`

Add two new state vars alongside the existing TMDB/OMDB ones:

```kotlin
var tvdbApiKey   by remember { mutableStateOf(tmdbPrefs.getString("tvdb_api_key",       "") ?: "") }
var showTvdbKey  by remember { mutableStateOf(false) }
var seriesSource by remember { mutableStateOf(tmdbPrefs.getString("series_meta_source", "auto") ?: "auto") }
var seriesSourceExpanded by remember { mutableStateOf(false) }
```

TVDB API key input (place after the OMDB field, same pattern):

```kotlin
OutlinedTextField(
    value = tvdbApiKey,
    onValueChange = { newKey ->
        tvdbApiKey = newKey
        tmdbPrefs.edit().putString("tvdb_api_key", newKey.trim()).apply()
    },
    label = { Text("TVDB API Key (Optional)") },
    placeholder = { Text("Get one free at thetvdb.com/dashboard") },
    visualTransformation = if (showTvdbKey) VisualTransformation.None
                           else PasswordVisualTransformation(),
    trailingIcon = {
        IconButton(onClick = { showTvdbKey = !showTvdbKey }) {
            Icon(
                if (showTvdbKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = null
            )
        }
    },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
)
```

Series metadata source dropdown (show only when TVDB key is set):

```kotlin
if (tvdbApiKey.isNotBlank()) {
    val sourceOptions = listOf(
        "auto" to "Auto (TVDB if configured)",
        "tvdb" to "Always TVDB",
        "tmdb" to "Always TMDB"
    )
    ExposedDropdownMenuBox(
        expanded = seriesSourceExpanded,
        onExpandedChange = { seriesSourceExpanded = it }
    ) {
        OutlinedTextField(
            value = sourceOptions.first { it.first == seriesSource }.second,
            onValueChange = {},
            readOnly = true,
            label = { Text("Series metadata source") },
            trailingIcon = {
                Icon(
                    if (seriesSourceExpanded) Icons.Filled.ArrowDropUp
                    else Icons.Filled.ArrowDropDown,
                    contentDescription = null
                )
            },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = seriesSourceExpanded,
            onDismissRequest = { seriesSourceExpanded = false }
        ) {
            sourceOptions.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        seriesSource = value
                        tmdbPrefs.edit().putString("series_meta_source", value).apply()
                        seriesSourceExpanded = false
                    }
                )
            }
        }
    }
}
```

---

## Phase 5 — Routing: Restructure the `LaunchedEffect`

This is the heart of the feature. The `LaunchedEffect(id, type)` in `LibraryDetailScreen` gains a
top-level routing branch. The existing TMDB path is preserved unchanged as the `else` branch.

### `browser/LibraryDetailScreen.kt`

**Add `tvdb` instance** alongside `tmdb` and `omdb`:

```kotlin
val tmdb = remember { TmdbRepository(context) }
val tvdb = remember { TvdbRepository(context) }
val omdb = remember { OmdbRepository(context) }
```

**Replace the `LaunchedEffect` with the routed version:**

```kotlin
LaunchedEffect(id, type) {
    isLoading    = true
    errorMessage = null

    val isAddonNative = id.toIntOrNull() == null
                     && !id.startsWith("tt")
                     && !id.startsWith("tvdb:")

    when {
        // ── Addon-native ID (kitsu:, mal:, etc.) ────────────────────────────
        // Skip TMDB and TVDB entirely; go straight to the addon.
        isAddonNative -> {
            val result = runCatching {
                addonRepository.fetchMetaWithSource(addonType, id)
            }.getOrNull()
            addonMeta       = result?.first
            addonMetaSource = result?.second
        }

        // ── Series with TVDB enabled ─────────────────────────────────────────
        isSeries && tvdb.shouldUseTvdb() -> {

            when {
                // Numeric TMDB ID — getTvDetails first (gives tvdbId via external_ids)
                id.toIntOrNull() != null -> {
                    val numericId = id.toInt()
                    resolvedTmdbId = numericId
                    val tv = tmdb.getTvDetails(numericId)
                    tvDetails = tv
                    resolvedImdbId = tv?.imdbId

                    val tvdbId = tv?.externalIds?.tvdbId

                    coroutineScope {
                        val episodesDeferred   = tvdbId?.let { async { tvdb.getEpisodes(it) } }
                        val trailerDeferred    = async { tmdb.getTvVideos(numericId) }
                        val providersDeferred  = async { tmdb.getTvWatchProviders(numericId) }
                        val omdbDeferred       = resolvedImdbId
                            ?.takeIf { omdb.isConfigured() }
                            ?.let { async { omdb.getDetailsByImdbId(it) } }

                        val episodes = episodesDeferred?.await()
                        if (!episodes.isNullOrEmpty()) {
                            addonMeta       = tvdb.buildStremioMeta(id, addonType, tv, episodes)
                            addonMetaSource = "TVDB"
                        } else {
                            // TVDB gave nothing — fall back to addon
                            resolvedImdbId?.let { imdb ->
                                val result = runCatching {
                                    addonRepository.fetchMetaWithSource(addonType, imdb)
                                }.getOrNull()
                                addonMeta       = result?.first
                                addonMetaSource = result?.second
                            }
                        }

                        trailerUrl    = trailerDeferred.await()?.bestTrailerUrl
                        watchProviders = providersDeferred.await()
                        omdbDeferred?.let { omdbDetails = it.await() }
                    }
                }

                // IMDb tt ID — TMDB /find and TVDB search run in parallel
                id.startsWith("tt") -> {
                    resolvedImdbId = id
                    coroutineScope {
                        val tmdbDeferred = async { tmdb.findByImdbId(id) }
                        val tvdbDeferred = async { tvdb.findSeriesByImdbId(id) }
                        val omdbDeferred = if (omdb.isConfigured())
                            async { omdb.getDetailsByImdbId(id) } else null

                        val tmdbResult = tmdbDeferred.await()
                        val tvdbResult = tvdbDeferred.await()

                        // Resolve TMDB ID (for watchlist + trailers + providers)
                        val tvId = tmdbResult?.tvResults?.firstOrNull()?.id
                        if (tvId != null) {
                            resolvedTmdbId = tvId
                            coroutineScope {
                                val tvDetailsDeferred   = async { tmdb.getTvDetails(tvId) }
                                val trailerDeferred     = async { tmdb.getTvVideos(tvId) }
                                val providersDeferred   = async { tmdb.getTvWatchProviders(tvId) }
                                tvDetails      = tvDetailsDeferred.await()
                                trailerUrl     = trailerDeferred.await()?.bestTrailerUrl
                                watchProviders = providersDeferred.await()
                            }
                        }

                        // Resolve TVDB episodes
                        val tvdbId = tvdbResult?.tvdbId?.toIntOrNull()
                        if (tvdbId != null) {
                            val episodes = tvdb.getEpisodes(tvdbId)
                            if (episodes.isNotEmpty()) {
                                addonMeta       = tvdb.buildStremioMeta(id, addonType, tvDetails, episodes)
                                addonMetaSource = "TVDB"
                            }
                        }

                        // Fallback to addon if TVDB gave no episode data
                        if (addonMeta?.videos.isNullOrEmpty()) {
                            val result = runCatching {
                                addonRepository.fetchMetaWithSource(addonType, id)
                            }.getOrNull()
                            addonMeta       = result?.first
                            addonMetaSource = result?.second
                        }

                        omdbDeferred?.let { omdbDetails = it.await() }
                    }
                }

                // Direct tvdb: ID
                id.startsWith("tvdb:") -> {
                    val tvdbId = id.removePrefix("tvdb:").toIntOrNull()
                    if (tvdbId != null) {
                        coroutineScope {
                            val seriesDeferred   = async { tvdb.getSeriesDetails(tvdbId) }
                            val episodesDeferred = async { tvdb.getEpisodes(tvdbId) }
                            val tvdbSeries = seriesDeferred.await()
                            val episodes   = episodesDeferred.await()
                            if (episodes.isNotEmpty()) {
                                addonMeta       = tvdb.buildStremioMeta(id, addonType, null, episodes, tvdbSeries)
                                addonMetaSource = "TVDB"
                            } else {
                                // No episodes from TVDB — addon fallback
                                val result = runCatching {
                                    addonRepository.fetchMetaWithSource(addonType, id)
                                }.getOrNull()
                                addonMeta       = result?.first
                                addonMetaSource = result?.second
                            }
                        }
                    } else {
                        errorMessage = "Invalid TVDB ID: $id"
                    }
                }
            }
        }

        // ── Existing TMDB path (movies + series when TVDB is off) ────────────
        // Paste the original LaunchedEffect content here, unchanged.
        else -> {
            // ... original when { id.toIntOrNull() != null -> { ... } id.startsWith("tt") -> { ... } else -> { ... } }
        }
    }

    if (movieDetails == null && tvDetails == null && addonMeta == null && errorMessage == null) {
        errorMessage = "Could not load details for \"$id\"."
    }
    isLoading = false
    hasAddons = addonRepository.hasAnyAddons()
}
```

---

## Routing Summary

```
ID received
│
├─ addon-native (kitsu:, mal:, etc.)
│   └─ addon meta only
│
├─ isSeries && TVDB enabled
│   ├─ numeric TMDB ID  →  getTvDetails (→ tvdbId) → TVDB episodes ‖ TMDB trailers+providers
│   ├─ tt IMDb ID       →  TVDB search ‖ TMDB /find  (fully parallel)
│   ├─ tvdb: ID         →  TVDB direct
│   └─ fallback         →  addon meta if TVDB returns no episodes
│
└─ everything else (movies, series with TVDB off)
    └─ existing TMDB + addon path, unchanged
```

---

## Data Sources by Feature

| Feature | Source |
|---|---|
| Series season/episode structure | **TVDB** (when enabled) |
| Series overview, cast, rating, poster | TMDB `getTvDetails` (always fetched) |
| Series trailers | TMDB `getTvVideos` |
| Series watch providers | TMDB `getTvWatchProviders` |
| Movie everything | **TMDB** (TVDB not used for movies) |
| Browse / Discover / Search | **TMDB** (unchanged) |
| Addon-native content (kitsu:, etc.) | **Addon** (unchanged) |
| Episode data fallback | **Addon** `fetchMetaWithSource` |
| Ratings supplement | OMDB (unchanged, optional) |

---

## Dependencies & Prerequisites

- TVDB API key — free at [thetvdb.com/dashboard](https://thetvdb.com/dashboard)
- TMDB API key — already required (unchanged)
- No new Gradle dependencies — TVDB uses the same OkHttp + kotlinx.serialization stack
- Room migration required for Phase 1 (`resourceDetailsJson` column)
