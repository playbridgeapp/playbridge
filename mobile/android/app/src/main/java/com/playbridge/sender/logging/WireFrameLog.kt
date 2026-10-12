package com.playbridge.sender.logging

import com.playbridge.shared.logging.redactLogText

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

    fun redact(frame: String): String = redactLogText(frame)
}
