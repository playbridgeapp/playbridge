package com.playbridge.sender.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
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
    val playEndpoint: String = "", // "/api/play/{type}/{id}" or empty = standard /stream only
    val isConfigurable: Boolean = false, // From behaviorHints.configurable
    val version: String = "",
    val types: String = "",    // Comma-separated: "movie,series"
    val resources: String = "",      // JSON array of resource names, e.g. ["stream","catalog","meta","subtitles"]
    val catalogsJson: String = "",    // JSON array of StremioResource objects from manifest.catalogs
    val installedAt: Long = System.currentTimeMillis(),
    val isEnabled: Boolean = true,        // Master switch — false skips the addon entirely
    val sortOrder: Int = 0,               // User-defined display/resolution order (lower = higher priority)
    val disabledFeatures: String = "",     // Comma-separated resource names the user has turned off, e.g. "catalog,meta"
    val resourceDetailsJson: String = ""  // Full StremioResource JSON (name + types + idPrefixes). Empty = accepts all IDs.
)

/** idPrefixes declared by this addon's "meta" resource. Empty = accepts any ID. */
fun InstalledAddonEntity.metaIdPrefixes(): List<String> {
    if (resourceDetailsJson.isBlank()) return emptyList()
    return try {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<List<StremioResource>>(resourceDetailsJson)
            .firstOrNull { it.name == "meta" }?.idPrefixes ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

/** False only when the addon declares prefixes and none match [id]. */
fun InstalledAddonEntity.canHandleMetaId(id: String): Boolean {
    val prefixes = metaIdPrefixes()
    return prefixes.isEmpty() || prefixes.any { id.startsWith(it) }
}

fun InstalledAddonEntity.supportsResource(name: String): Boolean {
    return try {
        val resourcesList = kotlinx.serialization.json.Json.decodeFromString<List<String>>(resources)
        resourcesList.contains(name)
    } catch (e: Exception) {
        false
    }
}

/** True if this addon supports the Play Endpoint protocol for optimized TV playback. */
fun InstalledAddonEntity.supportsPlayEndpoint(): Boolean = playEndpoint.isNotBlank()

/**
 * Returns the set of resource names the user has explicitly disabled for this addon,
 * e.g. `setOf("catalog", "meta")`.  Empty when nothing is disabled.
 */
fun InstalledAddonEntity.disabledFeatureSet(): Set<String> {
    if (disabledFeatures.isBlank()) return emptySet()
    return disabledFeatures.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
}

/**
 * Returns true when this addon should be used for the given [resource]
 * (e.g. "stream", "catalog", "meta", "subtitles").
 * Respects both the master [isEnabled] switch and the per-feature [disabledFeatures] list.
 */
fun InstalledAddonEntity.isFeatureEnabled(resource: String): Boolean {
    if (!isEnabled) return false
    return resource !in disabledFeatureSet()
}

/**
 * Parse the catalog entries stored in [catalogsJson].
 * Each entry carries a URL path [id] (e.g. "top"), a single content [type]
 * (e.g. "movie"), and a human-readable [name].
 */
fun InstalledAddonEntity.parsedCatalogEntries(): List<StremioCatalogEntry> {
    return try {
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
        }.decodeFromString<List<StremioCatalogEntry>>(catalogsJson)
    } catch (e: Exception) {
        android.util.Log.e("AddonModels", "Failed to parse catalogsJson for $name: ${e.message}\nJSON: $catalogsJson")
        emptyList()
    }
}

// ==================== Stremio Addon Manifest ====================

/**
 * One extra parameter declared by an addon catalog (e.g. "search", "skip", "genre").
 * [isRequired] = true means the catalog only returns results when this extra is provided.
 */
@Serializable
data class StremioExtra(
    val name: String = "",
    val isRequired: Boolean = false,
    val options: List<String> = emptyList()
)

/**
 * A single catalog entry from an addon manifest.
 * Uses Stremio's actual field names: [id] is the URL path segment (e.g. "top"),
 * [type] is the content type (e.g. "movie"), and [name] is the human-readable label.
 * [extra] declares optional/required parameters like "search" and "skip".
 * Note: distinct from [StremioResource] which models the "resources" array (can be string or object).
 */
@Serializable
data class StremioCatalogEntry(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val extra: List<StremioExtra> = emptyList()
) {
    /** True if this catalog accepts a search query via the Stremio extra protocol. */
    val supportsSearch: Boolean get() = extra.any { it.name == "search" }
}

@Serializable
data class StremioManifest(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val resources: List<StremioResource> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<StremioCatalogEntry> = emptyList(),
    val behaviorHints: StremioManifestHints? = null
)

@Serializable
data class StremioManifestHints(
    val configurable: Boolean = false,
    val playEndpoint: String? = null
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

object StringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = ListSerializer(String.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonDecoder = decoder as? JsonDecoder ?: return emptyList()
        val element = jsonDecoder.decodeJsonElement()

        return when (element) {
            is JsonArray -> jsonDecoder.json.decodeFromJsonElement(ListSerializer(String.serializer()), element)
            is JsonPrimitive -> {
                if (element.isString && element.content.isNotBlank()) listOf(element.content) 
                else emptyList()
            }
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeSerializableValue(ListSerializer(String.serializer()), value)
    }
}

// ==================== Stremio Stream Response ====================

@Serializable
data class StremioStreamResponse(
    val streams: List<StremioStream>? = null,
    val subtitles: List<StremioStream>? = null
)

private val SIZE_REGEX = Regex("""💾\s*([\d.]+)\s*(TB|GB|MB|KB)""", RegexOption.IGNORE_CASE)

@Serializable
data class StremioStream(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    @SerialName("externalUrl") val externalUrl: String? = null,
    val behaviorHints: StemioBehaviorHints? = null,
    /** Subtitle language (ISO code like "en"/"eng"), present on subtitle resources. */
    val lang: String? = null
) {
    /** Whether this is a directly playable HTTP stream (from debrid) */
    val isDirectUrl: Boolean get() = !url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))

    /** Display name: prefer name, fall back to parsing the title */
    val displayName: String get() = name ?: "Unknown"

    /** Quality/info description parsed from the title field */
    val qualityInfo: String get() = title ?: ""

    /**
     * Normalised quality tier for TV-side stream resolution.
     * Returns "2160p", "1080p", "720p", or null — matching QualityRanker strings.
     *
     * Checks [name] first, then falls back to [title].
     * This is intentional: in Torrentio streams the [name] field (e.g. "[RD+] Torrentio 1080p")
     * is the authoritative quality label, while [title] often describes the *source material*
     * (e.g. "S01.2160p.UHD.BluRay..." for a 1080p downscale from a 4K source). Combining both
     * fields into a single string causes the source quality to shadow the actual stream quality.
     * AIOStreams also puts its quality label in [name], so name-first is safe for both addons.
     */
    val qualityTier: String? get() {
        fun detect(text: String): String? {
            val t = text.lowercase()
            return when {
                t.contains("2160p") || t.contains("4k") || t.contains("uhd") -> "2160p"
                t.contains("1080p") || t.contains("1080") -> "1080p"
                t.contains("720p")  || t.contains("720")  -> "720p"
                else -> null
            }
        }
        return detect(name.orEmpty()) ?: detect(title.orEmpty())
    }

    /**
     * Effective video size in bytes: behaviorHints.videoSize if present, otherwise parsed
     * from the 💾 emoji token in the title (e.g. Torrentio: "👤 12 💾 13.4 GB 🌐 Multi").
     */
    val effectiveVideoSizeBytes: Long? get() {
        behaviorHints?.videoSize?.let { if (it > 0) return it }
        val text = "${title.orEmpty()} ${name.orEmpty()}"
        val match = SIZE_REGEX.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "TB" -> 1_099_511_627_776L
            "GB" -> 1_073_741_824L
            "MB" -> 1_048_576L
            "KB" -> 1_024L
            else -> return null
        }
        return (value * multiplier).toLong()
    }

    /** Formatted file size using effectiveVideoSizeBytes. */
    val fileSizeFormatted: String? get() {
        val size = effectiveVideoSizeBytes ?: return null
        return when {
            size >= 1_099_511_627_776L -> "%.1f TB".format(size / 1_099_511_627_776.0)
            size >= 1_073_741_824L -> "%.1f GB".format(size / 1_073_741_824.0)
            size >= 1_048_576L -> "%.0f MB".format(size / 1_048_576.0)
            else -> "$size B"
        }
    }

    /** Estimate Mbps using effectiveVideoSizeBytes and runtime.
     *  Falls back to 120 min when [runtimeMinutes] is unavailable (same as StreamSelector). */
    fun estimateMbps(runtimeMinutes: Int?): String? {
        val size = effectiveVideoSizeBytes ?: return null
        val runtime = (runtimeMinutes ?: 120).coerceAtLeast(1)
        val mbps = size * 8.0 / (runtime * 60 * 1_000_000.0)
        return "~%.1f Mbps".format(mbps)
    }
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

