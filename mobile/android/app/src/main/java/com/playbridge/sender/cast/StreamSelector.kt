package com.playbridge.sender.cast

import com.playbridge.sender.data.library.ResolvedStream

/**
 * Quality filter options for streams.
 */
enum class QualityFilter(val label: String, val patterns: List<String>) {
    ALL("All", emptyList()),
    UHD("4K", listOf("2160p", "4k", "uhd", "4K")),
    FHD("1080p", listOf("1080p", "1080")),
    HD("720p", listOf("720p", "720"));

    companion object {
        fun fromKey(key: String): QualityFilter? = when (key) {
            "Auto"  -> ALL
            "2160p" -> UHD
            "1080p" -> FHD
            "720p"  -> HD
            else    -> null
        }
    }
}

/**
 * Source/release-type filter options. Multi-select: a stream matches if any
 * of its name / title / description contains one of the patterns.
 *
 * [key] is the stable string persisted in SharedPreferences and sent over the
 * WebSocket protocol to the TV — keep it lowercase and stable.
 *
 * Order matters: REMUX is checked before BLURAY so that "bluray remux" strings
 * are classified as REMUX, not BLURAY (when detecting a stream's primary type).
 */
enum class SourceTypeFilter(val key: String, val label: String, val patterns: List<String>) {
    REMUX("remux",  "Remux",  listOf("remux")),
    BLURAY("bluray","BluRay", listOf("bluray", "blu-ray", "bdrip", "brrip", "bd-rip", "br-rip")),
    WEBDL("web-dl", "WEB-DL", listOf("web-dl", "webdl", "web.dl")),
    WEBRIP("webrip","WEBRip", listOf("webrip", "web-rip", "web.rip")),
    HDTV("hdtv",    "HDTV",   listOf("hdtv", "pdtv", "dsr")),
    DVD("dvd",      "DVD",    listOf("dvdrip", "dvd-rip", "dvdscr", "dvd")),
    CAM("cam",      "CAM/TS", listOf("cam", "hdcam", "hdts", "telesync", "telecine", "tc"));

    companion object {
        /** Stable order used whenever we render chips or persist selection. */
        val ORDERED: List<SourceTypeFilter> = listOf(REMUX, BLURAY, WEBDL, WEBRIP, HDTV, DVD, CAM)

        fun fromKey(key: String): SourceTypeFilter? =
            ORDERED.firstOrNull { it.key.equals(key, ignoreCase = true) }

        /** Parse a comma-separated persisted string back into a set of filters. */
        fun parseCsv(csv: String?): Set<SourceTypeFilter> =
            csv.orEmpty().split(',').mapNotNull { fromKey(it.trim()) }.toSet()

        /** Serialise a set of filters to the CSV form persisted in SharedPreferences. */
        fun toCsv(selected: Collection<SourceTypeFilter>): String =
            ORDERED.filter { it in selected }.joinToString(",") { it.key }
    }
}

/**
 * Logic for filtering and selecting streams based on user preferences.
 */
object StreamSelector {

    /**
     * Check if a stream matches a quality filter by scanning name + title.
     */
    fun matchesFilter(stream: ResolvedStream, filter: QualityFilter): Boolean {
        if (filter == QualityFilter.ALL) return true
        val text = normalizeForMatching("${stream.stream.name.orEmpty()} ${stream.stream.title.orEmpty()}")
        return filter.patterns.any { text.contains(it.lowercase()) }
    }

    /**
     * True if the stream's name/title/description mentions any of the selected source types.
     * An empty selection means "no preference" → always matches.
     *
     * Stream titles frequently contain invisible Unicode "format" characters (zero-width
     * joiner U+200D, ZWNJ, ZWSP, BOM, word-joiner, …) inserted to dodge filtering —
     * e.g. "Web‍-‍dl" renders as "Web-dl" but `.contains("web-dl")` sees
     * `web\u200d-\u200ddl` and fails. We strip anything in Unicode category Cf
     * (plus common bidi/whitespace-control codepoints) before matching so the
     * obfuscation doesn't bypass our filter.
     */
    fun matchesSourceTypes(stream: ResolvedStream, selected: Set<SourceTypeFilter>): Boolean {
        if (selected.isEmpty()) return true
        val text = normalizeForMatching(buildString {
            append(stream.stream.name.orEmpty()); append(' ')
            append(stream.stream.title.orEmpty()); append(' ')
            append(stream.stream.description.orEmpty())
        })
        return selected.any { filter -> filter.patterns.any { text.contains(it) } }
    }

