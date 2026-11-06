package com.playbridge.shared.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

enum class MpvTrackType { VIDEO, AUDIO, SUBTITLE }

/** One selectable entry of mpv's `track-list` property; sizes and counts are 0 when mpv doesn't know them. */
data class MpvTrackInfo(
    val type: MpvTrackType,
    val id: String,
    val language: String?,
    val title: String?,
    val codec: String?,
    val selected: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Long = 0,
    val channelCount: Int = 0,
)

/**
 * Parses mpv's string form of `track-list`, shared by the TV renderer and the phone player.
 * Entries without an id or a known type are skipped, as is embedded cover art (a "video" track
 * that isn't a picture the viewer can select).
 */
fun parseMpvTrackList(raw: String): List<MpvTrackInfo> {
    val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val type = when (entry.string("type")) {
            "video" -> MpvTrackType.VIDEO
            "audio" -> MpvTrackType.AUDIO
            "sub" -> MpvTrackType.SUBTITLE
            else -> return@mapNotNull null
        }
        if (entry.boolean("albumart")) return@mapNotNull null
        MpvTrackInfo(
            type = type,
            id = (entry["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
            language = entry.string("lang"),
            title = entry.string("title"),
            codec = entry.string("codec"),
            selected = entry.boolean("selected"),
            width = entry.number("demux-w").toInt(),
            height = entry.number("demux-h").toInt(),
            bitrate = entry.number("demux-bitrate"),
            channelCount = entry.number("demux-channel-count").toInt(),
        )
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.number(key: String): Long =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.coerceAtLeast(0) ?: 0
