package com.playbridge.player.stremio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "StremioClient"

// ==================== Response Data Classes ====================

@Serializable
internal data class StremioStreamItem(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
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

data class ResolvedStremioStream(
    val url: String,
    val name: String? = null,
    val title: String? = null
)

data class ScoredStremioStream(
    val url: String,
    val name: String? = null,
    val title: String? = null,
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

object StremioClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).followRedirects(true).build()

    private val cache = mutableMapOf<String, StreamCacheEntry>()
    private var cacheDurationHours: Int = 0
    private var cacheFile: File? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        cacheDurationHours = prefs.getInt("stream_cache_hours", 0)
        cacheFile = File(context.cacheDir, "stremio_streams_cache.json")
        loadCache()
    }

    fun updateCacheDuration(hours: Int) {
        cacheDurationHours = hours
        if (hours <= 0) { cache.clear(); saveCache() }
    }

    fun clearCache(contentId: String, type: String, season: Int? = null, episode: Int? = null) {
        val stremioId = if (type == "series" && season != null && episode != null) "$contentId:$season:$episode" else contentId
        if (cache.remove(stremioId) != null) saveCache()
    }

    fun clearAllCache() { cache.clear(); saveCache() }

    private fun loadCache() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        try {
            val loaded = json.decodeFromString<Map<String, StreamCacheEntry>>(file.readText())
            val now = System.currentTimeMillis()
            cache.putAll(loaded.filter { cacheDurationHours > 0 && (now - it.value.timestamp < cacheDurationHours * 3600 * 1000L) })
        } catch (_: Exception) {}
    }

    private fun saveCache() {
        cacheFile?.let { try { it.writeText(json.encodeToString(cache)) } catch (_: Exception) {} }
    }

    private fun getFromCache(stremioId: String): List<StremioStreamItem>? {
        if (cacheDurationHours <= 0) return null
        val entry = cache[stremioId] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > cacheDurationHours * 3600 * 1000L) {
            cache.remove(stremioId); saveCache(); return null
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
        preferredAddonName: String? = null
    ): List<ScoredStremioStream> {
        val stremioId = if (contentType == "series" && season != null && episode != null) "$contentId:$season:$episode" else contentId
        return resolveStreamsInternal(addonBaseUrls, addonNames, stremioId, contentType, qualityPreference, sourceHint, preferredAddonBaseUrl, preferredAddonName)
    }

    private suspend fun resolveStreamsInternal(
        addonBaseUrls: List<String>,
        addonNames: List<String>? = null,
        stremioId: String,
        contentType: String,
        qualityPreference: String? = null,
        sourceHint: String? = null,
        preferredAddonBaseUrl: String? = null,
        preferredAddonName: String? = null
    ): List<ScoredStremioStream> = withContext(Dispatchers.IO) {
        if (addonBaseUrls.isEmpty()) return@withContext emptyList()
        val cached = getFromCache(stremioId)
        val allStreams = if (cached != null) cached else {
            val fetched = coroutineScope {
                addonBaseUrls.mapIndexed { i, url -> async { fetchFromAddon(url, contentType, stremioId, addonNames?.getOrNull(i)) } }.flatMap { it.await() }
            }
            if (fetched.isNotEmpty()) { cache[stremioId] = StreamCacheEntry(fetched, System.currentTimeMillis()); saveCache() }
            fetched
        }

        val targetRank = QualityRanker.targetRank(qualityPreference)
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
            return score
        }

        allStreams.map { item ->
            val rank = QualityRanker.rankFromText("${item.name.orEmpty()} ${item.title.orEmpty()}")
            ScoredStremioStream(item.url!!, item.name, item.title, item.addonName, score(item), rank, isSeasonPack(item), isExtrasContent(item), targetRank > 0 && rank == targetRank)
        }.sortedByDescending { it.score }
    }

    suspend fun resolveStreams(addonBaseUrls: List<String>, addonNames: List<String>? = null, imdbId: String, season: Int, episode: Int, qualityPref: String? = null, hint: String? = null, prefUrl: String? = null): List<ScoredStremioStream> =
        resolveStreamsByContentId(addonBaseUrls, addonNames, imdbId, "series", season, episode, qualityPref, hint, prefUrl)

    suspend fun resolveEpisode(addonBaseUrls: List<String>, imdbId: String, season: Int, episode: Int, qualityPref: String? = null, hint: String? = null, prefUrl: String? = null): ResolvedStremioStream? = withContext(Dispatchers.IO) {
        val stremioId = "$imdbId:$season:$episode"
        val streams = getFromCache(stremioId) ?: coroutineScope { addonBaseUrls.map { async { fetchFromAddon(it, "series", stremioId) } }.flatMap { it.await() }.also { if (it.isNotEmpty()) { cache[stremioId] = StreamCacheEntry(it, System.currentTimeMillis()); saveCache() } } }
        pickBest(streams, qualityPref, hint)
    }

    private fun fetchFromAddon(baseUrl: String, type: String, id: String, displayName: String? = null): List<StremioStreamItem> {
        val url = "${baseUrl.trimEnd('/')}/stream/$type/$id.json"
        return try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) emptyList()
            else json.decodeFromString<StremioStreamsResponse>(response.body?.string() ?: "").streams?.filter { it.isDirectUrl }?.map { it.copy(addonName = displayName) } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun pickBest(streams: List<StremioStreamItem>, qualityPref: String?, hint: String?): ResolvedStremioStream? {
        val targetRank = QualityRanker.targetRank(qualityPref)
        fun score(s: StremioStreamItem) = QualityRanker.rankFromText("${s.name.orEmpty()} ${s.title.orEmpty()}") * 100 + (if (isSeasonPack(s)) 10 else 0) + sourceMatchScore(s, hint)
        val main = streams.filter { !isExtrasContent(it) }.ifEmpty { streams }
        val tierMatched = if (targetRank > 0) main.filter { QualityRanker.rankFromText("${it.name.orEmpty()} ${it.title.orEmpty()}") == targetRank } else main
        val pool = if (tierMatched.isNotEmpty()) tierMatched else if (targetRank > 0) main.filter { QualityRanker.rankFromText("${it.name.orEmpty()} ${it.title.orEmpty()}") > targetRank }.ifEmpty { main } else main
        return pool.maxByOrNull { score(it) }?.let { ResolvedStremioStream(it.url!!, it.name, it.title) }
    }
}
