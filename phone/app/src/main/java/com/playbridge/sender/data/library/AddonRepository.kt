package com.playbridge.sender.data.library

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Manages Stremio-compatible addons: installing from manifest URLs,
 * resolving streams via the Stremio addon protocol.
 */
class AddonRepository(private val addonDao: AddonDao) {

    companion object {
        private const val TAG = "AddonRepository"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 60 minutes
    }

    private data class CacheEntry(
        val timestamp: Long,
        val streams: List<ResolvedStream>
    )

    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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

                val entity = InstalledAddonEntity(
                    manifestUrl = manifestUrl,
                    name = manifest.name.ifBlank { "Unknown Addon" },
                    description = manifest.description,
                    baseUrl = baseUrl,
                    version = manifest.version,
                    types = manifest.types.joinToString(",")
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
     * Internal: Resolve streams from all installed addons in parallel.
     * Returns a complete list (used by season play).
     */
    private suspend fun resolveStreams(type: String, id: String): List<ResolvedStream> {
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

        val addons = addonDao.getAllSync()
        if (addons.isEmpty()) {
            Log.d(TAG, "No addons installed")
            return emptyList()
        }

        return coroutineScope {
            val results = addons.map { addon ->
                async(Dispatchers.IO) {
                    fetchStreamsFromAddon(addon, type, id)
                }
            }.awaitAll()

            val finalStreams = results.flatten().sortedByDescending { it.stream.isDirectUrl }
            if (!finalStreams.hasRateLimitError()) {
                streamCache[cacheKey] = CacheEntry(System.currentTimeMillis(), finalStreams)
            }
            finalStreams
        }
    }

    /**
     * Resolve streams incrementally — emits results from each addon as soon as it completes.
     * This allows the UI to show streams progressively.
     */
    fun resolveStreamsFlow(type: String, id: String): kotlinx.coroutines.flow.Flow<List<ResolvedStream>> =
        kotlinx.coroutines.flow.flow {
            val cacheKey = "$type:$id"
            val cached = streamCache[cacheKey]
            if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
                if (cached.streams.hasRateLimitError()) {
                    streamCache.remove(cacheKey)
                } else {
                    Log.d(TAG, "Using cached streams for $cacheKey flow")
                    emit(cached.streams)
                    return@flow
                }
            }

            val addons = addonDao.getAllSync()
            if (addons.isEmpty()) {
                Log.d(TAG, "No addons installed")
                emit(emptyList())
                return@flow
            }

            val accumulated = mutableListOf<ResolvedStream>()

            coroutineScope {
                addons.map { addon ->
                    async(Dispatchers.IO) {
                        fetchStreamsFromAddon(addon, type, id)
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
        id: String
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
                val url = "${addon.baseUrl}/stream/$type/$id.json"
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
                }

                resultStreams
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching streams from ${addon.name}", e)
                emptyList()
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
data class ResolvedStream(
    val addonName: String,
    val stream: StremioStream
)

/**
 * A resolved stream for a specific episode in a season.
 */
data class EpisodeStream(
    val season: Int,
    val episode: Int,
    val title: String,
    val stream: ResolvedStream
)
