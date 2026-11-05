package com.playbridge.sender.cast

import java.util.Locale

/**
 * A language code as a two-letter ISO 639-1 code where one exists. Settings store subtitles as
 * ISO 639-2 (`spa`, `fre`) and audio as 639-1 (`es`); tracks use either. Blank, `und` and the
 * `multi` setting mean no particular language.
 */
internal fun normalizeLanguage(code: String?): String? {
    val primary = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
        ?.takeIf { it.isNotEmpty() && it != "und" && it != "multi" } ?: return null
    return LANGUAGE_CODES[primary] ?: primary
}

/** Two- and three-letter language codes (including bibliographic 639-2), each mapped to its 639-1 code. */
private val LANGUAGE_CODES: Map<String, String> by lazy {
    buildMap {
        for (code in Locale.getISOLanguages()) {
            put(code, code)
            runCatching { Locale(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { put(it, code) }
        }
        // Bibliographic 639-2 codes, common in settings and subtitle file names.
        mapOf("fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el",
            "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "ice" to "is",
            "mac" to "mk", "may" to "ms", "wel" to "cy", "baq" to "eu", "geo" to "ka", "bur" to "my")
            .forEach { (word, code) -> put(word, code) }
    }
}

/** [LANGUAGE_CODES] plus English language names (`english`, `spanish`). */
private val LANGUAGE_WORDS: Map<String, String> by lazy {
    LANGUAGE_CODES + Locale.getISOLanguages().mapNotNull { code ->
        Locale(code).getDisplayLanguage(Locale.ENGLISH).lowercase().takeIf { it.length > 3 && it != code }?.let { it to code }
    }
}

/**
 * The language of a detected subtitle: what language detection found in its dialogue, otherwise
 * a language named in its title (`English`, `eng`) or at the end of its file name (`movie.en.vtt`).
 */
internal fun subtitleLanguageHint(subtitle: DetectedVideo): String? =
    normalizeLanguage(subtitle.subtitleLanguageCode)
        ?: languageInTitle(subtitle.title)
        ?: languageInFileName(subtitle.url)

/** A full language name or three-letter code in a title; two letters only when that's the whole title. */
internal fun languageInTitle(title: String?): String? {
    val words = title?.lowercase()?.split(Regex("[^\\p{L}]+"))?.filter { it.isNotEmpty() } ?: return null
    if (words.size == 1 && words[0].length == 2) return LANGUAGE_WORDS[words[0]]
    return words.firstNotNullOfOrNull { word -> LANGUAGE_WORDS[word]?.takeIf { word.length >= 3 } }
}

/** The last word of a file name (`movie.en`, `show_eng`) or a full language name anywhere in it. */
internal fun languageInFileName(url: String): String? {
    val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringBeforeLast('.')
    val words = name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    val last = words.lastOrNull()?.takeIf { words.size > 1 && it.length in 2..3 }
    return last?.let(LANGUAGE_WORDS::get)
        ?: words.firstNotNullOfOrNull { word -> LANGUAGE_WORDS[word]?.takeIf { word.length > 3 } }
}

private val SUBTITLE_VARIANT = Regex("(^|[^\\p{L}])(forced|sdh|cc|hearing)([^\\p{L}]|$)", RegexOption.IGNORE_CASE)

/** Forced-only and hearing-impaired versions; the plain one is preferred when both exist. */
private fun isSubtitleVariant(subtitle: DetectedVideo): Boolean =
    SUBTITLE_VARIANT.containsMatchIn(subtitle.title.orEmpty()) ||
        SUBTITLE_VARIANT.containsMatchIn(subtitle.url.substringBefore('?').substringAfterLast('/'))

/**
 * Subtitles for the cast sheet: the preferred language first, then the phone's language, then
 * the rest, newest first within each group.
 */
internal fun rankSubtitles(subtitles: List<DetectedVideo>, preferred: String?, device: String?): List<DetectedVideo> {
    val preferredCode = normalizeLanguage(preferred)
    val deviceCode = normalizeLanguage(device)
    return newestSubtitlesFirst(subtitles).sortedBy { subtitle ->
        val language = subtitleLanguageHint(subtitle)
        when {
            preferredCode != null && language == preferredCode -> 0
            deviceCode != null && language == deviceCode -> 1
            else -> 2
        }
    }
}

/** The subtitle to tick for the preferred language: the plain version over forced or SDH ones. */
internal fun preferredSubtitlePick(ranked: List<DetectedVideo>, preferred: String?): DetectedVideo? {
    val code = normalizeLanguage(preferred) ?: return null
    val matches = ranked.filter { subtitleLanguageHint(it) == code }
    return matches.firstOrNull { !isSubtitleVariant(it) } ?: matches.firstOrNull()
}
