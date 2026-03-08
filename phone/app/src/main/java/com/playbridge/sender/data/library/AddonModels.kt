package com.playbridge.sender.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== Room Entity for Installed Addons ====================

@Entity(tableName = "installed_addons")
data class InstalledAddonEntity(
    @PrimaryKey
    val manifestUrl: String,
    val name: String,
    val description: String = "",
    val baseUrl: String,       // e.g. https://torrentio.strem.fun/providers=yts|sort=qualitysize|debridoptions=nodownloadlinks|realdebrid=XXXX
    val version: String = "",
    val types: String = "",    // Comma-separated: "movie,series"
    val installedAt: Long = System.currentTimeMillis()
)

// ==================== Stremio Addon Manifest ====================

@Serializable
data class StremioManifest(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val resources: List<StremioResource> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<StremioResource> = emptyList()
)

@Serializable
data class StremioResource(
    val name: String = "",
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList()
)

// ==================== Stremio Stream Response ====================

@Serializable
data class StremioStreamResponse(
    val streams: List<StremioStream> = emptyList()
)

@Serializable
data class StremioStream(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    @SerialName("externalUrl") val externalUrl: String? = null,
    val behaviorHints: StemioBehaviorHints? = null
) {
    /** Whether this is a directly playable HTTP stream (from debrid) */
    val isDirectUrl: Boolean get() = !url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))

    /** Display name: prefer name, fall back to parsing the title */
    val displayName: String get() = name ?: "Unknown"

    /** Quality/info description parsed from the title field */
    val qualityInfo: String get() = title ?: ""
}

@Serializable
data class StemioBehaviorHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null
) {
    val fileSizeFormatted: String? get() {
        val size = videoSize ?: return null
        return when {
            size >= 1_073_741_824 -> String.format("%.1f GB", size / 1_073_741_824.0)
            size >= 1_048_576 -> String.format("%.0f MB", size / 1_048_576.0)
            else -> "$size B"
        }
    }
}
