package com.playbridge.player.player

import java.util.Locale

private val GENERIC_TRACK_LABELS = setOf(
    "default",
    "normal",
    "subtitle",
    "subtitles",
)

internal fun buildSubtitleTrackLabel(
    label: String?,
    language: String?,
    fallback: String,
): String {
    val rawLanguage = language
        ?.trim()
        ?.takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) }
    val displayLanguage = rawLanguage
        ?.replace('_', '-')
        ?.let(Locale::forLanguageTag)
        ?.getDisplayLanguage(Locale.ENGLISH)
        ?.takeUnless { it.isBlank() || it.equals(rawLanguage, ignoreCase = true) }
        ?: rawLanguage?.uppercase(Locale.ENGLISH)

    val qualifier = label
        ?.trim()
        ?.takeUnless { it.isEmpty() || it.lowercase(Locale.ENGLISH) in GENERIC_TRACK_LABELS }
        ?.takeUnless {
            it.equals(rawLanguage, ignoreCase = true) ||
                it.equals(displayLanguage, ignoreCase = true)
        }

    return listOfNotNull(displayLanguage, qualifier)
        .joinToString(" • ")
        .ifBlank { fallback }
}
