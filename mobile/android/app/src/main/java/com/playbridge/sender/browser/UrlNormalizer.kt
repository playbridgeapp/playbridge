package com.playbridge.sender.browser

import java.net.URLEncoder
import java.util.Locale

/**
 * Builds a search query URL using Google Search.
 */
fun searchUrl(query: String): String {
    val trimmed = query.trim()
    val encodedQuery = try {
        URLEncoder.encode(trimmed, "UTF-8")
    } catch (e: Exception) {
        trimmed.replace(" ", "+")
    }
    return "https://www.google.com/search?q=$encodedQuery"
}

/**
 * Normalize user input to a valid URL.
 */
fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "about:blank"
    // Never run pasted scripts from the address bar.
    if (trimmed.startsWith("javascript:", ignoreCase = true)) return searchUrl(trimmed)
    if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true) ||
        trimmed.startsWith("about:", ignoreCase = true) ||
        Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
    ) return trimmed
    if (listOf("file:", "data:", "blob:", "mailto:", "tel:", "magnet:").any { trimmed.startsWith(it, ignoreCase = true) }) return trimmed
    if (trimmed.any(Char::isWhitespace)) return searchUrl(trimmed)
    val authority = trimmed.lowercase(Locale.ROOT).takeWhile { it !in "/?#" }
    if (authority.startsWith("[") && authority.contains("]")) {
        val host = authority.substringAfter("[").substringBefore("]")
        val groups = host.substringBefore("%").split(":")
        val first = groups.first().toIntOrNull(16) ?: 0
        val loopback = groups.size > 1 && groups.last().toIntOrNull(16) == 1 &&
            groups.dropLast(1).all { it.isEmpty() || it.toIntOrNull(16) == 0 }
        val local = loopback || first in 0xfe80..0xfebf || first in 0xfc00..0xfdff
        return (if (local) "http://" else "https://") + trimmed
    }
    val parts = authority.split(":")
    val host = parts.first()
    val hasPort = parts.size == 2 && parts[1].isNotEmpty() && parts[1].all(Char::isDigit)
    val octets = host.split(".")
    val ipv4 = octets.size == 4 && octets.all { part ->
        part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull()?.let { it in 0..255 } == true)
    }
    val local = host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        host.endsWith(".lan") || (hasPort && !host.contains("."))
    return when {
        local || ipv4 -> "http://$trimmed"
        hasPort || trimmed.contains(".") -> "https://$trimmed"
        else -> searchUrl(trimmed)
    }
}
