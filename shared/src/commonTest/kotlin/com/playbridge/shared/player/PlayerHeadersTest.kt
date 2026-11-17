package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlayerHeadersTest {
    @Test
    fun stripsBrowserAndRangeHeadersCaseInsensitivelyWhileKeepingAuthHeaders() {
        val raw = mapOf(
            "Range" to "bytes=0-",
            "if-range" to "\"etag\"",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-CH-UA" to "\"Chromium\"",
            "Host" to "cdn.example",
            "Referer" to "https://example.com/",
            "Origin" to "https://example.com",
            "Cookie" to "sid=secret",
            "Authorization" to "Bearer token",
            "User-Agent" to "Mozilla/5.0",
        )
        val clean = sanitizePlayerHeaders(raw)
        assertFalse(clean.keys.any { it.equals("Range", ignoreCase = true) })
        assertFalse(clean.keys.any { it.equals("If-Range", ignoreCase = true) })
        assertFalse(clean.keys.any { it.equals("Sec-Fetch-Site", ignoreCase = true) })
        assertFalse(clean.keys.any { it.equals("Host", ignoreCase = true) })
        assertEquals(
            mapOf(
                "Referer" to "https://example.com/",
                "Origin" to "https://example.com",
                "Cookie" to "sid=secret",
                "Authorization" to "Bearer token",
                "User-Agent" to "Mozilla/5.0",
            ),
            clean,
        )
    }
}
