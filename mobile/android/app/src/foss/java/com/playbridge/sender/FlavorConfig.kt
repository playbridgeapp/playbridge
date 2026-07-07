package com.playbridge.sender

/**
 * Capabilities of the FOSS (GitHub/sideload) distribution flavor.
 * The Play flavor ships a counterpart with restricted features
 * (Google Play content-policy compliance).
 */
object FlavorConfig {
    /** Debrid services (Real-Debrid, All-Debrid, Premiumize, TorBox) are FOSS-only. */
    const val DEBRID_SUPPORTED = true

    /**
     * Nuvio-style local scraper plugins (downloaded JS run in QuickJS) are FOSS-only,
     * matching Nuvio's own Play posture (their Play flavor sets pluginsEnabled=false).
     */
    const val SCRAPER_PLUGINS_SUPPORTED = true
}
