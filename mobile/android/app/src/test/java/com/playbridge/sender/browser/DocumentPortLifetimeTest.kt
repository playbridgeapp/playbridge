package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentPortLifetimeTest {
    @Test fun `revocation and queued transport disconnect clean up once`() {
        var cleaned = 0
        val port = DocumentPortLifetime { cleaned++ }
        assertFalse(port.closed)
        assertTrue(port.close())
        assertTrue(port.closed)
        assertFalse(port.close())
        assertEquals(1, cleaned)
    }
    @Test fun `cleanup reentry already sees revoked authority`() {
        lateinit var port: DocumentPortLifetime
        var cleaned = 0
        port = DocumentPortLifetime {
            assertTrue(port.closed)
            assertFalse(port.close())
            cleaned++
        }
        assertTrue(port.close())
        assertEquals(1, cleaned)
    }
    @Test fun `failed cleanup cannot resurrect document authority`() {
        val port = DocumentPortLifetime { throw IllegalStateException("fixture") }
        try { port.close(); fail("Expected fixture failure") } catch (_: IllegalStateException) { }
        assertTrue(port.closed)
        assertFalse(port.close())
    }
}
