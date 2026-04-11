package com.playbridge.sender.browser

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
            "2160p" -> UHD
            "1080p" -> FHD
            "720p"  -> HD
            else    -> null
        }
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
        val text = "${stream.stream.name.orEmpty()} ${stream.stream.title.orEmpty()}".lowercase()
        return filter.patterns.any { text.contains(it.lowercase()) }
    }

    /**
     * Estimate Mbps from behaviorHints.videoSize and episode runtime (or 120 min for movies).
     * Returns null when videoSize is not present.
     */
    fun estimatedMbps(stream: ResolvedStream, runtimeMinutes: Int?): Double? {
        val bytes = stream.stream.behaviorHints?.videoSize ?: return null
        val minutes = (runtimeMinutes ?: 120).coerceAtLeast(1)
        return bytes * 8.0 / (minutes * 60 * 1_000_000.0)
    }

    /**
     * Selects the best stream from the list based on quality and bitrate preferences.
     * Returns null if no candidates match the quality tier (allowing fallback to manual UI).
     */
    fun selectBest(
        streams: List<ResolvedStream>,
        preferredQuality: QualityFilter?,
        maxMbps: Double? = null,
        runtimeMinutes: Int? = null
    ): ResolvedStream? {
        if (preferredQuality == null || streams.isEmpty()) return null

        // 1. Filter to the desired quality tier
        var candidates = streams.filter { matchesFilter(it, preferredQuality) }
        if (candidates.isEmpty()) return null // No matches for this quality tier

        // 2. Apply max-bitrate cap if configured
        if (maxMbps != null) {
            val capped = candidates.filter { s ->
                val mbps = estimatedMbps(s, runtimeMinutes)
                mbps == null || mbps <= maxMbps
            }
            if (capped.isNotEmpty()) candidates = capped
        }

        // 3. Return the first (streams are already sorted: direct URLs first)
        return candidates.firstOrNull()
    }
}
