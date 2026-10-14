package com.playbridge.sender.logging

import com.playbridge.shared.logging.redactLogText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Release logs for a WebSocket frame: the envelope type and the UTF-8 size.
 * The payload stays out of logcat. Debug builds can log [redact], which uses the
 * shared sanitizer to drop URLs and auth-like fields.
 */
internal object WireFrameLog {
    private val typeField = Regex(""""type"\s*:\s*"([^"\\]+)"""")

    fun summary(frame: String): String {
        val type = typeField.find(frame)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "unknown"
        val bytes = frame.toByteArray(Charsets.UTF_8).size
        return "type=$type bytes=$bytes"
    }

    fun redact(frame: String): String {
        // Descriptors can be large and contain open receiver-provided identifiers. Even debug
        // frame logging needs only the envelope; pairing credentials are never decrypted here.
        val withoutDescriptor = runCatching {
            val json = Json.parseToJsonElement(frame) as? JsonObject
            if (json != null && "capabilities" in json) JsonObject(json - "capabilities").toString() else frame
        }.getOrDefault(frame)
        return redactLogText(withoutDescriptor)
    }
}
