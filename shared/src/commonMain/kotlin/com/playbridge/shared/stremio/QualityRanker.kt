package com.playbridge.shared.stremio

/**
 * Quality tier rank — mirrors the phone's QualityFilter patterns so both sides
 * apply the same preference. Higher rank = preferred.
 *
 * Key values match PlayPayload.defaultVideoQuality strings:
 *   "2160p", "1080p", "720p", or null / anything else → best available.
 */
object QualityRanker {

    private val UHD_PATTERNS  = listOf("2160p", "4k", "uhd")
    private val FHD_PATTERNS  = listOf("1080p", "1080")
    private val HD_PATTERNS   = listOf("720p", "720")

    /** Returns 4, 3, 2, or 1 for UHD / FHD / HD / SD respectively based on name/title text. */
    fun rankFromText(text: String): Int {
        // Strip zero-width / format / bidi obfuscation the same way SourceTypeRanker does,
        // so titles like "1‍0‍8‍0‍p" still classify correctly.
        val lower = SourceTypeRanker.normalizeForMatching(text)
        return when {
            UHD_PATTERNS.any { lower.contains(it) } -> 4
            FHD_PATTERNS.any { lower.contains(it) } -> 3
            HD_PATTERNS.any  { lower.contains(it) } -> 2
            else                                    -> 1
        }
    }

    /**
     * Returns the target rank for the given quality preference key.
     * Returns 0 for null / "auto" so callers can detect "no preference".
     */
    fun targetRank(qualityPreference: String?): Int = when (qualityPreference?.lowercase()) {
        "2160p", "4k", "uhd" -> 4
        "1080p", "1080"      -> 3
        "720p",  "720"       -> 2
        else                 -> 0 // null / "auto" → no preference
    }
}
