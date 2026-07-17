package com.playbridge.shared.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
