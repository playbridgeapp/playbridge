package com.playbridge.player.server

import android.content.Context
import android.content.pm.PackageManager

/**
 * The players and browser engines this receiver can actually drive. Reported to the phone
 * at auth so the phone's pickers only ever offer what this TV supports (see the phone-side
 * `TvCapabilityOptions`). IDs match what [com.playbridge.player.preplay.PrePlayActivity] and
 * [ServerService] route on (`player_mode` / `browser_mode`).
 */
data class TvCapabilities(
    val players: List<String>,
    val browsers: List<String>,
)

object TvCapabilityProvider {
    private const val GECKO_PACKAGE = "com.playbridge.browser"

    // Both engines are compiled into the player app; external players were removed.
    private val PLAYERS = listOf("internal_exo", "internal_mpv")

    /**
     * Resolved fresh per call so a GeckoView plugin installed after the server starts is
     * picked up on the next (re)connect without a restart.
     */
    fun current(context: Context): TvCapabilities {
        val browsers = buildList {
            add("webview") // System WebView — built into the player app, always available.
            if (isGeckoInstalled(context)) add("gecko")
        }
        return TvCapabilities(players = PLAYERS, browsers = browsers)
    }

    private fun isGeckoInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GECKO_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
