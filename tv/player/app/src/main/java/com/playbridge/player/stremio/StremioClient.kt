package com.playbridge.player.stremio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "StremioClient"

// ==================== Response Data Classes ====================

/**
 * Minimal Stremio stream item — only the fields the TV needs for playback and quality selection.
 * Mirrors the shape of StremioStream in the phone's AddonRepository but is local to the TV
 * to avoid cross-module coupling.
 */
@Serializable
private data class StremioStreamItem(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val behaviorHints: StremioStreamBehaviorHints? = null
) {
    val isDirectUrl: Boolean get() = url?.startsWith("http") == true
}

@Serializable
private data class StremioStreamBehaviorHints(
    val videoSize: Long? = null
)

@Serializable
private data class StremioStreamsResponse(
    val streams: List<StremioStreamItem>? = null
)

// ==================== Result Type ====================

/**
 * A successfully resolved stream URL, ready to hand to ExoPlayer.
 */
data class ResolvedStremioStream(
    val url: String,
    val name: String? = null,
    val title: String? = null
)

/**
 * A stream candidate with its associated score and ranking metadata.
 * Returned by [resolveStreams] to allow for manual selection.
 */
data class ScoredStremioStream(
    val url: String,
    val name: String? = null,
    val title: String? = null,
    val score: Int,
    val rank: Int,
    val isSeasonPack: Boolean,
    val isExtras: Boolean,
    val isTargetTier: Boolean
)

// ==================== Quality Ranking ====================

/**
 * Quality tier rank — mirrors the phone's QualityFilter patterns so both sides
 * apply the same preference. Higher rank = preferred.
 *
 * Key values match PlayPayload.defaultVideoQuality strings:
 *   "2160p", "1080p", "720p", or null / anything else → best available.
 */
private object QualityRanker {

    private val UHD_PATTERNS  = listOf("2160p", "4k", "uhd")
    private val FHD_PATTERNS  = listOf("1080p", "1080")
    private val HD_PATTERNS   = listOf("720p", "720")

    /** Returns 4, 3, 2, or 1 for UHD / FHD / HD / SD respectively. */
    fun rank(stream: StremioStreamItem): Int {
        val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()}".lowercase()
        return when {
            UHD_PATTERNS.any { text.contains(it) } -> 4
            FHD_PATTERNS.any { text.contains(it) } -> 3
            HD_PATTERNS.any  { text.contains(it) } -> 2
            else                                    -> 1
        }
    }

    /**
     * Returns the target rank for the given quality preference key.
     * Used for exact-tier matching: the TV mirrors the phone's StreamSelector which
     * filters to streams that explicitly carry the preferred quality label — a "1080p"
     * preference does NOT accept 4K streams, just as the phone wouldn't auto-select them.
     *
     * Returns 0 for null / "auto" so callers can detect "no preference" and skip
     * tier filtering entirely (accept all ranks, let score pick the best available).
     */
    fun targetRank(qualityPreference: String?): Int = when (qualityPreference?.lowercase()) {
        "2160p", "4k", "uhd" -> 4
        "1080p", "1080"      -> 3
        "720p",  "720"       -> 2
        else                 -> 0 // null / "auto" → no preference, skip tier filter
    }
}

// ==================== Season Pack Detection ====================

/**
 * Returns true if the stream appears to be a season/complete pack rather than a
 * single-episode release. Season packs are preferred because they are more
 * reliably seeded and cached on Debrid services.
 *
 * Detection heuristic: the combined name + title + URL filename does NOT contain
 * an explicit episode indicator (S01E02, 1x02, etc.) but DOES contain a season
 * or completion marker (S01. complete, batch, etc.).
 */
private fun isSeasonPack(stream: StremioStreamItem): Boolean {
    val filename = stream.url?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
    val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()} $filename".lowercase()

    // An explicit episode indicator → definitely a single-episode release
    if (Regex("""s\d{2}e\d{2}|[._\-\s]e\d{2}[._\-\s]|\d{1,2}x\d{2}""").containsMatchIn(text)) return false

    // Season or completion marker without an episode → season pack
    return Regex("""[._\-\s]s\d{2}[._\-\s]|\bseason\b|\bcomplete\b|\bbatch\b|\bseasons\b""").containsMatchIn(text)
}

