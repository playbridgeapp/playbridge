package com.playbridge.sender.browser

import java.net.URI

/** Pure checks shared by the document bridge and its security tests. */
internal object NativePluginBridgePolicy {
    const val MAX_REQUEST_BYTES = 16 * 1024
    const val MAX_PENDING_REQUESTS = 4
    const val MAX_SCRAPERS = 32

    fun authorized(
        senderUrl: String?,
        currentUrl: String?,
        installedAppOrigin: String?,
        topLevel: Boolean,
        matchingSession: Boolean,
        contentScript: Boolean,
        debugBuild: Boolean,
    ): Boolean {
        if (!topLevel || !matchingSession || !contentScript || installedAppOrigin == null) return false
        val origin = senderUrl?.let(BridgedAppStore::originFor) ?: return false
        if (origin != installedAppOrigin || currentUrl?.let(BridgedAppStore::originFor) != origin) return false
        // HTTP development origins must never gain privileged access in release.
        return origin.startsWith("https://") || debugBuild
    }

    fun validResolveRequest(
        repoUrl: String,
        scraperIds: List<String>,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): Boolean {
        if (repoUrl.length !in 1..2048) return false
        val uri = runCatching { URI(repoUrl) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null) return false
        if (scraperIds.size !in 1..MAX_SCRAPERS || scraperIds.distinct().size != scraperIds.size) return false
        if (scraperIds.any { it.isBlank() || it.length > 128 || it.any { c -> c.isISOControl() } }) return false
        if (!tmdbId.matches(Regex("[1-9][0-9]{0,9}")) || tmdbId.toLongOrNull() == null) return false
        if (mediaType != "movie" && mediaType != "tv") return false
        if (mediaType == "movie") return season == null && episode == null
        return season != null && season in 0..10000 && episode != null && episode in 1..10000
    }
}
