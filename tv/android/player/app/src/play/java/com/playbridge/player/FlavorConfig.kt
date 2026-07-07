package com.playbridge.player

/**
 * Capabilities of the Google Play distribution flavor.
 * Pointing users at APK downloads outside Play violates the Device and Network
 * Abuse policy, so the GeckoView plugin install prompt is hidden. If the plugin
 * is already installed (sideloaded), it is still detected and used.
 */
object FlavorConfig {
    /** See the FOSS counterpart. */
    const val SIDELOAD_LINKS_SUPPORTED = false
}