// ==================== Extras / Bonus Content Detection ====================

/**
 * Keywords that identify bonus/extras content — featurettes, interview reels, debriefs,
 * behind-the-scenes packs, etc. These are NOT main episode streams and must be excluded
 * from the primary scoring pool regardless of their quality rank.
 *
 * Note: quality ranks can be misleading for extras packs because the title string often
 * contains the quality of the pack it belongs to (e.g. "S01.Extras.2160p") rather than
 * the actual episode quality.
 */
private val EXTRAS_KEYWORDS = listOf(
    "extras", "bonus", "featurette", "featurettes",
    "behind.the.scenes", "behind the scenes",
    "deleted.scenes", "deleted scenes",
    "making.of", "making of",
    "debrief", "interview", "interviews",
    "blooper", "bloopers", "gag.reel", "gag reel",
    "promo", "trailer", "trailers",
    "special.features", "special features"
)

/**
 * Returns true if the stream is bonus/supplemental content (extras pack, featurette reel,
 * interview, etc.) rather than a main episode. Extras are always deprioritised: they are
 * excluded from the primary scoring pool and only considered as a last resort when no
 * regular episode streams are available.
 *
 * Torrentio embeds the torrent/pack name in [StremioStreamItem.title] before the '/' that
 * separates it from the specific file path, so a title like:
 *   "The.Last.of.Us.S01.Extras.1080p.MeM.GP/The.Last.of.Us.Debrief.4.Finale.2.mkv"
 * is reliably caught by checking the full title text for extras keywords.
 */
private fun isExtrasContent(stream: StremioStreamItem): Boolean {
    val text = "${stream.name.orEmpty()} ${stream.title.orEmpty()}".lowercase()
    return EXTRAS_KEYWORDS.any { text.contains(it) }
}

// ==================== Source Hint Matching ====================

/**
 * Extracts a short release-group / source hint from a stream's URL, name, or title.
 *
 * Priority:
 *  1. Release group from the URL filename — the token after the last '-' before the
 *     extension, e.g. "...H.264-FLUX.mkv" → "FLUX". Most reliable for Torrentio/AIOStreams.
 *  2. First significant uppercase token in the stream name (e.g. "NF", "DSNP", "AMZN").
 *
 * Returns null when nothing useful can be extracted.
 */
