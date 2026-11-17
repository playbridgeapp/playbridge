package com.playbridge.shared.player

/**
 * Browser-context and hop-by-hop headers that players must never forward upstream.
 * Notably, forwarding a browser-captured `Range: bytes=0-` or `If-Range` breaks seeking.
 */
val PLAYER_SKIP_HEADERS: Set<String> = setOf(
    "Range",
    "If-Range",
    "Accept-Encoding",
    "Host",
    "Connection",
    "Content-Length",
    "Sec-Fetch-Dest",
    "Sec-Fetch-Mode",
    "Sec-Fetch-Site",
    "Sec-Fetch-Storage-Access",
    "Sec-GPC",
    "Sec-CH-UA",
    "Sec-CH-UA-Mobile",
    "Sec-CH-UA-Platform",
    "Priority",
    "Upgrade-Insecure-Requests",
    "TE",
    "Pragma",
)

private val PLAYER_SKIP_HEADERS_LOWER: Set<String> =
    PLAYER_SKIP_HEADERS.mapTo(HashSet(PLAYER_SKIP_HEADERS.size)) { it.lowercase() }

/**
 * Returns a copy of [headers] with browser-only headers removed while preserving
 * auth/session headers (`User-Agent`, `Referer`, `Origin`, `Cookie`, `Authorization`, ...).
 */
fun sanitizePlayerHeaders(headers: Map<String, String>): Map<String, String> {
    if (headers.isEmpty()) return emptyMap()
    val filtered = LinkedHashMap<String, String>(headers.size)
    for ((name, value) in headers) {
        if (name.lowercase() !in PLAYER_SKIP_HEADERS_LOWER) {
            filtered[name] = value
        }
    }
    return filtered
}
