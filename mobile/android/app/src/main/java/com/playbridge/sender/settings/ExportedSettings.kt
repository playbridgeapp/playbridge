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
    val addedAt: Long
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
    val mediaflowProxyUrl: String? = null,
    val mediaflowProxyPassword: String? = null,
)
