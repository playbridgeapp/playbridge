package com.playbridge.sender.settings

import kotlinx.serialization.Serializable

@Serializable
data class ExportedBookmark(
    val url: String,
    val title: String?
)

@Serializable
data class ExportedTab(
    val id: String,
    val url: String,
    val title: String?,
    val parentId: String?
)

@Serializable
data class ExportedWatchlist(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val posterUrl: String?,
    val year: String,
    val rating: String,
    val addedAt: Long,
    // Tracking / progress state — new fields default to null so older backups still parse.
    val status: String? = null,
    val userRating: Int? = null,
    val seasonProgress: Int? = null,
    val episodeProgress: Int? = null,
    val notes: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)

/** A cross-session resume position (drives "Resume · mm:ss" and progress bars). */
@Serializable
data class ExportedResume(
    val contentKey: String,
    val tmdbId: Int,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/**
 * A user-added IPTV playlist source. Channels are NOT exported — they're a refreshable
 * cache re-parsed from [source]. Note: FILE-sourced playlists carry a content:// URI
 * whose permission grant does not transfer to another device; those import but need
 * the file re-picked.
 */
@Serializable
data class ExportedIptvPlaylist(
    val name: String,
    val source: String,
    val sourceType: String,
    val addedAt: Long,
)

@Serializable
data class ExportedCollectionItem(
    val title: String,
    val url: String,
    val kind: String,
    val mimeType: String? = null,
    val headersJson: String? = null,
    val logo: String? = null,
    val sourceTag: String? = null,
    val orderIndex: Int = 0,
    val addedAt: Long,
)

/** A curated collection with its ordered items (LOCAL items: see FILE caveat above). */
@Serializable
data class ExportedCollection(
    val name: String,
    val addedAt: Long,
    val items: List<ExportedCollectionItem> = emptyList(),
)

/** App-level preferences stored in DataStore (toggles, defaults, popup lists). */
@Serializable
data class ExportedAppSettings(
    val autoSwitchToRemote: Boolean? = null,
    val maxAliveTabs: Int? = null,
    val preferredAudioLang: String? = null,
    val preferredSubtitleLang: String? = null,
    val defaultVideoQuality: String? = null,
    val maxBitrateCapMbps: Double? = null,
    val tvPrefetchWindow: Int? = null,
    val detectVideos: Boolean? = null,
    val trackWatchProgress: Boolean? = null,
    val autoAddToWatching: Boolean? = null,
    val blockPopups: Boolean? = null,
    val popupWhitelist: List<String>? = null,
    val popupBlacklist: List<String>? = null,
    val iptvSort: String? = null,
    val iptvSortAscending: Boolean? = null,
    val iptvActiveFirst: Boolean? = null,
    val sendSubtitlesToTv: Boolean? = null,
)

@Serializable
data class ExportedSettings(
    val debridProvider: String? = null,
    val debridApiKey: String? = null,
    val debridApiKeys: Map<String, String>? = null,
    val tmdbApiKey: String? = null,
    val omdbApiKey: String? = null,
    val tvPlayerMode: String? = null,
    val tvBrowserMode: String? = null,
    val addonUrls: List<String> = emptyList(),
    val tabs: List<ExportedTab>? = null,
    val bookmarks: List<ExportedBookmark>? = null,
    val watchlist: List<ExportedWatchlist>? = null,
    val resume: List<ExportedResume>? = null,
    val iptvPlaylists: List<ExportedIptvPlaylist>? = null,
    val collections: List<ExportedCollection>? = null,
    val appSettings: ExportedAppSettings? = null,
    /** @deprecated Prefer [streamProxyRemoteUrl]; kept for import of older backups. */
    val mediaflowProxyUrl: String? = null,
    /** @deprecated Prefer [streamProxyRemotePassword]. */
    val mediaflowProxyPassword: String? = null,
    @Deprecated("Removed with MediaFlow retirement")
    val mediaflowAutoSelect: Boolean? = null,
    @Deprecated("Removed with MediaFlow retirement")
    val mediaflowProxyEnabled: Boolean? = null,
    val streamProxyRemoteUrl: String? = null,
    val streamProxyRemotePassword: String? = null,
    val streamRouteDefault: String? = null,
)
