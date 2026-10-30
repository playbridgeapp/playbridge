package com.playbridge.sender.settings

import kotlinx.serialization.Serializable
import com.playbridge.sender.data.settings.MediaDetectionSettings

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
    val mediaDetection: MediaDetectionSettings? = null,
    val blockPopups: Boolean? = null,
    val popupWhitelist: List<String>? = null,
    val popupBlacklist: List<String>? = null,
    val iptvSort: String? = null,
    val iptvSortAscending: Boolean? = null,
    val iptvActiveFirst: Boolean? = null,
    val hideEmptyCastSheetTabs: Boolean? = null,
)

@Serializable
data class ExportedSettings(
    val tvPlayerMode: String? = null,
    val tvBrowserMode: String? = null,
    val tabs: List<ExportedTab>? = null,
    val bookmarks: List<ExportedBookmark>? = null,
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

internal object SettingsBackupCodec {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun decode(value: String): ExportedSettings = json.decodeFromString<ExportedSettings>(value)
}
