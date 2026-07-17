package com.playbridge.shared.logging

/**
 * Produces a diagnostic URL label without retaining credentials, path segments, query
 * parameters, fragments, or hostnames. Cast URLs can be signed even when no explicit
 * Authorization header is present, so logs should never contain the original value.
 */
fun redactUrlForLog(value: String?): String {
    if (value.isNullOrBlank()) return "<no-url>"

    val separator = value.indexOf(':')
    if (separator <= 0) return "<redacted-url>"
    val scheme = value.substring(0, separator)
    val validScheme = scheme.first().isLetter() &&
        scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    return if (validScheme) "$scheme://<redacted>" else "<redacted-url>"
}
