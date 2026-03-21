package com.playbridge.sender.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

@Serializable(with = StremioResourceSerializer::class)
data class StremioResource(
    val name: String = "",
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList()
) {
    @Serializable
    data class Surrogate(
        val name: String = "",
        val types: List<String> = emptyList(),
        val idPrefixes: List<String> = emptyList()
    )
}

object StremioResourceSerializer : KSerializer<StremioResource> {
    override val descriptor: SerialDescriptor = StremioResource.Surrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): StremioResource {
        val jsonDecoder = decoder as? JsonDecoder ?: error("Only JSON is supported")
        val element = jsonDecoder.decodeJsonElement()
        
        return when (element) {
            is JsonPrimitive -> StremioResource(name = element.content)
            is JsonObject -> {
                val surrogate = jsonDecoder.json.decodeFromJsonElement(StremioResource.Surrogate.serializer(), element)
                StremioResource(surrogate.name, surrogate.types, surrogate.idPrefixes)
            }
            else -> throw IllegalArgumentException("Unsupported JSON element for StremioResource")
        }
    }

    override fun serialize(encoder: Encoder, value: StremioResource) {
        val surrogate = StremioResource.Surrogate(value.name, value.types, value.idPrefixes)
        encoder.encodeSerializableValue(StremioResource.Surrogate.serializer(), surrogate)
    }
}

// ==================== Stremio Stream Response ====================

@Serializable
data class StremioStreamResponse(
    val streams: List<StremioStream>? = null,
    val subtitles: List<StremioStream>? = null
)

@Serializable
data class StremioStream(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
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

    /**
     * Estimate Mbps from file size and episode runtime.
     * @param runtimeMinutes episode runtime in minutes (from TMDB)
     * @return formatted Mbps string (e.g. "~6.2 Mbps") or null if unavailable
     */
    fun estimateMbps(runtimeMinutes: Int?): String? {
        val size = videoSize ?: return null
        val runtime = runtimeMinutes ?: return null
        if (runtime <= 0) return null
        val mbps = size * 8.0 / (runtime * 60 * 1_000_000.0)
        return "~%.1f Mbps".format(mbps)
    }

    /**
     * Calculate raw Mbps value for bitrate comparison.
     * @return Mbps as Double, or null if unavailable
     */
    fun calculateMbps(runtimeMinutes: Int?): Double? {
        val size = videoSize ?: return null
        val runtime = runtimeMinutes ?: return null
        if (runtime <= 0) return null
        return size * 8.0 / (runtime * 60 * 1_000_000.0)
    }
}
