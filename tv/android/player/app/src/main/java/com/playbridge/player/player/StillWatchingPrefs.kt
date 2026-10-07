package com.playbridge.player.player

/** Still Watching preference keys shared by Settings and the live player. */
object StillWatchingPrefs {
    const val STILL_WATCHING_PREFS = "browser_prefs"
    const val PREF_STILL_WATCHING_ENABLED = "still_watching_enabled"
    const val PREF_STILL_WATCHING_THRESHOLD_MIN = "still_watching_threshold_min"
    const val PREF_STILL_WATCHING_RESPONSE_SEC = "still_watching_response_sec"
    val STILL_WATCHING_PRESETS = setOf(30, 60, 90, 120, 180, 240)
    val STILL_WATCHING_RESPONSE_PRESETS = setOf(30, 60, 120, 300, 600)

    fun normalizeStillWatchingThreshold(value: Int): Int =
        value.takeIf { it in STILL_WATCHING_PRESETS } ?: 90

    fun normalizeStillWatchingResponseSeconds(value: Int): Int =
        value.takeIf { it in STILL_WATCHING_RESPONSE_PRESETS } ?: 300
}
