package com.playbridge.sender

/**
 * Capabilities of the Google Play distribution flavor.
 * Debrid integration is disabled for Play content-policy compliance:
 * [com.playbridge.sender.data.debrid.DebridRepository] reports no providers and the
 * Debrid dashboard/settings entry points are hidden.
 */
object FlavorConfig {
    /** Debrid services (Real-Debrid, All-Debrid, Premiumize, TorBox) are FOSS-only. */
    const val DEBRID_SUPPORTED = false

    /**
     * Nuvio-style local scraper plugins (downloaded JS run in QuickJS) are FOSS-only,
     * matching Nuvio's own Play posture (their Play flavor sets pluginsEnabled=false).
     */
    const val SCRAPER_PLUGINS_SUPPORTED = false
}