// ==================== Stremio Catalog & Meta DTOs ====================

@Serializable
data class StremioMetaPreview(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val description: String? = null,
    val year: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList()
)

@Serializable
data class StremioMetaDetail(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val runtime: String? = null,
    val year: String? = null,
    val imdbRating: String? = null,
    val tmdbId: Int? = null,
    val cast: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    @Serializable(with = StringListSerializer::class)
    val director: List<String> = emptyList(),
    @Serializable(with = StringListSerializer::class)
    val writer: List<String> = emptyList(),
    val country: String? = null,
    val releaseInfo: String? = null,
    val status: String? = null,
    val awards: String? = null,
    val trailer: String? = null,
    val trailers: List<StremioTrailer> = emptyList(),
    val trailerStreams: List<StremioTrailerStream> = emptyList(),
    val logo: String? = null,
    @SerialName("app_extras") val appExtras: AppExtras? = null,
    val videos: List<StremioVideo> = emptyList()
)

/** `trailers[]` entry — `source` is the YouTube id; `name`/`type` are optional labels. */
@Serializable
data class StremioTrailer(
    val source: String? = null,
    val type: String? = null,
    val name: String? = null
)

/** `trailerStreams[]` entry — `ytId` is the YouTube id, `title` an optional label. */
@Serializable
data class StremioTrailerStream(
    val ytId: String? = null,
    val title: String? = null
)

