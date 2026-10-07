package com.playbridge.shared.update

/**
 * What actually differs between the phone and TV updaters.
 *
 * Prefs names and snooze keys are intentionally not configurable: existing installs
 * persist state under `update_checker` / `snoozed_version` / `first_seen_<version>`.
 */
data class UpdateConfig(
    val downloadEndpoint: String,
    val assetVersionPattern: Regex,
    val userAgent: String,
    val playStoreWebUrl: String,
)

object UpdateConfigs {
    val phone = UpdateConfig(
        downloadEndpoint = "https://playbridge.app/download/android",
        assetVersionPattern = Regex("""playbridge-phone-(\d+(?:\.\d+)+)"""),
        userAgent = "PlayBridge-Phone-Updater",
        playStoreWebUrl = "https://play.google.com/store/apps/details?id=com.playbridge.sender",
    )

    val tvPlayer = UpdateConfig(
        downloadEndpoint = "https://playbridge.app/download/tv-player",
        assetVersionPattern = Regex("""playbridge-tv-player-(\d+(?:\.\d+)+)"""),
        userAgent = "PlayBridge-TV-Updater",
        playStoreWebUrl = "https://play.google.com/store/apps/details?id=com.playbridge.player",
    )
}

/**
 * Picks the release version out of a download-endpoint `Location` using [UpdateConfig.assetVersionPattern].
 *
 * The website redirects to a release asset URL. The version comes from the asset filename
 * (the first pattern match), not from the release tag in the path. A miss returns null —
 * the caller treats that the same as an unparseable version.
 */
object ReleaseAssets {
    fun select(location: String, config: UpdateConfig): AppVersion? {
        val rawVersion = config.assetVersionPattern.find(location)?.groupValues?.getOrNull(1)
        return AppVersion.parse(rawVersion)
    }
}
