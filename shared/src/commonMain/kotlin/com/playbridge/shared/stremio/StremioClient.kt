package com.playbridge.shared.stremio

import com.playbridge.shared.io.Paths
import com.playbridge.shared.logging.logger
import com.playbridge.shared.network.SharedHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.playbridge.shared.io.platformFileSystem
import okio.FileSystem
import okio.Path

private const val TAG = "StremioClient"

// ==================== Response Data Classes ====================

@Serializable
internal data class StremioStreamItem(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val addonName: String? = null,
    val behaviorHints: StremioStreamBehaviorHints? = null
) {
    val isDirectUrl: Boolean get() = url?.startsWith("http") == true
}

@Serializable
internal data class StremioStreamBehaviorHints(
    val videoSize: Long? = null
)

@Serializable
private data class StremioStreamsResponse(
    val streams: List<StremioStreamItem>? = null
)

// ==================== Caching ====================

@Serializable
private data class StreamCacheEntry(
    val streams: List<StremioStreamItem>,
    val timestamp: Long
)

// ==================== Result Types ====================

@Serializable
data class ResolvedStremioStream(
    val url: String,
    val name: String? = null,
    val title: String? = null
)

@Serializable
data class ScoredStremioStream(
    val url: String,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val addonName: String? = null,
    val score: Int,
    val rank: Int,
    val isSeasonPack: Boolean,
    val isExtras: Boolean,
    val isTargetTier: Boolean
)

// ==================== Detection Helpers ====================

private fun isSeasonPack(stream: StremioStreamItem): Boolean {
    val filename = stream.url?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
    val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()} $filename".lowercase()
    if (Regex("""s\d{2}e\d{2}|[._\-\s]e\d{2}[._\-\s]|\d{1,2}x\d{2}""").containsMatchIn(text)) return false
    return Regex("""[._\-\s]s\d{2}[._\-\s]|\bseason\b|\bcomplete\b|\bbatch\b|\bseasons\b""").containsMatchIn(text)
}

private val EXTRAS_KEYWORDS = listOf(
    "extras", "bonus", "featurette", "featurettes", "behind.the.scenes", "behind the scenes",
    "deleted.scenes", "deleted scenes", "making.of", "making of", "debrief", "interview", "interviews",
    "blooper", "bloopers", "gag.reel", "gag reel", "promo", "trailer", "trailers", "special.features", "special features"
)

private fun isExtrasContent(stream: StremioStreamItem): Boolean {
    val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()}".lowercase()
    return EXTRAS_KEYWORDS.any { text.contains(it) }
}

internal fun extractSourceHint(url: String?, name: String?, title: String?): String? {
    val filename = url?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
    val releaseGroup = filename.substringAfterLast('-').uppercase().takeIf { it.length in 2..12 && it.all { c -> c.isLetterOrDigit() } }
    if (releaseGroup != null) return releaseGroup
    val combined = "${name.orEmpty()} ${title.orEmpty()}".uppercase()
    val knownSources = listOf("DSNP", "NF", "AMZN", "HMAX", "ATVP", "PCOK", "HULU", "BCORE")
    return knownSources.firstOrNull { combined.contains(it) }
}

private fun sourceMatchScore(stream: StremioStreamItem, hint: String?): Int {
    if (hint == null) return 0
    val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()} ${stream.url.orEmpty()}".uppercase()
    return if (text.contains(hint.uppercase())) 1 else 0
}

// ==================== Client ====================

