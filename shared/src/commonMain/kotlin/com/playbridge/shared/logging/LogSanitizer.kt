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

private val URL_IN_TEXT = Regex("""\b[A-Za-z][A-Za-z0-9+.\-]{1,15}://[^\s"'<>()\[\]{},]+""")
private val BEARER_CREDENTIAL = Regex("""(?i)\b(Bearer|Basic|Digest|Token)\s+[A-Za-z0-9\-._~+/=:]{6,}""")
private val SENSITIVE_FIELD = Regex(
    """(?i)(["']?\b(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|api[_-]?key|""" +
        """access[_-]?token|refresh[_-]?token|auth[_-]?token|token|password|passwd|secret|signature|""" +
        """session[_-]?id|x-plex-token|x-emby-token|x-mediabrowser-token)\b["']?\s*[:=]\s*)""" +
        """("[^"]*"|'[^']*'|[^\s,;&}\]]+)""",
)

/**
 * Redacts a log line before it is persisted: every URL becomes [redactUrlForLog]'s
 * scheme-only label, and credential-bearing header or field values are replaced. Logs may
 * be shared for debugging, so stream URLs, Debrid tokens and request headers must not
 * reach disk.
 */
fun redactLogText(text: String): String {
    if (text.isEmpty()) return text
    var result = URL_IN_TEXT.replace(text) { redactUrlForLog(it.value) }
    result = BEARER_CREDENTIAL.replace(result) { "${it.groupValues[1]} <redacted>" }
    result = SENSITIVE_FIELD.replace(result) { "${it.groupValues[1]}<redacted>" }
    return result
}
