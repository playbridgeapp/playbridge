package com.playbridge.sender.cast

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

internal const val MAX_DETECTED_SUBTITLE_BYTES = 2 * 1024 * 1024

internal data class DetectorSubtitleText(
    val text: String,
    val format: String,
    val label: String,
) {
    val mime: String get() = if (format == "vtt") "text/vtt" else "application/x-subrip"
    val hash: String get() = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun detectorSubtitleText(message: JsonObject): DetectorSubtitleText? {
    fun field(name: String): String? = (message[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (field("mediaKind") != "subtitle") return null
    val format = field("subtitleFormat")?.takeIf { it == "vtt" || it == "srt" } ?: return null
    val text = field("subtitleText")?.takeIf { it.isNotBlank() } ?: return null
    if (text.length > MAX_DETECTED_SUBTITLE_BYTES ||
        text.toByteArray(Charsets.UTF_8).size > MAX_DETECTED_SUBTITLE_BYTES
    ) return null
    val mime = field("contentType").orEmpty().substringBefore(';').trim().lowercase()
    if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/")) return null
    val start = text.removePrefix("\uFEFF").trimStart().take(8192)
    val isVtt = Regex("^WEBVTT(?:[ \\t]|\\r?\\n|$)").containsMatchIn(start)
    val isSrt = Regex("^(?:\\d+\\s*\\r?\\n)?\\d{1,2}:\\d{2}:\\d{2},\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2},\\d{3}")
        .containsMatchIn(start)
    val detectedFormat = when {
        isVtt -> "vtt"
        isSrt -> "srt"
        mime == "text/vtt" -> "vtt"
        mime.contains("subrip") -> "srt"
        else -> return null
    }
    if (format != detectedFormat) return null
    return DetectorSubtitleText(text, format, field("subtitleLabel")?.trim()?.take(256)
        ?.takeIf { it.isNotEmpty() } ?: "Subtitles")
}

/** Text stays in the cache file, never in diagnostics or the retained detection JSON. */
internal fun publishedSubtitleMessage(message: JsonObject, subtitle: DetectorSubtitleText, url: String): JsonObject =
    JsonObject(message.filterKeys { it !in setOf("subtitleText", "subtitleFormat", "subtitleLabel", "headers") } + mapOf(
        "url" to JsonPrimitive(url),
        "contentType" to JsonPrimitive(subtitle.mime),
        "detectedBy" to JsonPrimitive("blob_subtitle"),
        "title" to JsonPrimitive(subtitle.label),
    ))