internal fun extractSourceHint(url: String?, name: String?, title: String?): String? {
    // 1. Release group from URL filename
    val filename = url?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
    val releaseGroup = filename.substringAfterLast('-')
        .uppercase()
        .takeIf { it.length in 2..12 && it.all { c -> c.isLetterOrDigit() } }
    if (releaseGroup != null) return releaseGroup

    // 2. Known streaming source tokens in name/title as fallback
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

/**
 * Minimal, stateless Stremio addon HTTP client for the TV player.
 *
 * Responsibilities:
 *   - Call each addon base URL in parallel to fetch stream candidates
 *   - Filter to direct HTTP URLs only
 *   - Apply quality preference (mirrors phone's StreamSelector.selectBest logic)
 *   - Return the best single stream, or null if nothing resolved
 *
 * No caching, no Room DB, no addon management — those stay on the phone.
 * Addon base URLs (including any embedded auth tokens) come from SeriesContext.addonBaseUrls.
 */
object StremioClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Resolve streams for a series episode across all provided addon base URLs.
     *
     * Calls all addons in parallel and picks the best direct HTTP stream using a
     * composite score: quality rank (dominant) → season-pack bonus → source hint match.
     *
     * @param addonBaseUrls      Addon base URLs from SeriesContext (auth tokens baked in)
     * @param imdbId             e.g. "tt1234567"
     * @param season             Season number (1-based)
     * @param episode            Episode number (1-based)
     * @param qualityPreference  "2160p", "1080p", "720p", or null for best available
     * @param sourceHint         Release group / source token from the previous episode
     *                           (e.g. "FLUX", "NF"). Used to prefer the same torrent
     *                           source across consecutive episodes. Null = no preference.
     * @param preferredAddonBaseUrl Base URL of the preferred addon to try first.
     * @return Best matching stream, or null if all addons fail or return empty
     */
    /**
     * Resolve all direct HTTP streams for a series episode across all provided addons.
     * Unlike [resolveEpisode], this returns the full list of candidates (scored and sorted)
     * so the user can manually pick an alternative if the "best" match is poor.
     */
    suspend fun resolveStreams(
        addonBaseUrls: List<String>,
        imdbId: String,
        season: Int,
        episode: Int,
        qualityPreference: String? = null,
        sourceHint: String? = null,
        preferredAddonBaseUrl: String? = null
    ): List<ScoredStremioStream> = withContext(Dispatchers.IO) {
        if (addonBaseUrls.isEmpty()) return@withContext emptyList()

        val stremioId = "$imdbId:$season:$episode"

        // Fetch from all addons in parallel
        val allStreams: List<StremioStreamItem> = coroutineScope {
            addonBaseUrls.map { baseUrl ->
                async { fetchFromAddon(baseUrl, stremioId) }
            }.flatMap { it.await() }
        }

        if (allStreams.isEmpty()) return@withContext emptyList()

        val targetRank = QualityRanker.targetRank(qualityPreference)
        fun score(s: StremioStreamItem): Int =
            QualityRanker.rank(s) * 100 +
            (if (isSeasonPack(s)) 10 else 0) +
            sourceMatchScore(s, sourceHint)

        return@withContext allStreams
            .map { item ->
                ScoredStremioStream(
                    url = item.url!!,
                    name = item.name,
                    title = item.title,
                    score = score(item),
                    rank = QualityRanker.rank(item),
                    isSeasonPack = isSeasonPack(item),
                    isExtras = isExtrasContent(item),
                    isTargetTier = targetRank > 0 && QualityRanker.rank(item) == targetRank
                )
            }
            .sortedByDescending { it.score }
    }

    suspend fun resolveEpisode(
        addonBaseUrls: List<String>,
        imdbId: String,
        season: Int,
        episode: Int,
        qualityPreference: String? = null,
        sourceHint: String? = null,
        preferredAddonBaseUrl: String? = null
    ): ResolvedStremioStream? = withContext(Dispatchers.IO) {
        if (addonBaseUrls.isEmpty()) {
            Log.w(TAG, "resolveEpisode called with no addon URLs")
            return@withContext null
        }

        // Stremio stream ID for series: "{imdbId}:{season}:{episode}"
        val stremioId = "$imdbId:$season:$episode"
        Log.d(TAG, "Resolving streams for $stremioId via ${addonBaseUrls.size} addon(s)")

        // If a preferred addon is specified, try it in isolation first.
        // Only fall back to all addons when the preferred one returns nothing.
        if (!preferredAddonBaseUrl.isNullOrBlank() && preferredAddonBaseUrl in addonBaseUrls) {
            Log.d(TAG, "Trying preferred addon first: $preferredAddonBaseUrl")
            val preferredStreams = fetchFromAddon(preferredAddonBaseUrl, stremioId)
            if (preferredStreams.isNotEmpty()) {
                Log.d(TAG, "Preferred addon returned ${preferredStreams.size} stream(s); skipping other addons")
                val result = pickBest(preferredStreams, qualityPreference, sourceHint)
                if (result != null) return@withContext result
                Log.d(TAG, "No qualifying stream from preferred addon; falling back to all addons")
            }
        }

        // Fetch from all addons in parallel, collect all direct-URL streams
        val allStreams: List<StremioStreamItem> = coroutineScope {
            addonBaseUrls.map { baseUrl ->
                async {
                    fetchFromAddon(baseUrl, stremioId)
                }
            }.flatMap { it.await() }
        }

        if (allStreams.isEmpty()) {
            Log.d(TAG, "No streams resolved for $stremioId")
            return@withContext null
        }

        Log.d(TAG, "Got ${allStreams.size} stream candidate(s) for $stremioId")
        pickBest(allStreams, qualityPreference, sourceHint)
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Fetch streams from a single addon base URL.
     * Returns only direct HTTP streams; silently returns empty list on any error.
     */
    private fun fetchFromAddon(baseUrl: String, stremioId: String): List<StremioStreamItem> {
        val url = "${baseUrl.trimEnd('/')}/stream/series/$stremioId.json"
        return try {
            Log.d(TAG, "Fetching: $url")
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Addon returned ${response.code} for $url")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val parsed = json.decodeFromString<StremioStreamsResponse>(body)

            (parsed.streams ?: emptyList())
                .filter { it.isDirectUrl }
                .also { Log.d(TAG, "  → ${it.size} direct stream(s) from $baseUrl") }

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from $baseUrl: ${e.message}")
            emptyList()
        }
    }

    /**
     * Composite stream scoring within [streams]:
     *
     *   qualityRank × 100   — dominant: never cross a quality tier for any other reason
     *   + seasonPackBonus × 10  — within same quality tier, season/complete packs first
     *   + sourceMatchBonus × 1 — fine-grained tiebreaker: prefer same release group
     *
     * Extras/bonus content (featurettes, debriefs, interview reels, etc.) is always
     * excluded from the primary pool and only used as a last resort when no regular
     * episode streams are available at all.
     *
     * If [qualityPreference] is set, streams below the target rank are tried only as a
     * last resort (no qualifying streams at that tier).
     */
    private fun pickBest(
        streams: List<StremioStreamItem>,
        qualityPreference: String?,
        sourceHint: String? = null
    ): ResolvedStremioStream? {
        val targetRank = QualityRanker.targetRank(qualityPreference)

        fun score(s: StremioStreamItem): Int =
            QualityRanker.rank(s) * 100 +
            (if (isSeasonPack(s)) 10 else 0) +
            sourceMatchScore(s, sourceHint)

        // 1. Strip extras/bonus content — these are never correct for a main episode.
        //    Extras packs can poison quality ranking (their title embeds the pack quality,
        //    e.g. "S01.Extras.2160p", which inflates the rank) and will never play the
        //    requested episode correctly.
        val mainStreams = streams.filter { !isExtrasContent(it) }
        val episodePool = mainStreams.ifEmpty {
            // Every stream was flagged as extras — last resort: use everything
            Log.w(TAG, "All ${streams.size} candidate(s) appear to be extras content; using full pool as fallback")
            streams
        }

        // 2. Exact-tier matching — mirrors the phone's StreamSelector which filters to
        //    streams that explicitly carry the preferred quality label.
        //    "1080p" preference → only rank-3 candidates; 4K streams are excluded.
        //    targetRank == 0 means "auto / no preference" → skip tier filter, let score pick.
        val tierMatched = if (targetRank > 0) {
            episodePool.filter { QualityRanker.rank(it) == targetRank }
        } else {
            episodePool
        }

        // 3. Fallback chain when no stream matches the target tier:
        //    a) try the next tier up (e.g. only 4K available when 1080p preferred)
        //    b) then any tier (e.g. only SD available)
        val pool = when {
            tierMatched.isNotEmpty() -> tierMatched
            targetRank > 0 -> {
                val higherOrLower = episodePool.filter { QualityRanker.rank(it) > targetRank }
                    .ifEmpty { episodePool }
                Log.d(TAG, "No rank-$targetRank streams found; falling back to ${higherOrLower.size} other candidate(s)")
                higherOrLower
            }
            else -> episodePool
        }

        val chosen = pool.maxByOrNull { score(it) } ?: return null

        Log.d(TAG, "Picked stream: rank=${QualityRanker.rank(chosen)} " +
            "targetRank=$targetRank " +
            "seasonPack=${isSeasonPack(chosen)} " +
            "extras=${isExtrasContent(chosen)} " +
            "sourceMatch=${sourceMatchScore(chosen, sourceHint) > 0} (hint=$sourceHint) " +
            "name=${chosen.name} title=${chosen.title}")

        return ResolvedStremioStream(
            url = chosen.url!!,
            name = chosen.name,
            title = chosen.title
        )
    }
}