/** Extra detail some addons (AIO, TMDB) nest under `app_extras`. All optional. */
@Serializable
data class AppExtras(
    val certification: String? = null,
    val cast: List<CastMember> = emptyList(),
    val directors: List<CastMember> = emptyList(),
    val writers: List<CastMember> = emptyList(),
    val seasonPosters: List<String> = emptyList()
)

@Serializable
data class CastMember(
    val name: String = "",
    val character: String? = null,
    val photo: String? = null
)

@Serializable
data class StremioVideo(
    val id: String = "",       // e.g. "tt1234567:1:3"
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val rating: String? = null,      // per-episode IMDb rating (Cinemeta)
    val runtime: String? = null,     // per-episode runtime (AIO/TMDB), e.g. "48min"
    val available: Boolean? = null   // false ⇒ not yet released/available (AIO/TMDB)
)

@Serializable data class StremioMetaResponse(val meta: StremioMetaDetail? = null)
@Serializable data class StremioMetasResponse(val metas: List<StremioMetaPreview>? = null)

// ==================== Home Tab Catalog Row ====================

/**
 * One horizontal row in the Home tab, representing a single addon catalog page.
 * [addonName] drives the source chip rendered beside the row title.
 *
 * Serializable so the assembled Home catalog list can be persisted to disk and
 * served instantly (cache-first) on the next launch.
 */
@Serializable
data class AddonCatalogRow(
    val catalogName: String,     // entry.name — human-readable, e.g. "Cinemeta - Top movies"
    val addonName: String,       // addon.name — shown as the source chip, e.g. "Cinemeta"
    val type: String,            // "movie" / "series" etc.
    val catalogId: String,       // URL path segment, e.g. "top"
    val addonBaseUrl: String,    // for eviction / identification
    val items: List<StremioMetaPreview> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,  // true while fetching a page beyond the first
    val currentSkip: Int = 0,           // skip offset of the last successfully loaded page
    val hasMore: Boolean = true         // false once a page fetch returns 0 new items
)

// ==================== Search Result Groups ====================

/**
 * Search results from a single addon, used to power per-source filter chips in Search.
 */
data class AddonSearchResultGroup(
    val addonName: String,
    val items: List<StremioMetaPreview>
)

/**
 * Parses a catalog title (e.g., "[Torrentio] Popular") into a pair of
 * (Effective Provider Name, Cleaned Title).
 * If no [Name] prefix is found, returns the original addon name and title.
 */
fun parseCatalogTitle(addonName: String, catalogTitle: String): Pair<String, String> {
    val PROVIDER_REGEX = Regex("""^\[(.*?)\]\s*(.*)$""")
    val match = PROVIDER_REGEX.find(catalogTitle)
    return if (match != null) {
        match.groupValues[1] to match.groupValues[2]
    } else {
        addonName to catalogTitle
    }
}
