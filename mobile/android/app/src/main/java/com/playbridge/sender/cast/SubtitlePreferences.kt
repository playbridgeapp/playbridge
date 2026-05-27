package com.playbridge.sender.cast

object SubtitlePreferences {
    val audioLanguages = listOf(
        "Auto" to "",
        "English" to "en",
        "Spanish" to "es",
        "French" to "fr",
        "German" to "de",
        "Italian" to "it",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Multi" to "multi"
    )

    val subtitleLanguages = listOf(
        "None" to "",
        "English" to "en",
        "Spanish" to "spa",
        "French" to "fre",
        "German" to "ger",
        "Italian" to "ita",
        "Japanese" to "jpn",
        "Korean" to "kor"
    )

    val videoQualities = listOf(
        "Auto" to "Auto",
        "4K / 2160p" to "2160p",
        "1080p" to "1080p",
        "720p" to "720p",
        "480p" to "480p"
    )
}
