package com.playbridge.sender.browser

import kotlinx.serialization.Serializable

@Serializable
data class ExportedSettings(
    val showInbuiltExtensions: Boolean? = null,
    val debridProvider: String? = null,
    val debridApiKey: String? = null,
    val tmdbApiKey: String? = null,
    val tvPlayerMode: String? = null,
    val tvBrowserMode: String? = null,
    val addonUrls: List<String> = emptyList()
)
