package com.playbridge.sender.dlna

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Tolerant extractor (spike only): pulls the media `url` and `headers` map out of a
 * pasted cast message. Works whether you paste the full play envelope
 * `{type,action:"playlist",payload:{items:[{url,headers}]}}`, a single PlayPayload,
 * or a `video_detected` message. In the wire proto, headers is a
 * `map<string,string>` → a JSON object.
 */
object CastMessageParser {
    data class Parsed(val url: String, val headers: Map<String, String>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): Parsed? {
        val root = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull() ?: return null
        val url = findString(root, "url")?.takeIf { it.isNotBlank() } ?: return null
        val headers = findObject(root, "headers")
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            ?.toMap()
            .orEmpty()
        return Parsed(url, headers)
    }

    /** Depth-first search for the first string value under [key]. */
    private fun findString(el: JsonElement, key: String): String? = when (el) {
        is JsonObject ->
            (el[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: el.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> el.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    /** Depth-first search for the first object value under [key]. */
    private fun findObject(el: JsonElement, key: String): JsonObject? = when (el) {
        is JsonObject ->
            (el[key] as? JsonObject)
                ?: el.values.firstNotNullOfOrNull { findObject(it, key) }
        is JsonArray -> el.firstNotNullOfOrNull { findObject(it, key) }
        else -> null
    }
}
