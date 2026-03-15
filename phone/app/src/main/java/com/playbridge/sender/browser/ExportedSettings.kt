package com.playbridge.sender.browser

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
data class ExportedSettings(
    val showInbuiltExtensions: Boolean? = null,
    val debridProvider: String? = null,
    val debridApiKey: String? = null,
    val tmdbApiKey: String? = null,
    val omdbApiKey: String? = null,
    val tvPlayerMode: String? = null,
    val tvBrowserMode: String? = null,
    val addonUrls: List<String> = emptyList(),
    val tabs: List<ExportedTab>? = null,
    val bookmarks: List<ExportedBookmark>? = null
)
