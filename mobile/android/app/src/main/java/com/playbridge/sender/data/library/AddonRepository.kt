package com.playbridge.sender.data.library

import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Manages Stremio-compatible addons: installing from manifest URLs,
 * resolving streams via the Stremio addon protocol.
 */
class AddonRepository(
    private val addonDao: AddonDao,
    private val cacheDir: File? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {

    companion object {
        private const val TAG = "AddonRepository"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 60 minutes
        private const val CATALOG_CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes — catalogs change more often
        private const val CACHE_FILE_NAME = "stream_cache.json"
    }

    @Serializable
    private data class CacheEntry(
        val timestamp: Long,
        val streams: List<ResolvedStream>
    )

    @Serializable
    private data class PersistedCache(val entries: Map<String, CacheEntry>)

    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private val subtitleCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<StremioStream>>>()
    private val catalogCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<StremioMetaPreview>>>()
    // Triple: (timestampMs, meta, addonName)
    private val metaCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, StremioMetaDetail, String>>()
    private val kitsuCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val diskJson = Json { ignoreUnknownKeys = true }

    init {
        loadCacheFromDisk()
    }

    private fun loadCacheFromDisk() {
        val file = cacheDir?.let { File(it, CACHE_FILE_NAME) } ?: return
        if (!file.exists()) return
        try {
            val persisted = diskJson.decodeFromString<PersistedCache>(file.readText())
            val now = System.currentTimeMillis()
            persisted.entries.forEach { (key, entry) ->
                if (now - entry.timestamp < CACHE_TTL_MS) {
                    streamCache[key] = entry
                }
            }
            Log.d(TAG, "Loaded ${streamCache.size} cached entries from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load stream cache from disk: ${e.message}")
        }
    }

    private fun saveCacheToDisk() {
        val file = cacheDir?.let { File(it, CACHE_FILE_NAME) } ?: return
        ioScope.launch {
            try {
                val persisted = PersistedCache(entries = HashMap(streamCache))
                file.writeText(diskJson.encodeToString(persisted))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save stream cache to disk: ${e.message}")
            }
        }
    }

    /**
     * Clear the stream cache for a specific content item (movie or episode).
     * Used when the user manually triggers a refresh to bypass cached results.
     */
    fun clearStreamCache(type: String, id: String) {
        val cacheKey = "$type:$id"
        val suffix = ":$type:$id"

        // 1. Clear top-level aggregated entry
        streamCache.remove(cacheKey)

        // 2. Clear individual addon entries for this specific item
        // Use removeIf for efficient bulk removal in ConcurrentHashMap
        streamCache.keys.removeIf { it.endsWith(suffix) }

        saveCacheToDisk()
        Log.d(TAG, "Cleared stream cache for $cacheKey (and all addon-specific entries)")
    }

    /** Drop all in-memory catalog pages so the next fetch goes to the network. */
    fun clearCatalogCache() {
        catalogCache.clear()
        Log.d(TAG, "Cleared in-memory catalog cache")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Check if a URL returns a successful response (2xx).
     * Uses a HEAD request for efficiency.
     */
    suspend fun isUrlReachable(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).head().build()
                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }

    // ==================== Addon Installation ====================

    /**
     * Install an addon from its manifest URL.
     * The URL can be the full manifest URL or the base addon URL.
     * Returns the installed addon entity, or null on failure.
     */
    suspend fun installAddon(inputUrl: String): InstalledAddonEntity? {
        return withContext(Dispatchers.IO) {
            try {
                // Normalize URL: ensure it ends with /manifest.json
                val manifestUrl = normalizeManifestUrl(inputUrl)
                val baseUrl = manifestUrl.removeSuffix("/manifest.json")

                Log.d(TAG, "Fetching manifest from: $manifestUrl")
                val request = Request.Builder().url(manifestUrl).get().build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Manifest fetch failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val manifest = json.decodeFromString<StremioManifest>(body)

                val resourceNames = manifest.resources.map { it.name }.distinct()
                val resourcesJson = json.encodeToString(resourceNames)
                val resourceDetailsJson = json.encodeToString(manifest.resources)
                val catalogsJson = json.encodeToString(manifest.catalogs)

                val entity = InstalledAddonEntity(
                    manifestUrl = manifestUrl,
                    name = manifest.name.ifBlank { "Unknown Addon" },
                    description = manifest.description,
                    baseUrl = baseUrl,
                    version = manifest.version,
                    types = manifest.types.joinToString(","),
                    resources = resourcesJson,
                    resourceDetailsJson = resourceDetailsJson,
                    catalogsJson = catalogsJson,
                    playEndpoint = manifest.behaviorHints?.playEndpoint ?: "",
                    isConfigurable = manifest.behaviorHints?.configurable ?: false
                )

                addonDao.insert(entity)
                Log.d(TAG, "Installed addon: ${entity.name} from $manifestUrl")
                entity
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install addon", e)
                null
            }
        }
    }

    /**
     * Remove an installed addon.
     */
    suspend fun removeAddon(addon: InstalledAddonEntity) {
        addonDao.delete(addon)

        // Evict orphaned cache entries to free memory and prevent stale results if reinstalled
        val prefix = "${addon.baseUrl}:"
        streamCache.keys.filter { it.startsWith(prefix) }.forEach {
            streamCache.remove(it)
        }
    }

    /**
     * Persist the user's feature configuration for an addon.
     *
     * @param isEnabled       Master on/off switch (false = skip addon entirely).
     * @param disabledFeatures Set of resource names the user has turned off,
     *                         e.g. setOf("catalog", "meta").  Empty = all features active.
     */
    suspend fun configureAddon(
        addon: InstalledAddonEntity,
        isEnabled: Boolean,
        disabledFeatures: Set<String>
    ) {
        addonDao.update(
            addon.copy(
                isEnabled = isEnabled,
                disabledFeatures = disabledFeatures.joinToString(",")
            )
        )
    }

    /**
     * Persist a new display/resolution order for all addons.
     * Call this after the user finishes a drag-to-reorder gesture.
     * @param addons Addons in the desired new order (index 0 = highest priority).
     */
    suspend fun reorderAddons(addons: List<InstalledAddonEntity>) {
        addons.forEachIndexed { index, addon ->
            addonDao.update(addon.copy(sortOrder = index))
        }
    }

    /**
     * Re-fetch an addon's manifest from its URL and update the stored metadata
     * (name, description, version, resources, catalogs). The manifestUrl itself
     * and user settings (isEnabled, sortOrder) are preserved.
     * Returns the updated entity, or null if the fetch/parse fails.
     */
    suspend fun refreshAddon(addon: InstalledAddonEntity): InstalledAddonEntity? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(addon.manifestUrl).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val manifest = json.decodeFromString<StremioManifest>(body)
                val resourceNames = manifest.resources.map { it.name }.distinct()
                val resourceDetailsJson = json.encodeToString(manifest.resources)
                val updated = addon.copy(
                    name = manifest.name.ifBlank { addon.name },
                    description = manifest.description,
                    version = manifest.version,
                    types = manifest.types.joinToString(","),
                    resources = json.encodeToString(resourceNames),
                    resourceDetailsJson = resourceDetailsJson,
                    catalogsJson = json.encodeToString(manifest.catalogs),
                    playEndpoint = manifest.behaviorHints?.playEndpoint ?: "",
                    isConfigurable = manifest.behaviorHints?.configurable ?: false
                )
                addonDao.update(updated)
                Log.d(TAG, "Refreshed addon: ${updated.name}")
                updated
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh addon: ${addon.name}", e)
                null
            }
        }
    }

    /**
     * Returns true if at least one addon is installed.
     * Used by the UI to decide between stream-picker mode vs. provider-info-only mode.
     */
    suspend fun hasAnyAddons(): Boolean = addonDao.count() > 0

    /** Returns all installed addons. */
    suspend fun getInstalledAddons(): List<InstalledAddonEntity> = withContext(Dispatchers.IO) {
        addonDao.getAllSync()
    }

    /**
     * Observe the installed-addon set. Emits on every change (install, remove, refresh,
     * reorder, enable/disable) so the Home screen can keep its catalogs in sync without
     * relying on callers to remember to trigger a refresh.
     */
    fun observeInstalledAddons(): Flow<List<InstalledAddonEntity>> = addonDao.getAll()

    /**
     * Enumerate all catalogs available across all installed addons.
     * Returns a list of (addon, contentType, catalogId).
     */
    /**
     * Enumerate all catalogs available across all installed addons.
     * Returns a list of (addon, contentType, catalogId) where catalogId is the
     * URL path segment used in /catalog/{type}/{catalogId}.json requests.
     */
    suspend fun getAvailableCatalogs(): List<Triple<InstalledAddonEntity, String, String>> {
        return withContext(Dispatchers.IO) {
            val addons = addonDao.getAllSync().filter { it.isFeatureEnabled("catalog") }
            addons.flatMap { addon ->
                addon.parsedCatalogEntries()
                    .filter { it.type.isNotBlank() && it.id.isNotBlank() }
                    .map { entry -> Triple(addon, entry.type, entry.id) }
            }
        }
    }

    // ==================== Catalog Resolution ====================

    /**
     * Fetch one page of items from a specific addon catalog.
     * Uses a 15-minute cache keyed by (baseUrl, type, catalogId, skip).
     *
     * @param addon   The installed addon to query
     * @param type    Content type: "movie", "series", "channel", etc.
     * @param catalogId  The catalog ID from the addon manifest
     * @param skip    Offset for pagination (Stremio standard: increment by 100)
     */
    suspend fun fetchCatalog(
        addon: InstalledAddonEntity,
        type: String,
        catalogId: String,
        skip: Int = 0
    ): List<StremioMetaPreview> {
        val cacheKey = "${addon.baseUrl}:$type:$catalogId:$skip"
        val cached = catalogCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < CATALOG_CACHE_TTL_MS) {
            Log.d(TAG, "Using cached catalog for $cacheKey")
            return cached.second
        }
        return withContext(Dispatchers.IO) {
            try {
                val url = if (skip > 0)
                    "${addon.baseUrl}/catalog/$type/$catalogId/skip=$skip.json"
                else
                    "${addon.baseUrl}/catalog/$type/$catalogId.json"
                Log.d(TAG, "Fetching catalog: $url")
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Catalog fetch failed from ${addon.name}: ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                Log.d(TAG, "Catalog response body length: ${body.length}")
                if (body.length < 500) {
                    Log.d(TAG, "Catalog response body: $body")
                }
                val parsed = json.decodeFromString<StremioMetasResponse>(body)
                val items = parsed.metas ?: emptyList()
                catalogCache[cacheKey] = Pair(System.currentTimeMillis(), items)
                items
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching catalog from ${addon.name}", e)
                emptyList()
            }
        }
    }

    // ==================== Catalog Search ====================

    /**
     * Search a specific addon catalog using the Stremio extra protocol.
     * URL pattern: `{baseUrl}/catalog/{type}/{catalogId}/search={query}.json`
     *
     * Results are intentionally NOT cached — search queries vary freely and
     * caching them would waste memory without meaningful hit rates.
     *
     * @param addon     The installed addon to query
     * @param type      Content type: "movie", "series", etc.
     * @param catalogId The catalog ID from the addon manifest
     * @param query     The user's search string; will be URL-encoded
     */
    suspend fun searchCatalog(
        addon: InstalledAddonEntity,
        type: String,
        catalogId: String,
        query: String
    ): List<StremioMetaPreview> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
                val url = "${addon.baseUrl}/catalog/$type/$catalogId/search=$encoded.json"
                Log.d(TAG, "Searching catalog: $url")
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Catalog search failed from ${addon.name}: ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<StremioMetasResponse>(body)
                parsed.metas ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error searching catalog from ${addon.name}", e)
                emptyList()
            }
        }
    }

    /**
     * Search across all installed addon catalogs that declare search support.
     * Fires all matching catalogs in parallel and merges results.
     * Deduplicates by item ID.
     *
     * @param query The user's search string
     */
    suspend fun searchAllCatalogs(query: String): List<StremioMetaPreview> {
        if (query.isBlank()) return emptyList()
        val addons = addonDao.getAllSync()
        return coroutineScope {
            val deferreds = addons.flatMap { addon ->
                addon.parsedCatalogEntries()
                    .filter { it.supportsSearch && it.type.isNotBlank() && it.id.isNotBlank() }
                    .map { entry ->
                        async(Dispatchers.IO) {
                            searchCatalog(addon, entry.type, entry.id, query)
                        }
                    }
            }
            val seen = mutableSetOf<String>()
            deferreds.awaitAll()
                .flatten()
                .filter { item -> item.id.isNotBlank() && seen.add(item.id) }
        }
    }

    /**
     * Like [searchAllCatalogs] but returns results grouped by addon name so the UI
     * can render per-source filter chips. Deduplication is applied within each group.
     */
    suspend fun searchAllCatalogsGrouped(query: String): List<AddonSearchResultGroup> {
        if (query.isBlank()) return emptyList()
        val addons = addonDao.getAllSync()
        return coroutineScope {
            // One deferred per (addon, catalogEntry) pair, tagged with the addon name
            val tagged = addons.flatMap { addon ->
                addon.parsedCatalogEntries()
                    .filter { it.supportsSearch && it.type.isNotBlank() && it.id.isNotBlank() }
                    .map { entry ->
                        async(Dispatchers.IO) {
                            val items = searchCatalog(addon, entry.type, entry.id, query)
                            addon.name to items
                        }
                    }
            }
            // Merge by addon name, dedup within each group
            val byAddon = mutableMapOf<String, MutableList<StremioMetaPreview>>()
            tagged.awaitAll().forEach { (addonName, items) ->
                val bucket = byAddon.getOrPut(addonName) { mutableListOf() }
                val seen = bucket.map { it.id }.toMutableSet()
                items.forEach { item -> if (item.id.isNotBlank() && seen.add(item.id)) bucket += item }
            }
            byAddon.entries
                .filter { it.value.isNotEmpty() }
                .map { AddonSearchResultGroup(addonName = it.key, items = it.value) }
        }
    }

    /**
     * Like [searchAllCatalogsGrouped] but invokes [onGroupsUpdated] each time an addon
     * catalog responds — even before all catalogs have finished — so the UI can render
     * results progressively instead of waiting for the slowest addon.
     *
     * Concurrency: all catalog searches fire in parallel; the callback is guarded by a
     * mutex so it is never called simultaneously from two coroutines.
     */
    suspend fun searchAllCatalogsGroupedStreaming(
        query: String,
        onGroupsUpdated: suspend (List<AddonSearchResultGroup>) -> Unit
    ) {
        if (query.isBlank()) return
        val addons = addonDao.getAllSync()
        val resultGroups = mutableMapOf<String, MutableList<StremioMetaPreview>>()
        val mutex = Mutex()

        coroutineScope {
            val jobs = addons.flatMap { addon ->
                addon.parsedCatalogEntries()
                    .filter { it.supportsSearch && it.type.isNotBlank() && it.id.isNotBlank() }
                    .map { entry ->
                        launch(Dispatchers.IO) {
                            val items = searchCatalog(addon, entry.type, entry.id, query)
                            if (items.isNotEmpty()) {
                                val (effectiveProvider, _) = parseCatalogTitle(addon.name, entry.name)
                                val snapshot = mutex.withLock {
                                    val bucket = resultGroups.getOrPut(effectiveProvider) { mutableListOf() }
                                    val seen = bucket.map { it.id }.toMutableSet()
                                    items.forEach { item ->
                                        if (item.id.isNotBlank() && seen.add(item.id)) bucket += item
                                    }
                                    resultGroups.entries
                                        .filter { it.value.isNotEmpty() }
                                        .map { AddonSearchResultGroup(addonName = it.key, items = it.value.toList()) }
                                }
                                onGroupsUpdated(snapshot)
                            }
                        }
                    }
            }
            jobs.joinAll()
        }
    }

    // ==================== Meta Resolution ====================

    /**
     * Fetch full metadata for a content item from installed addons that declare meta support.
     * Tries each qualifying addon in order and returns the first successful result.
     * Results are cached for [CACHE_TTL_MS] (60 minutes).
     *
     * @param type  Content type: "movie", "series", etc.
     * @param id    The addon-specific content ID (may be IMDb "tt..." or a custom addon ID)
     */
    suspend fun fetchMeta(type: String, id: String): StremioMetaDetail? =
        fetchMetaWithSource(type, id)?.first

    /**
     * Like [fetchMeta] but also returns the name of the addon that supplied the metadata.
     * Returns null when no addon could provide metadata for the given type + id.
     */
    suspend fun fetchMetaWithSource(type: String, id: String, forcedSource: String? = null): Pair<StremioMetaDetail, String>? {
        val cacheKey = "meta:$type:$id:${forcedSource ?: "any"}"
        val cached = metaCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            Log.d(TAG, "Using cached meta for $cacheKey")
            return Pair(cached.second, cached.third)
        }

        val addons = addonDao.getAllSync().filter { addon ->
            addon.isFeatureEnabled("meta") &&
                addon.supportsResource("meta") &&
                addon.types.split(",").any { it.trim().equals(type, ignoreCase = true) } &&
                addon.canHandleMetaId(id)
        }

        if (addons.isEmpty()) {
            Log.d(TAG, "No meta-capable addons for type=$type")
            return null
        }

        return withContext(Dispatchers.IO) {
            // 1. Try to find an addon that exactly matches the forcedSource name
            if (forcedSource != null) {
                val directAddon = addons.find { it.name.equals(forcedSource, ignoreCase = true) }
                if (directAddon != null) {
                    val result = fetchFromAddon(directAddon, type, id, null)
                    if (result != null) {
                        metaCache[cacheKey] = Triple(System.currentTimeMillis(), result, directAddon.name)
                        return@withContext Pair(result, directAddon.name)
                    }
                }
            }

            // 2. Try the rest of the addons
            for (addon in addons) {
                // Skip the one we already tried above
                if (forcedSource != null && addon.name.equals(forcedSource, ignoreCase = true)) continue

                val result = fetchFromAddon(addon, type, id, forcedSource)
                if (result != null) {
                    metaCache[cacheKey] = Triple(System.currentTimeMillis(), result, addon.name)
                    return@withContext Pair(result, addon.name)
                }
            }
            null
        }
    }

    private suspend fun fetchFromAddon(addon: InstalledAddonEntity, type: String, id: String, forcedSource: String?): StremioMetaDetail? {
        return try {
            var url = "${addon.baseUrl}/meta/$type/$id.json"
            // If this is the Hub (aggregating multiple sources), pass the 'src' parameter
            if (forcedSource != null && (addon.baseUrl.contains(":8080") || addon.supportsPlayEndpoint())) {
                url += "?src=${android.net.Uri.encode(forcedSource)}"
            }
            Log.d(TAG, "Fetching meta from ${addon.name}: $url")
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val parsed = json.decodeFromString<StremioMetaResponse>(body)
                parsed.meta
            } else {
                Log.e(TAG, "Meta fetch failed from ${addon.name}: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching meta from ${addon.name}", e)
            null
        }
    }

    // ==================== Subtitle Resolution ====================

    /**
     * Resolve subtitles for a movie or episode from all installed addons that declare
     * subtitle support for the requested content type.
     *
     * @param type "movie" or "series"
     * @param id IMDb ID for movies (e.g. "tt1234567") or "tt1234567:season:episode" for series
     */
    suspend fun resolveSubtitles(type: String, id: String): List<StremioStream> {
        val cacheKey = "$type:$id"
        val cached = subtitleCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            Log.d(TAG, "Using cached subtitles for $cacheKey")
            return cached.second
        }

        val addons = addonDao.getAllSync().filter { addon ->
            addon.isFeatureEnabled("subtitles") &&
                addon.supportsResource("subtitles") &&
                addon.types.split(",").any { it.trim().equals(type, ignoreCase = true) }
        }

        if (addons.isEmpty()) {
            Log.d(TAG, "No subtitle-capable addons for type=$type")
            return emptyList()
        }

        return coroutineScope {
            val results = addons.map { addon ->
                async(Dispatchers.IO) {
                    fetchSubtitlesFromAddon(addon, type, id)
                }
            }.awaitAll()

            val merged = results.flatten()
            subtitleCache[cacheKey] = Pair(System.currentTimeMillis(), merged)
            merged
        }
    }

    private suspend fun fetchSubtitlesFromAddon(
        addon: InstalledAddonEntity,
        type: String,
        id: String
    ): List<StremioStream> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${addon.baseUrl}/subtitles/$type/$id.json"
                Log.d(TAG, "Fetching subtitles from ${addon.name}: $url")
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Subtitle fetch failed from ${addon.name}: ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<StremioStreamResponse>(body)
                parsed.subtitles ?: parsed.streams ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching subtitles from ${addon.name}", e)
                emptyList()
            }
        }
    }

    // ==================== Stream Resolution ====================

    /**
     * Resolve streams for a movie from all installed addons.
     * @param imdbId The IMDB ID (e.g., "tt1234567")
     */
    suspend fun resolveMovieStreams(imdbId: String): List<ResolvedStream> {
        return resolveStreams("movie", imdbId)
    }

    /**
     * Resolve streams for a specific TV episode from all installed addons.
     * @param imdbId The IMDB ID of the series
     * @param season Season number
     * @param episode Episode number
     */
    suspend fun resolveEpisodeStreams(imdbId: String, season: Int, episode: Int): List<ResolvedStream> {
        return resolveStreams("series", "$imdbId:$season:$episode")
    }

    /**
     * Resolve the best stream for each episode in a season.
     * Returns an ordered list of (episodeNumber, bestStream) pairs.
     * Episodes with no resolved streams are skipped.
     * @param qualityFilter optional quality keywords to prefer (e.g. listOf("2160p", "4k"))
     */
    suspend fun resolveSeasonStreams(
        imdbId: String,
        season: Int,
        episodeCount: Int,
        showName: String,
        qualityFilter: List<String> = emptyList()
    ): List<EpisodeStream> {
        return coroutineScope {
            val results = (1..episodeCount).map { episode ->
                async(Dispatchers.IO) {
                    val streams = resolveStreams("series", "$imdbId:$season:$episode")

                    // Score each stream: prefer English/Multi language + quality match
                    val best = streams
                        .maxByOrNull { s ->
                            val text = "${s.stream.name.orEmpty()} ${s.stream.title.orEmpty()}".lowercase()
                            var score = 0

                            // Language preference: strongly prefer English / Multi-audio
                            val englishPatterns = listOf("english", "eng", "multi", "dual", "multi audio")
                            if (englishPatterns.any { text.contains(it) }) {
                                score += 10
                            }
                            // Deprioritize streams that are explicitly single non-English language
                            val nonEnglishOnly = listOf("german", "deutsch", "french", "français", "spanish", "español", "italian", "italiano", "portuguese", "português")
                            val hasNonEnglish = nonEnglishOnly.any { text.contains(it) }
                            val hasEnglish = englishPatterns.any { text.contains(it) }
                            if (hasNonEnglish && !hasEnglish) {
                                score -= 5
                            }

                            // Quality preference
                            if (qualityFilter.isNotEmpty() && qualityFilter.any { text.contains(it.lowercase()) }) {
                                score += 5
                            }

                            // Prefer direct URLs
                            if (s.stream.isDirectUrl) score += 1

                            score
                        }

                    if (best != null) {
                        EpisodeStream(
                            season = season,
                            episode = episode,
                            title = "$showName S${season}E$episode",
                            stream = best
                        )
                    } else null
                }
            }.awaitAll()

            results.filterNotNull()
        }
    }

    /**
     * Flow-based sequential season stream resolution with cache-aware throttling.
     * Emits one EpisodeStream at a time (or null for failed episodes).
     *
     * - Cached episodes emit instantly (no delay).
     * - Uncached episodes are separated by [delayBetweenMs] to avoid addon rate limits.
     * - When [targetBitrateMbps] is provided, streams closest to that bitrate are preferred.
     *
     * @param episodeRuntimeMinutes map of episode number → runtime in minutes (from TMDB)
     * @param delayBetweenMs delay between uncached episode resolutions (default 2s)
     */
    fun resolveSeasonStreamsFlow(
        imdbId: String,
        season: Int,
        episodeCount: Int,
        showName: String,
        qualityFilter: List<String> = emptyList(),
        targetBitrateMbps: Double? = null,
        preferredBingeGroup: String? = null,
        episodeRuntimeMinutes: Map<Int, Int> = emptyMap(),
        delayBetweenMs: Long = 2000
    ): Flow<EpisodeStream?> = flow {
        for (episode in 1..episodeCount) {
            // Check cache to decide whether to delay
            val cacheKey = "series:$imdbId:$season:$episode"
            val cached = streamCache[cacheKey]
            val isCacheHit = cached != null &&
                    System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS &&
                    !cached.streams.hasRateLimitError()

            if (episode > 1) {
                // Always delay between episodes: rate-limit protection for uncached, and for
                // cached gives the TV player time to launch and register its receiver.
                delay(if (isCacheHit) minOf(delayBetweenMs, 1000L) else delayBetweenMs)
            }

            try {
                val streams = resolveStreams("series", "$imdbId:$season:$episode")

                // Score streams: bingeGroup match > bitrate proximity > language > quality
                val best = streams.maxByOrNull { s ->
                    scoreStream(s, qualityFilter, targetBitrateMbps, preferredBingeGroup, episodeRuntimeMinutes[episode])
                }

                if (best != null) {
                    emit(
                        EpisodeStream(
                            season = season,
                            episode = episode,
                            title = "$showName S${season}E$episode",
                            stream = best
                        )
                    )
                } else {
                    emit(null) // Signal that this episode had no streams
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving S${season}E$episode: ${e.message}")
                emit(null)
            }
        }
    }

    /**
     * Score a stream for auto-selection. Higher = better.
     * Includes language preference, quality match, direct URL bonus, and bitrate proximity.
     */
    internal fun scoreStream(
        s: ResolvedStream,
        qualityFilter: List<String> = emptyList(),
        targetBitrateMbps: Double? = null,
        preferredBingeGroup: String? = null,
        episodeRuntimeMinutes: Int? = null
    ): Int {
        val text = "${s.stream.name.orEmpty()} ${s.stream.title.orEmpty()}".lowercase()
        var score = 0

        // Highest priority: same torrent/release group as Ep1 ensures language consistency
        if (preferredBingeGroup != null && s.stream.behaviorHints?.bingeGroup == preferredBingeGroup) {
            score += 20
        }

        // Language preference: strongly prefer English / Multi-audio
        val englishPatterns = listOf("english", "eng", "multi", "dual", "multi audio")
        if (englishPatterns.any { text.contains(it) }) {
            score += 10
        }
        // Deprioritize streams that are explicitly single non-English language
        val nonEnglishOnly = listOf("german", "deutsch", "french", "français", "spanish", "español", "italian", "italiano", "portuguese", "português")
        val hasNonEnglish = nonEnglishOnly.any { text.contains(it) }
        val hasEnglish = englishPatterns.any { text.contains(it) }
        if (hasNonEnglish && !hasEnglish) {
            score -= 5
        }

        // Quality preference
        if (qualityFilter.isNotEmpty() && qualityFilter.any { text.contains(it.lowercase()) }) {
            score += 5
        }

        // Prefer direct URLs
        if (s.stream.isDirectUrl) score += 1

        // Bitrate proximity scoring
        if (targetBitrateMbps != null) {
            val videoSize = s.stream.behaviorHints?.videoSize
            if (videoSize != null && videoSize > 0) {
                val runtimeMin = episodeRuntimeMinutes ?: 45
                val estimatedMbps = videoSize * 8.0 / (runtimeMin * 60 * 1_000_000.0)
                val proximity = 1.0 / (1.0 + abs(estimatedMbps - targetBitrateMbps))
                score += (proximity * 15).toInt() // Strong weight for bitrate match
            }
        }

        return score
    }

    /**
     * Internal: Resolve streams from all installed addons in parallel.
     * Returns a complete list (used by season play).
     */
    private suspend fun resolveStreams(type: String, id: String, forcedSource: String? = null): List<ResolvedStream> {
        val cacheKey = "$type:$id"
        val cached = streamCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            if (cached.streams.hasRateLimitError()) {
                streamCache.remove(cacheKey)
            } else {
                Log.d(TAG, "Using cached streams for $cacheKey")
                return cached.streams
            }
        }

        // Include addons that explicitly support "stream" OR have no resource data
        // (pre-Phase 1 installs have resources = "" and must not be silently excluded).
        // Respects both the master isEnabled switch and the per-feature disabledFeatures list.
        val addons = addonDao.getAllSync().filter {
            it.isFeatureEnabled("stream") && (it.resources.isBlank() || it.supportsResource("stream"))
        }
        if (addons.isEmpty()) {
            Log.d(TAG, "No stream-capable addons installed")
            return emptyList()
        }

        return coroutineScope {
            val results = addons.map { addon ->
                async(Dispatchers.IO) {
                    fetchStreamsFromAddon(addon, type, id, forcedSource)
                }
            }.awaitAll()

            val finalStreams = results.flatten().sortedByDescending { it.stream.isDirectUrl }
            if (!finalStreams.hasRateLimitError()) {
                streamCache[cacheKey] = CacheEntry(System.currentTimeMillis(), finalStreams)
                saveCacheToDisk()
            }
            finalStreams
        }
    }

    /**
     * One-shot, cache-aware stream resolution. Used by the in-app player to lazily
     * resolve the next episode when there is no play-endpoint ("Hub") addon to build
     * a deterministic playlist URL from.
     */
    suspend fun resolveStreamsOnce(type: String, id: String, forcedSource: String? = null): List<ResolvedStream> =
        resolveStreams(type, id, forcedSource)

    /**
     * Resolve streams incrementally — emits results from each addon as soon as it completes.
     * This allows the UI to show streams progressively.
     */
    fun resolveStreamsFlow(type: String, id: String, forcedSource: String? = null): kotlinx.coroutines.flow.Flow<List<ResolvedStream>> =
        kotlinx.coroutines.flow.flow {
            val cacheKey = "$type:$id"
            val cached = streamCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
                if (cached.streams.hasRateLimitError()) {
                    Log.d(TAG, "Removing rate-limited cache for $cacheKey")
                    streamCache.remove(cacheKey)
                } else {
                    Log.d(TAG, "Using cached streams for $cacheKey flow")
                    emit(cached.streams)
                    return@flow
                }
            }

            Log.d(TAG, "Starting fresh resolution for $cacheKey (cache empty or expired)")
            emit(emptyList()) // Immediate clear for UI feedback

            // Include addons that explicitly support "stream" OR have no resource data
            // (pre-Phase 1 installs have resources = "" and must not be silently excluded).
            // Respects both the master isEnabled switch and the per-feature disabledFeatures list.
            val addons = addonDao.getAllSync().filter {
                it.isFeatureEnabled("stream") && (it.resources.isBlank() || it.supportsResource("stream"))
            }
            if (addons.isEmpty()) {
                Log.d(TAG, "No stream-capable addons installed")
                emit(emptyList())
                return@flow
            }

            val accumulated = mutableListOf<ResolvedStream>()

            coroutineScope {
                addons.map { addon ->
                    async(Dispatchers.IO) {
                        fetchStreamsFromAddon(addon, type, id, forcedSource)
                    }
                }.forEach { deferred ->
                    val result = deferred.await()
                    if (result.isNotEmpty()) {
                        accumulated.addAll(result)
                        emit(accumulated.sortedByDescending { it.stream.isDirectUrl }.toList())
                    }
                }
            }

            // Final emission (even if empty) to signal completion
            if (accumulated.isEmpty()) {
                emit(emptyList())
            } else {
                val finalStreams = accumulated.sortedByDescending { it.stream.isDirectUrl }.toList()
                if (!finalStreams.hasRateLimitError()) {
                    streamCache[cacheKey] = CacheEntry(
                        timestamp = System.currentTimeMillis(),
                        streams = finalStreams
                    )
                    saveCacheToDisk()
                }
            }
        }

    /**
     * Flow-based movie stream resolution for progressive UI display.
     */
    fun resolveMovieStreamsFlow(imdbId: String) = resolveStreamsFlow("movie", imdbId)

    /**
     * Flow-based episode stream resolution for progressive UI display.
     */
    fun resolveEpisodeStreamsFlow(imdbId: String, season: Int, episode: Int) =
        resolveStreamsFlow("series", "$imdbId:$season:$episode")

    /**
     * Fetch streams from a single addon.
     */
    private suspend fun fetchStreamsFromAddon(
        addon: InstalledAddonEntity,
        type: String,
        id: String,
        forcedSource: String? = null
    ): List<ResolvedStream> {
        val cacheKey = "${addon.baseUrl}:$type:$id"
        val cached = streamCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            if (cached.streams.hasRateLimitError()) {
                streamCache.remove(cacheKey)
            } else {
                Log.d(TAG, "Using cached streams for $cacheKey")
                return cached.streams
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                var url = "${addon.baseUrl}/stream/$type/$id.json"
                // If this is the Hub, pass the 'src' parameter to help it resolve correctly
                if (forcedSource != null && (addon.baseUrl.contains(":8080") || addon.supportsPlayEndpoint())) {
                    url += "?src=${android.net.Uri.encode(forcedSource)}"
                }
                Log.d(TAG, "Fetching streams from ${addon.name}: $url")

                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Stream fetch failed from ${addon.name}: ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val streamResponse = json.decodeFromString<StremioStreamResponse>(body)

                val resultStreams = (streamResponse.streams ?: emptyList())
                    .filter { it.isDirectUrl }  // Only include directly playable HTTP streams
                    .map { stream ->
                        ResolvedStream(
                            addonName = addon.name,
                            stream = stream
                        )
                    }

                if (!resultStreams.hasRateLimitError()) {
                    streamCache[cacheKey] = CacheEntry(System.currentTimeMillis(), resultStreams)
                    saveCacheToDisk()
                }

                resultStreams
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching streams from ${addon.name}", e)
                emptyList()
            }
        }
    }

    /**
     * Maps a MAL id to a Kitsu id via Kitsu's public mappings API. Cached in memory.
     * Kitsu addon and Torrentio (Anime) prefer kitsu:ID format for anime streams.
     */
    suspend fun lookupKitsuFromMal(malId: String): String? {
        kitsuCache[malId]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                // Kitsu API for external site mappings
                val url = "https://kitsu.io/api/edge/mappings?filter[externalSite]=myanimelist/anime&filter[externalId]=$malId&include=item"
                Log.d(TAG, "Fetching Kitsu mapping for MAL:$malId: $url")

                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Kitsu mapping fetch failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val mapping = json.decodeFromString<KitsuMappingResponse>(body)

                // The 'include=item' includes the Kitsu anime record in 'included' array
                val kitsuId = mapping.included?.firstOrNull { it.type == "anime" }?.id
                if (kitsuId != null) {
                    Log.d(TAG, "Resolved MAL:$malId -> Kitsu:$kitsuId")
                    kitsuCache[malId] = kitsuId
                    kitsuId
                } else {
                    Log.w(TAG, "No Kitsu anime found in mapping for MAL:$malId")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error looking up Kitsu from MAL:$malId", e)
                null
            }
        }
    }

    // ==================== Helpers ====================

    /**
     * Normalize a URL to a manifest.json URL.
     * Users might paste various formats:
     * - https://torrentio.strem.fun/.../manifest.json (already correct)
     * - https://torrentio.strem.fun/... (needs /manifest.json appended)
     * - https://torrentio.strem.fun/.../  (trailing slash)
     * - stremio://torrentio.strem.fun/.../manifest.json (Stremio deep link)
     * - https://torrentio.strem.fun/.../configure (configuration page URL)
     */
    private fun normalizeManifestUrl(url: String): String {
        var trimmed = url.trim().trimEnd('/')

        // Handle stremio:// deep links — convert to https://
        if (trimmed.startsWith("stremio://")) {
            trimmed = trimmed.replace("stremio://", "https://")
        }

        // Remove /configure suffix (user copied the config page URL instead of manifest)
        if (trimmed.endsWith("/configure")) {
            trimmed = trimmed.removeSuffix("/configure")
        }

        // Ensure it ends with /manifest.json
        val result = if (trimmed.endsWith("/manifest.json") || trimmed.endsWith("manifest.json")) {
            trimmed
        } else {
            "$trimmed/manifest.json"
        }

        Log.d(TAG, "Normalized addon URL: $url -> $result")
        return result
    }

    private fun List<ResolvedStream>.hasRateLimitError(): Boolean {
        return any { resolved ->
            val text = "${resolved.stream.displayName} ${resolved.stream.qualityInfo}".lowercase()
            text.contains("rate-limit") || text.contains("rate limit")
        }
    }
}

/**
 * A stream result tagged with the addon it came from.
 */
@Serializable
data class ResolvedStream(
    val addonName: String,
    val stream: StremioStream
)

@Serializable
private data class KitsuMappingResponse(
    val data: List<KitsuMappingData> = emptyList(),
    val included: List<KitsuMappingIncluded>? = null
)

@Serializable
private data class KitsuMappingData(val id: String, val type: String)

@Serializable
private data class KitsuMappingIncluded(val id: String, val type: String)

/**
 * A resolved stream for a specific episode in a season.
 */
data class EpisodeStream(
    val season: Int,
    val episode: Int,
    val title: String,
    val stream: ResolvedStream
)
