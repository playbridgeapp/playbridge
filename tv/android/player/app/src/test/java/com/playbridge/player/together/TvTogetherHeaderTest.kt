package com.playbridge.player.together

import com.playbridge.shared.player.sanitizePlayerHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TvTogetherHeaderTest {
    @Test
    fun `sanitizePlayerHeaders strips Range and If-Range from party items while keeping auth`() {
        val raw = mapOf(
            "Range" to "bytes=0-",
            "If-Range" to "\"v1\"",
            "Sec-Fetch-Dest" to "video",
            "Referer" to "https://site.example/watch",
            "Cookie" to "session=abc",
            "Authorization" to "Bearer secret",
            "User-Agent" to "Mozilla/5.0",
        )
        val cleaned = sanitizePlayerHeaders(raw)
        assertFalse(cleaned.containsKey("Range"))
        assertFalse(cleaned.containsKey("If-Range"))
        assertFalse(cleaned.containsKey("Sec-Fetch-Dest"))
        assertEquals(
            mapOf(
                "Referer" to "https://site.example/watch",
                "Cookie" to "session=abc",
                "Authorization" to "Bearer secret",
                "User-Agent" to "Mozilla/5.0",
            ),
            cleaned,
        )
    }
}
