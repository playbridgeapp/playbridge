package com.playbridge.shared.player

/**
 * Playback preferences carried from the phone or read from local settings.
 *
 * @param defaultVideoQuality e.g. "720p", "1080p", "2160p", or null for best.
 * @param maxBitrateCapMbps soft cap on estimated Mbps; null = no cap.
 * @param preferredAddonBaseUrl preferred Stremio addon base URL.
 * @param preferredAddonName preferred Stremio addon display name.
 * @param preferredSourceTypes ordered list of source-type keys (bluray, web-dl, etc.).
 */
data class PlaybackSettings(
    val defaultVideoQuality: String? = null,
    val maxBitrateCapMbps: Double? = null,
    val preferredAddonBaseUrl: String? = null,
    val preferredAddonName: String? = null,
    val preferredSourceTypes: List<String>? = null,
)
