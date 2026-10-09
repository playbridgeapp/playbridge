package com.playbridge.sender.cast.dlna

import org.json.JSONObject
import java.io.IOException

/** Structured request-scoped failure returned by a DLNA action in Rust's session worker. */
internal class DlnaActionFailure(
    val actionName: String,
    val httpStatus: Int?,
    val upnpCode: Int?,
    val upnpDescription: String? = null,
) : IOException(dlnaActionFailureMessage(actionName, httpStatus, upnpCode, upnpDescription))

internal fun dlnaActionFailureFromEvent(
    actionName: String?,
    upnp: JSONObject?,
): DlnaActionFailure? {
    upnp ?: return null
    val action = upnp.optString("action").takeIf(String::isNotBlank)
        ?: actionName?.takeIf(String::isNotBlank)
        ?: return null
    val code = upnp.optNullableInt("code")
    val httpStatus = upnp.optNullableInt("http_status")
    val description = upnp.optString("description")
        .takeIf { upnp.has("description") && !upnp.isNull("description") }
    return DlnaActionFailure(action, httpStatus, code, description)
}

internal fun dlnaActionFailureMessage(
    actionName: String,
    httpStatus: Int?,
    upnpCode: Int?,
    upnpDescription: String?,
): String = buildString {
    append("DLNA $actionName failed (")
    if (httpStatus != null && httpStatus in 100..599) append("HTTP $httpStatus, ")
    append("UPnP ${upnpCode ?: "unknown"}")
    upnpDescription?.takeIf(String::isNotBlank)?.let { description ->
        val sanitized = description.replace(URL_PATTERN, "[URL redacted]").trim()
        val limited = if (sanitized.length > MAX_DESCRIPTION_CHARS) {
            sanitized.take(MAX_DESCRIPTION_CHARS - 3) + "..."
        } else {
            sanitized
        }
        if (limited.isNotEmpty()) append(": ").append(limited)
    }
    append(')')
}

private fun JSONObject.optNullableInt(key: String): Int? {
    val value = opt(key)
    if (value == null || value === JSONObject.NULL) return null
    return if (value is Number) value.toInt() else value.toString().toIntOrNull()
}

private val URL_PATTERN = Regex("https?://[^\\s<]+", RegexOption.IGNORE_CASE)
private const val MAX_DESCRIPTION_CHARS = 120
