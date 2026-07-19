package com.playbridge.player.player

import com.playbridge.player.data.PlaybackTrackPreference

internal data class PlaybackTrackCandidate(
    val id: String,
    val label: String,
    val language: String?,
)

internal fun hasRestorableTrackCandidates(
    tracks: List<PlaybackTrackCandidate>,
    excludedIds: Set<String>,
): Boolean = tracks.any { it.id !in excludedIds }

internal fun resolveTrackPreference(
    tracks: List<PlaybackTrackCandidate>,
    saved: PlaybackTrackPreference?,
    fallbackLanguage: String? = null,
    excludedIds: Set<String> = emptySet(),
): PlaybackTrackCandidate? {
    val candidates = tracks.filterNot { it.id in excludedIds }
    if (candidates.isEmpty()) return null

    val savedLanguage = normalizeTrackLanguage(saved?.language)
    val savedLabel = normalizeTrackValue(saved?.label)

    // Renderer IDs are usually positional and can be reassigned between episodes,
    // sources, or playback engines. Prefer durable metadata before using an ID as
    // a tie-breaker, and never accept an ID whose available metadata contradicts
    // the saved selection.
    if (savedLanguage != null && savedLabel != null) {
        candidates.firstOrNull {
            normalizeTrackLanguage(it.language) == savedLanguage &&
                normalizeTrackValue(it.label) == savedLabel
        }?.let { return it }
    }

    if (savedLabel != null) {
        candidates.firstOrNull {
            normalizeTrackValue(it.label) == savedLabel &&
                languagesAreCompatible(savedLanguage, normalizeTrackLanguage(it.language))
        }?.let { return it }
    }

    saved?.id?.let { id ->
        candidates.firstOrNull {
            it.id == id && trackMetadataDoesNotConflict(it, savedLanguage, savedLabel)
        }
    }?.let { return it }

    if (savedLanguage != null && savedLabel != null) {
        candidates.firstOrNull {
            normalizeTrackLanguage(it.language) == savedLanguage &&
                normalizeTrackValue(it.label)?.contains(savedLabel) == true
        }?.let { return it }
    }

    if (savedLanguage != null) {
        candidates.firstOrNull { normalizeTrackLanguage(it.language) == savedLanguage }?.let { return it }
    }

    val normalizedFallback = normalizeTrackLanguage(fallbackLanguage)
    return normalizedFallback?.let { language ->
        candidates.firstOrNull { normalizeTrackLanguage(it.language) == language }
    }
}

private fun trackMetadataDoesNotConflict(
    candidate: PlaybackTrackCandidate,
    savedLanguage: String?,
    savedLabel: String?,
): Boolean {
    val candidateLanguage = normalizeTrackLanguage(candidate.language)
    if (!languagesAreCompatible(savedLanguage, candidateLanguage)) return false

    val candidateLabel = normalizeTrackValue(candidate.label)
    return savedLabel == null ||
        candidateLabel == null ||
        candidateLabel == savedLabel ||
        candidateLabel.contains(savedLabel)
}

private fun languagesAreCompatible(saved: String?, candidate: String?): Boolean =
    saved == null || candidate == null || saved == candidate

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
