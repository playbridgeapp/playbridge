package com.playbridge.player.player

import com.playbridge.player.data.PlaybackTrackPreference

internal data class PlaybackTrackCandidate(
    val id: String,
    val label: String,
    val language: String?,
)

internal fun resolveTrackPreference(
    tracks: List<PlaybackTrackCandidate>,
    saved: PlaybackTrackPreference?,
    fallbackLanguage: String? = null,
    excludedIds: Set<String> = emptySet(),
): PlaybackTrackCandidate? {
    val candidates = tracks.filterNot { it.id in excludedIds }
    if (candidates.isEmpty()) return null

    saved?.id?.let { id -> candidates.firstOrNull { it.id == id } }?.let { return it }

    val savedLanguage = normalizeTrackLanguage(saved?.language)
    val savedLabel = normalizeTrackValue(saved?.label)
    if (savedLanguage != null && savedLabel != null) {
        candidates.firstOrNull {
            normalizeTrackLanguage(it.language) == savedLanguage &&
                normalizeTrackValue(it.label) == savedLabel
        }?.let { return it }
    }
    if (savedLanguage != null) {
        candidates.firstOrNull { normalizeTrackLanguage(it.language) == savedLanguage }?.let { return it }
    }
    if (savedLabel != null) {
        candidates.firstOrNull { normalizeTrackValue(it.label) == savedLabel }?.let { return it }
    }

    val normalizedFallback = normalizeTrackLanguage(fallbackLanguage)
    return normalizedFallback?.let { language ->
        candidates.firstOrNull { normalizeTrackLanguage(it.language) == language }
    }
}

private fun normalizeTrackLanguage(value: String?): String? {
    val language = normalizeTrackValue(value)?.substringBefore('-')
    return when (language) {
        "eng" -> "en"
        "jpn" -> "ja"
        "spa" -> "es"
        "fra", "fre" -> "fr"
        "deu", "ger" -> "de"
        "ita" -> "it"
        "por" -> "pt"
        "rus" -> "ru"
        "kor" -> "ko"
        "zho", "chi" -> "zh"
        "ara" -> "ar"
        "hin" -> "hi"
        "und" -> null
        else -> language
    }
}

private fun normalizeTrackValue(value: String?): String? = value
    ?.trim()
    ?.lowercase()
    ?.replace('_', '-')
    ?.replace(Regex("\\s+"), " ")
    ?.takeIf(String::isNotBlank)
