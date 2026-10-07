package com.playbridge.shared.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSanitizerTest {
    @Test
    fun removesCredentialsHostPathQueryAndFragment() {
        val original = "https://user:secret@media.example/video/token.mp4?signature=signed#fragment"

        val sanitized = redactUrlForLog(original)

        assertEquals("https://<redacted>", sanitized)
        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("media.example"))
        assertFalse(sanitized.contains("signature"))
    }

    @Test
    fun preservesOnlyAValidScheme() {
        assertEquals("file://<redacted>", redactUrlForLog("file:///storage/emulated/0/private.mp4"))
        assertEquals("<redacted-url>", redactUrlForLog("not a URL containing a token"))
        assertEquals("<no-url>", redactUrlForLog(null))
    }
}

class LogTextSanitizerTest {
    @Test
    fun redactsUrlsHeadersAndTokensInsideLogLines() {
        val line = "Playing https://real-debrid.example/d/ABC123/movie.mkv?token=dl-secret with " +
            "headers {Authorization=Bearer rd-api-secret-1234, Cookie: sid=cookie-secret; " +
            "\"X-Plex-Token\":\"plex-secret\"} api_key=key-secret password: hunter2 " +
            "fallback hls+https://cdn.example/a.m3u8"

        val sanitized = redactLogText(line)

        for (secret in listOf("real-debrid", "ABC123", "dl-secret", "rd-api-secret", "cookie-secret",
            "plex-secret", "key-secret", "hunter2", "cdn.example")) {
            assertFalse(sanitized.contains(secret), "$secret leaked: $sanitized")
        }
        assertTrue(sanitized.startsWith("Playing https://<redacted> with headers"))
    }

    @Test
    fun leavesOrdinaryDiagnosticsReadable() {
        val line = "ExoPlayer state=READY position=1234ms codec=avc1 error=none"
        assertEquals(line, redactLogText(line))
    }
}