    /**
     * Strip zero-width / format / bidi characters and lowercase the result.
     * Shared by source-type and (indirectly) quality matching so that obfuscated
     * titles like "1‍0‍8‍0‍p" or "Web‍-‍dl" still match their patterns.
     */
    internal fun normalizeForMatching(text: String): String =
        text.replace(STREAM_NAME_NOISE_REGEX, "").lowercase()

    private val STREAM_NAME_NOISE_REGEX: Regex =
        Regex("[\\p{Cf}\\u200B-\\u200F\\u2028-\\u202F\\u205F-\\u206F\\uFEFF]")

    /**
     * Estimate Mbps from behaviorHints.videoSize and episode runtime (or 120 min for movies).
     * Returns null when videoSize is not present.
     */
    fun estimatedMbps(stream: ResolvedStream, runtimeMinutes: Int?): Double? {
        val bytes = stream.stream.effectiveVideoSizeBytes ?: return null
        val minutes = (runtimeMinutes ?: 120).coerceAtLeast(1)
        return bytes * 8.0 / (minutes * 60 * 1_000_000.0)
    }

    /**
     * Selects the best stream from the list based on quality, bitrate and source-type
     * preferences. Returns null if no candidates match the quality tier (allowing the
     * caller to fall back to the manual picker).
     */
    fun selectBest(
        streams: List<ResolvedStream>,
        preferredQuality: QualityFilter?,
        maxMbps: Double? = null,
        runtimeMinutes: Int? = null,
        preferredAddon: String? = null,
        preferredSourceTypes: Set<SourceTypeFilter> = emptySet()
    ): ResolvedStream? {
        if (preferredQuality == null || streams.isEmpty()) return null

        // If a preferred addon is set, try it first
        if (!preferredAddon.isNullOrBlank()) {
            val addonStreams = streams.filter { it.addonName == preferredAddon }
            if (addonStreams.isNotEmpty()) {
                val best = selectBestFromPool(addonStreams, preferredQuality, maxMbps, runtimeMinutes, preferredSourceTypes)
                if (best != null) return best
                // Preferred addon had streams but none matched quality/bitrate — fall through to full pool
            }
            // Preferred addon had no streams at all — fall through to full pool
        }

        return selectBestFromPool(streams, preferredQuality, maxMbps, runtimeMinutes, preferredSourceTypes)
    }

    /**
     * Internal: applies quality + source-type + bitrate filtering to a pool without addon filtering.
     */
    private fun selectBestFromPool(
        streams: List<ResolvedStream>,
        preferredQuality: QualityFilter,
        maxMbps: Double?,
        runtimeMinutes: Int?,
        preferredSourceTypes: Set<SourceTypeFilter>
    ): ResolvedStream? {
        // 1. Filter to the desired quality tier
        var candidates = streams.filter { matchesFilter(it, preferredQuality) }
        if (candidates.isEmpty()) return null // No matches for this quality tier

        // 2. Filter to the preferred source types (best effort — if nothing matches, keep pool)
        if (preferredSourceTypes.isNotEmpty()) {
            val byType = candidates.filter { matchesSourceTypes(it, preferredSourceTypes) }
            if (byType.isNotEmpty()) candidates = byType
        }

        // 3. Apply max-bitrate cap if configured
        if (maxMbps != null) {
            val capped = candidates.filter { s ->
                val mbps = estimatedMbps(s, runtimeMinutes)
                mbps == null || mbps <= maxMbps
            }
            if (capped.isNotEmpty()) candidates = capped
        }

        // 4. Return the first (streams are already sorted: direct URLs first)
        return candidates.firstOrNull()
    }
}