class StremioClient(
    private val http: HttpClient = SharedHttpClient.client,
    private val fs: FileSystem = platformFileSystem,
    private val cacheFile: Path = Paths.cacheDir / "stremio_streams_cache.json",
    private val json: Json = SharedHttpClient.json
) {
    companion object {
        private val instance = StremioClient()
        fun init(context: Any? = null) = instance.init(context)
        fun updateCacheDuration(hours: Int) = instance.updateCacheDuration(hours)
        fun clearCache(contentId: String, type: String, season: Int? = null, episode: Int? = null) = instance.clearCache(contentId, type, season, episode)
        fun clearAllCache() = instance.clearAllCache()
        suspend fun resolveStreamsByContentId(addonBaseUrls: List<String>, addonNames: List<String>? = null, contentId: String, contentType: String, season: Int? = null, episode: Int? = null, qualityPreference: String? = null, sourceHint: String? = null, preferredAddonBaseUrl: String? = null, preferredAddonName: String? = null, preferredSourceTypes: List<String>? = null, runtimeMinutes: Int? = null, maxBitrateMbps: Double? = null) = instance.resolveStreamsByContentId(addonBaseUrls, addonNames, contentId, contentType, season, episode, qualityPreference, sourceHint, preferredAddonBaseUrl, preferredAddonName, preferredSourceTypes, runtimeMinutes, maxBitrateMbps)
        suspend fun resolveEpisode(addonBaseUrls: List<String>, addonNames: List<String>? = null, imdbId: String, season: Int, episode: Int, qualityPref: String? = null, hint: String? = null, prefUrl: String? = null, prefName: String? = null, preferredSourceTypes: List<String>? = null, runtimeMinutes: Int? = null, maxBitrateMbps: Double? = null) = instance.resolveEpisode(addonBaseUrls, addonNames, imdbId, season, episode, qualityPref, hint, prefUrl, prefName, preferredSourceTypes, runtimeMinutes, maxBitrateMbps)
    }

    private val cache = mutableMapOf<String, StreamCacheEntry>()
    private var cacheDurationHours: Int = 0

    fun init(context: Any? = null) {
        loadCache()
    }

    fun updateCacheDuration(hours: Int) {
        cacheDurationHours = hours
        if (hours <= 0) {
            cache.clear()
            saveCache()
        }
    }

    fun clearCache(contentId: String, type: String, season: Int? = null, episode: Int? = null) {
        val stremioId = if (type == "series" && season != null && episode != null) "$contentId:$season:$episode" else contentId
        if (cache.remove(stremioId) != null) saveCache()
    }

    fun clearAllCache() {
        cache.clear()
        saveCache()
    }

    private fun loadCache() {
        if (!fs.exists(cacheFile)) return
        try {
            val content = fs.read(cacheFile) { readUtf8() }
            val loaded = json.decodeFromString<Map<String, StreamCacheEntry>>(content)
            val now = currentTimeMillis()
            cache.putAll(loaded.filter { cacheDurationHours > 0 && (now - it.value.timestamp < cacheDurationHours * 3600 * 1000L) })
        } catch (_: Exception) {}
    }

    private fun saveCache() {
        try {
            fs.write(cacheFile) { writeUtf8(json.encodeToString(cache)) }
        } catch (_: Exception) {}
    }

    private fun getFromCache(stremioId: String): List<StremioStreamItem>? {
        if (cacheDurationHours <= 0) return null
        val entry = cache[stremioId] ?: return null
        if (currentTimeMillis() - entry.timestamp > cacheDurationHours * 3600 * 1000L) {
            cache.remove(stremioId)
            saveCache()
            return null
        }
        return entry.streams
    }

    suspend fun resolveStreamsByContentId(
        addonBaseUrls: List<String>,
        addonNames: List<String>? = null,
        contentId: String,
        contentType: String,
        season: Int? = null,
        episode: Int? = null,
        qualityPreference: String? = null,
        sourceHint: String? = null,
        preferredAddonBaseUrl: String? = null,
        preferredAddonName: String? = null,
        preferredSourceTypes: List<String>? = null,
        runtimeMinutes: Int? = null,
        maxBitrateMbps: Double? = null
    ): List<ScoredStremioStream> {
        val stremioId = if (contentType == "series" && season != null && episode != null) "$contentId:$season:$episode" else contentId
        return resolveStreamsInternal(
            addonBaseUrls, addonNames, stremioId, contentType, qualityPreference, sourceHint,
            preferredAddonBaseUrl, preferredAddonName, preferredSourceTypes, runtimeMinutes, maxBitrateMbps
        )
    }

    private suspend fun resolveStreamsInternal(
        addonBaseUrls: List<String>,
        addonNames: List<String>? = null,
        stremioId: String,
        contentType: String,
        qualityPreference: String? = null,
        sourceHint: String? = null,
        preferredAddonBaseUrl: String? = null,
        preferredAddonName: String? = null,
        preferredSourceTypes: List<String>? = null,
        runtimeMinutes: Int? = null,
        maxBitrateMbps: Double? = null
    ): List<ScoredStremioStream> = coroutineScope {
        if (addonBaseUrls.isEmpty()) return@coroutineScope emptyList()
        logger.i(TAG, "Resolving streams for $contentType $stremioId")

        val cached = getFromCache(stremioId)
        val allStreams = if (cached != null) {
            logger.i(TAG, "  Found ${cached.size} streams in cache")
            cached
        } else {
            val fetched = addonBaseUrls.mapIndexed { i, url ->
                async { fetchFromAddon(url, contentType, stremioId, addonNames?.getOrNull(i)) }
            }.flatMap { it.await() }

            if (fetched.isNotEmpty()) {
                logger.i(TAG, "  Fetched ${fetched.size} total streams from ${addonBaseUrls.size} addons")
                cache[stremioId] = StreamCacheEntry(fetched, currentTimeMillis())
                saveCache()
            }
            fetched
        }

        val targetRank = QualityRanker.targetRank(qualityPreference)
        val sourceKeys = preferredSourceTypes.orEmpty()

        fun score(s: StremioStreamItem): Int {
            val rank = QualityRanker.rankFromText("${s.name.orEmpty()} ${s.title.orEmpty()}")
            var score = rank * 100
            if (targetRank > 0) {
                if (rank == targetRank) score += 1000
                else if (rank > targetRank) score -= 500
                else score -= 800
            }
            if (preferredAddonName != null && s.addonName == preferredAddonName) score += 400
            else if (preferredAddonBaseUrl != null && s.url?.startsWith(preferredAddonBaseUrl.trimEnd('/')) == true) score += 400
            if (isExtrasContent(s)) score -= 2000
            if (isSeasonPack(s)) score += 50
            score += sourceMatchScore(s, sourceHint)
            if (sourceKeys.isNotEmpty() && SourceTypeRanker.matches(s.name, s.title, null, sourceKeys)) {
                score += 300
            }
            val mbps = estimateMbps(s, runtimeMinutes)
            if (maxBitrateMbps != null && mbps != null && mbps > maxBitrateMbps) {
                score -= 4000
            }
            return score
        }

        allStreams.map { item ->
            val rank = QualityRanker.rankFromText("${item.name.orEmpty()} ${item.title.orEmpty()}")
            ScoredStremioStream(item.url!!, item.name, item.title, item.description, item.addonName, score(item), rank, isSeasonPack(item), isExtrasContent(item), targetRank > 0 && rank == targetRank)
        }.sortedByDescending { it.score }
    }

    suspend fun resolveEpisode(
        addonBaseUrls: List<String>,
        addonNames: List<String>? = null,
        imdbId: String,
        season: Int,
        episode: Int,
        qualityPref: String? = null,
        hint: String? = null,
        prefUrl: String? = null,
        prefName: String? = null,
        preferredSourceTypes: List<String>? = null,
        runtimeMinutes: Int? = null,
        maxBitrateMbps: Double? = null
    ): ResolvedStremioStream? = coroutineScope {
        val stremioId = "$imdbId:$season:$episode"
        logger.i(TAG, "Resolving episode: $stremioId")

        val cached = getFromCache(stremioId)
        val streams = if (cached != null) {
            logger.i(TAG, "  Found ${cached.size} streams in cache for $stremioId")
            cached
        } else {
            logger.i(TAG, "  Cache miss for $stremioId, fetching from ${addonBaseUrls.size} addons")
            val fetched = addonBaseUrls.mapIndexed { i, url ->
                async { fetchFromAddon(url, "series", stremioId, addonNames?.getOrNull(i)) }
            }.flatMap { it.await() }

            if (fetched.isNotEmpty()) {
                logger.i(TAG, "  Fetched ${fetched.size} total streams for $stremioId")
                cache[stremioId] = StreamCacheEntry(fetched, currentTimeMillis())
                saveCache()
            } else {
                logger.w(TAG, "  No streams found for $stremioId from any addon")
            }
            fetched
        }

        pickBest(streams, qualityPref, hint, season, episode, prefUrl, prefName, preferredSourceTypes, runtimeMinutes, maxBitrateMbps)
    }

    private suspend fun fetchFromAddon(baseUrl: String, type: String, id: String, displayName: String? = null): List<StremioStreamItem> {
        val url = "${baseUrl.trimEnd('/')}/stream/$type/$id.json"
        return try {
            val response: HttpResponse = http.get(url)
            if (!response.status.isSuccess()) {
                logger.w(TAG, "  Addon [${displayName ?: baseUrl}] fetch failed with code ${response.status.value}")
                emptyList()
            } else {
                val body = response.bodyAsText()
                val streams = json.decodeFromString<StremioStreamsResponse>(body).streams
                    ?.filter { it.isDirectUrl }
                    ?.map { it.copy(addonName = displayName) } ?: emptyList()
                logger.i(TAG, "  Addon [${displayName ?: baseUrl}] returned ${streams.size} streams")
                streams
            }
        } catch (e: Exception) {
            logger.e(TAG, "  Addon [${displayName ?: baseUrl}] fetch error: ${e.message}")
            emptyList()
        }
    }

    private fun pickBest(
        streams: List<StremioStreamItem>,
        qualityPref: String?,
        hint: String?,
        targetSeason: Int? = null,
        targetEpisode: Int? = null,
        prefUrl: String? = null,
        prefName: String? = null,
        preferredSourceTypes: List<String>? = null,
        runtimeMinutes: Int? = null,
        maxBitrateMbps: Double? = null
    ): ResolvedStremioStream? {
        val targetRank = QualityRanker.targetRank(qualityPref)
        val sourceKeys = preferredSourceTypes.orEmpty()

        fun score(s: StremioStreamItem): Int {
            val text = "${s.name.orEmpty()} ${s.title.orEmpty()}".lowercase()
            var score = QualityRanker.rankFromText(text) * 100

            if (targetSeason != null && targetEpisode != null) {
                val sRegex = Regex("""s(\d{1,2})""", RegexOption.IGNORE_CASE)
                val eRegex = Regex("""e(\d{1,3})""", RegexOption.IGNORE_CASE)

                val foundSeasons = sRegex.findAll(text).map { it.groupValues[1].toInt() }.toList()
                if (foundSeasons.isNotEmpty() && !foundSeasons.contains(targetSeason)) {
                    score -= 5000
                }

                val foundEpisodes = eRegex.findAll(text).map { it.groupValues[1].toInt() }.toList()
                if (foundEpisodes.isNotEmpty() && !foundEpisodes.contains(targetEpisode)) {
                    score -= 5000
                }
            }

            if (isSeasonPack(s)) score += 10
            score += sourceMatchScore(s, hint)

            if (prefName != null && s.addonName == prefName) score += 400
            else if (prefUrl != null && s.url?.startsWith(prefUrl.trimEnd('/')) == true) score += 400

            if (sourceKeys.isNotEmpty() && SourceTypeRanker.matches(s.name, s.title, null, sourceKeys)) {
                score += 300
            }

            val mbps = estimateMbps(s, runtimeMinutes)
            if (maxBitrateMbps != null && mbps != null && mbps > maxBitrateMbps) {
                score -= 4000
            }

            return score
        }

        val main = streams.filter { !isExtrasContent(it) }.ifEmpty { streams }
        val tierMatched = if (targetRank > 0) main.filter { QualityRanker.rankFromText("${it.name.orEmpty()} ${it.title.orEmpty()}") == targetRank } else main
        val qualityPool = if (tierMatched.isNotEmpty()) tierMatched else if (targetRank > 0) main.filter { QualityRanker.rankFromText("${it.name.orEmpty()} ${it.title.orEmpty()}") > targetRank }.ifEmpty { main } else main

        val sourcePool = if (sourceKeys.isNotEmpty()) {
            qualityPool.filter { SourceTypeRanker.matches(it.name, it.title, null, sourceKeys) }.ifEmpty { qualityPool }
        } else {
            qualityPool
        }

        val pool = if (maxBitrateMbps != null && runtimeMinutes != null) {
            sourcePool.filter {
                val mbps = estimateMbps(it, runtimeMinutes)
                mbps == null || mbps <= maxBitrateMbps
            }.ifEmpty { sourcePool }
        } else {
            sourcePool
        }

        val picked = pool.maxByOrNull { score(it) }
        if (picked != null) {
            logger.i(TAG, "  Best stream picked: ${picked.name ?: picked.title} (targetRank: $targetRank, qualityPref: $qualityPref, hint: $hint, score: ${score(picked)})")
        } else if (streams.isNotEmpty()) {
            logger.w(TAG, "  No stream picked despite having ${streams.size} candidates (pool size: ${pool.size})")
        }

        return picked?.let { ResolvedStremioStream(it.url!!, it.name, it.title) }
    }

    private fun estimateMbps(s: StremioStreamItem, runtimeMinutes: Int?): Double? {
        val bytes = s.behaviorHints?.videoSize ?: return null
        if (bytes <= 0) return null
        val runtime = runtimeMinutes ?: return null
        if (runtime <= 0) return null
        return (bytes * 8.0) / (runtime * 60.0 * 1_000_000.0)
    }
}

expect fun currentTimeMillis(): Long
