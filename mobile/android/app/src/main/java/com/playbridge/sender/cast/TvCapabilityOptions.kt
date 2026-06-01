package com.playbridge.sender.cast

import com.playbridge.sender.model.TvDevice

/**
 * Single source of truth for the player/browser picker options shown across the phone UI.
 *
 * Options are derived from what the selected TV reported at auth ([TvDevice.players] /
 * [TvDevice.browsers]) so the UI only ever offers what that specific TV can actually drive.
 * "TV Default" (id [TV_DEFAULT]) is always offered and means "let the TV use its own
 * configured default". When the TV hasn't reported yet (not connected), only TV Default is
 * shown.
 */
object TvCapabilityOptions {
    const val TV_DEFAULT = "tv"

    // One canonical id per engine, shared across every receiver (Android TV, desktop, Apple TV).
    // Insertion order defines display order in the picker.
    private val PLAYER_LABELS = linkedMapOf(
        "exo" to "ExoPlayer",
        "mpv" to "MPV",
        "avplayer" to "AVPlayer",
        "vlc" to "VLC",
    )

    private val BROWSER_LABELS = linkedMapOf(
        "webview" to "System WebView",
        "gecko" to "GeckoView",
    )

    /** Player picker options: TV Default first, then the TV's reported players (known ids only). */
    fun playerOptions(device: TvDevice?): List<Pair<String, String>> =
        buildOptions(device?.players.orEmpty(), PLAYER_LABELS)

    /** Browser picker options: TV Default first, then the TV's reported browser engines. */
    fun browserOptions(device: TvDevice?): List<Pair<String, String>> =
        buildOptions(device?.browsers.orEmpty(), BROWSER_LABELS)

    private fun buildOptions(
        reported: List<String>,
        labels: Map<String, String>,
    ): List<Pair<String, String>> {
        val options = mutableListOf(TV_DEFAULT to "TV Default")
        val seenLabels = HashSet<String>()
        for (id in reported) {
            val label = labels[id] ?: continue              // skip ids this phone build doesn't know
            if (seenLabels.add(label)) options.add(id to label)  // guard against a TV repeating a label
        }
        return options
    }

    /**
     * Coerce a persisted/selected id to [TV_DEFAULT] when it's no longer offered by [options]
     * (e.g. a stored "internal_vlc"/"external" from before the TV dropped it), so a dropdown
     * never shows a dangling selection.
     */
    fun coerceSelection(selected: String, options: List<Pair<String, String>>): String =
        if (options.any { it.first == selected }) selected else TV_DEFAULT
}
