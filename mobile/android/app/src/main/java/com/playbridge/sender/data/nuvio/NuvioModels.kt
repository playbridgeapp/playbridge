package com.playbridge.sender.data.nuvio

import androidx.room.Entity
import kotlinx.serialization.Serializable

// ==================== Room Entity ====================

/**
 * One scraper belonging to an installed Nuvio plugin repository.
 *
 * The repository itself is represented as a row in `installed_addons` (so it appears in
 * the existing addon management UI with enable/disable + remove for free), marked with
 * the pseudo-resource "nuvio" (see [NuvioService.NUVIO_RESOURCE]). `repoUrl` here equals
 * that row's `manifestUrl`. The downloaded scraper code lives on disk at
 * `filesDir/nuvio/<repoDirName>/<scraperId>.js` — never in the DB.
 */
@Entity(tableName = "nuvio_scrapers", primaryKeys = ["repoUrl", "scraperId"])
data class NuvioScraperEntity(
    val repoUrl: String,
    val scraperId: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val filename: String,
    /** Comma-separated: "movie,tv" (Nuvio types, NOT Stremio's "series"). */
    val supportedTypes: String = "movie,tv",
    /** Comma-separated ISO codes, e.g. "en,hi,ta". */
    val contentLanguage: String = "",
    val logo: String = "",
    /** Per-scraper user toggle (repo master switch lives on the installed_addons row). */
    val isEnabled: Boolean = true,
    /** True when the scraper exports `onSettings()` — drives the settings UI affordance. */
    val hasSettings: Boolean = false,
    /** User's saved settings as a JSON object string, injected as `globalThis.settings`. */
    val settingsJson: String = "{}",
    val installedAt: Long = System.currentTimeMillis()
)

fun NuvioScraperEntity.supportsType(nuvioType: String): Boolean =
    supportedTypes.split(",").map { it.trim() }.contains(nuvioType)

// ==================== Repository manifest DTOs ====================

/** Root of a Nuvio plugin repository `manifest.json`. */
@Serializable
data class NuvioManifest(
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val scrapers: List<NuvioScraperInfo> = emptyList()
)

@Serializable
data class NuvioScraperInfo(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val supportedTypes: List<String> = emptyList(),
    val filename: String = "",
    val enabled: Boolean = true,
    val logo: String = "",
    val contentLanguage: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    /** Explicit platform whitelist; empty = all platforms. */
    val supportedPlatforms: List<String> = emptyList(),
    val disabledPlatforms: List<String> = emptyList(),
    val hasSettings: Boolean = false,
    val limited: Boolean = false
) {
    val isAndroidCompatible: Boolean
        get() = (supportedPlatforms.isEmpty() || supportedPlatforms.contains("android")) &&
            !disabledPlatforms.contains("android")
}

// ==================== Scraper result ====================

/**
 * One stream returned by a scraper's `getStreams()`. Field names follow the de-facto
 * Nuvio provider contract (see nuvio-providers DOCUMENTATION.md).
 */
@Serializable
data class NuvioStreamResult(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val quality: String? = null,
    val size: String? = null,
    val provider: String? = null,
    val headers: Map<String, String> = emptyMap()
)

// ==================== Settings schema (from a scraper's onSettings()) ====================

/**
 * One field in a scraper's settings schema. Matches the de-facto Nuvio shape:
 * `{type, key, label, options?, defaultValue?}`. [type] is typically "header"
 * (label only), "dropdown"/"select" (choose from [options]), or a free-text input.
 */
@Serializable
data class NuvioSettingField(
    val type: String = "",
    val key: String = "",
    val label: String = "",
    val options: List<NuvioSettingOption> = emptyList(),
    val defaultValue: String? = null
) {
    val isHeader: Boolean get() = type.equals("header", ignoreCase = true)
    val isChoice: Boolean get() = options.isNotEmpty() ||
        type.equals("dropdown", ignoreCase = true) || type.equals("select", ignoreCase = true)
}

@Serializable
data class NuvioSettingOption(
    val label: String = "",
    val value: String = ""
)
