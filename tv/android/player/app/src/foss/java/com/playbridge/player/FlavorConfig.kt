package com.playbridge.player

/**
 * Capabilities of the FOSS (GitHub/sideload) distribution flavor.
 * The Play flavor ships a counterpart with restricted features
 * (Google Play policy compliance).
 */
object FlavorConfig {
    /**
     * Whether the app may point users at APK downloads outside an app store
     * (e.g. the optional GeckoView browser-plugin APK on GitHub releases).
     * Prohibited for Play-distributed builds (Device and Network Abuse policy).
     */
    const val SIDELOAD_LINKS_SUPPORTED = true
}
