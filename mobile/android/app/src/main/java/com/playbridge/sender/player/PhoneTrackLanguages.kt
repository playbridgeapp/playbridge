package com.playbridge.sender.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import com.playbridge.sender.cast.normalizeLanguage
import java.util.Locale

/**
 * The audio and subtitle languages the phone player opens with, from the Preferred audio and
 * Preferred subtitle language settings.
 *
 * Subtitles ticked in the cast sheet (or supplied by a website) always win: the first one is on.
 * Otherwise a subtitle in the preferred language is turned on, and without one subtitles stay
 * off; forced subtitles still show. Audio prefers the setting, or the phone's language on Auto,
 * and falls back to the stream's default; Multi keeps the stream's default.
 */
internal data class PhoneTrackLanguages(
    val audio: String?,
    val subtitles: String?,
    /** Ignore a stream's "default" subtitle flag, so only a language match turns subtitles on. */
    val subtitlesOffUnlessPreferred: Boolean,
) {
    fun applyTo(parameters: TrackSelectionParameters): TrackSelectionParameters =
        parameters.buildUpon()
            .setPreferredAudioLanguage(audio)
            .setPreferredTextLanguage(subtitles)
            .setIgnoredTextSelectionFlags(if (subtitlesOffUnlessPreferred) C.SELECTION_FLAG_DEFAULT else 0)
            .build()

    companion object {
        fun of(
            audioSetting: String,
            subtitleSetting: String,
            chosenSubtitles: Boolean,
            deviceLanguage: String? = Locale.getDefault().language,
        ): PhoneTrackLanguages = PhoneTrackLanguages(
            audio = if (audioSetting.trim().equals("multi", ignoreCase = true)) null
                else normalizeLanguage(audioSetting) ?: normalizeLanguage(deviceLanguage),
            subtitles = if (chosenSubtitles) null else normalizeLanguage(subtitleSetting),
            subtitlesOffUnlessPreferred = !chosenSubtitles,
        )
    }
}

/** The spellings a track may tag a language with (`en`, `eng`; `fr`, `fra`, `fre`), for mpv's exact matching. */
internal fun languageSpellings(code: String): List<String> {
    val iso3 = runCatching { Locale(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }
    val bibliographic = BIBLIOGRAPHIC[code]
    return listOfNotNull(code, iso3, bibliographic).distinct()
}

private val BIBLIOGRAPHIC = mapOf(
    "fr" to "fre", "de" to "ger", "nl" to "dut", "zh" to "chi", "cs" to "cze", "el" to "gre", "fa" to "per",
    "ro" to "rum", "sk" to "slo", "sq" to "alb", "hy" to "arm", "is" to "ice", "mk" to "mac", "ms" to "may",
    "cy" to "wel", "eu" to "baq", "ka" to "geo", "my" to "bur",
)
