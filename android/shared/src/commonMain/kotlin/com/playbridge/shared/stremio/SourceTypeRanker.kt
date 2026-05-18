package com.playbridge.shared.stremio

/**
 * Source/release-type detection for Stremio streams. Mirrors the phone's
 * `SourceTypeFilter` so the TV respects the same set of preferred release types
 * ("bluray", "web-dl", "remux", "webrip", "hdtv", "dvd", "cam") when picking the
 * best stream.
 *
 * The keys are what the phone sends over the wire via
 * `PlayPayload.preferredSourceTypes`
 * and must stay in sync with the phone enum's `SourceTypeFilter.key` values.
 *
 * REMUX is ordered before BLURAY on purpose: a title like "BluRay Remux" classifies
 * as REMUX (more specific), not BLURAY. CAM/TS is last since it's the least desirable.
 */
object SourceTypeRanker {

    /** Ordered pairs of (key, patterns) — first match wins when detecting primary type. */
    private val TYPES: List<Pair<String, List<String>>> = listOf(
        "remux"  to listOf("remux"),
        "bluray" to listOf("bluray", "blu-ray", "bdrip", "brrip", "bd-rip", "br-rip"),
        "web-dl" to listOf("web-dl", "webdl", "web.dl"),
        "webrip" to listOf("webrip", "web-rip", "web.rip"),
        "hdtv"   to listOf("hdtv", "pdtv", "dsr"),
        "dvd"    to listOf("dvdrip", "dvd-rip", "dvdscr", "dvd"),
        "cam"    to listOf("cam", "hdcam", "hdts", "telesync", "telecine", "tc")
    )

    /** Ordered list of (key, human-readable label) pairs for UI pickers. Preference order. */
    val ORDERED_LABELS: List<Pair<String, String>> = listOf(
        "bluray" to "BluRay",
        "remux"  to "Remux",
        "web-dl" to "WEB-DL",
        "webrip" to "WEBRip",
        "hdtv"   to "HDTV",
        "dvd"    to "DVD",
        "cam"    to "CAM / TS"
    )

    /** Label for a given key, or the key itself as a fallback. */
    fun labelOf(key: String): String =
        ORDERED_LABELS.firstOrNull { it.first == key }?.second ?: key

    /**
     * True if the stream's combined name/title/description mentions ANY of the
     * requested source-type keys. Empty [preferredKeys] means "no preference" →
     * always true.
     *
     * Stream titles frequently contain invisible Unicode "format" characters
     * (zero-width joiner U+200D, ZWNJ, ZWSP, BOM, word-joiner, …) inserted to
     * dodge filtering — e.g. "Web‍-‍dl" renders as "Web-dl" but
     * `.contains("web-dl")` sees `web\u200d-\u200ddl` and fails. We strip
     * anything in Unicode category Cf (plus common bidi/whitespace-control
     * codepoints) before matching.
     */
    fun matches(name: String?, title: String?, description: String? = null, preferredKeys: Collection<String>): Boolean {
        if (preferredKeys.isEmpty()) return true
        val text = normalizeForMatching(buildString {
            append(name.orEmpty()); append(' ')
            append(title.orEmpty()); append(' ')
            append(description.orEmpty())
        })
        val normalised = preferredKeys.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalised.isEmpty()) return true
        return normalised.any { key ->
            val patterns = TYPES.firstOrNull { it.first == key }?.second ?: listOf(key)
            patterns.any { text.contains(it) }
        }
    }

    /** Shared regex stripping zero-width / format / bidi characters before matching. */
    private val STREAM_NAME_NOISE_REGEX: Regex =
        Regex("[\\p{Cf}\\u200B-\\u200F\\u2028-\\u202F\\u205F-\\u206F\\uFEFF]")

    internal fun normalizeForMatching(text: String): String =
        text.replace(STREAM_NAME_NOISE_REGEX, "").lowercase()
}
