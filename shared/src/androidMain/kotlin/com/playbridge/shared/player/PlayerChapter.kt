package com.playbridge.shared.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One chapter marker of the open media file. */
data class PlayerChapter(val startMs: Long, val title: String? = null)

/** mpv's placeholder for a chapter without a title (Matroska). */
private const val MPV_UNNAMED_CHAPTER = "(unnamed)"

/**
 * Parses mpv's string form of `chapter-list` (JSON `[{"title","time"}]`, time in
 * seconds). Malformed entries are skipped; ordering is left to the reporter.
 */
fun parseMpvChapterList(raw: String): List<PlayerChapter> {
    val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val seconds = (entry["time"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.doubleOrNull
            ?.takeIf { it.isFinite() }
            ?: return@mapNotNull null
        val title = (entry["title"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it != MPV_UNNAMED_CHAPTER }
        PlayerChapter(startMs = Math.round(seconds * 1000), title = title)
    }
}
