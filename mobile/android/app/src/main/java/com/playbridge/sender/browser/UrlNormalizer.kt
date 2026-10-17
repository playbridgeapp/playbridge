package com.playbridge.sender.browser

import java.net.URLEncoder

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
    return when {
        trimmed.isEmpty() -> "about:blank"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("about:") -> trimmed
        trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
        else -> searchUrl(trimmed)
    }
}
