package com.playbridge.sender.cast.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserHostStateTest {
    @Test
    fun primaryUrlPrefersLanOverLoopback() {
        val state = BrowserHostState(
            running = true,
            urls = listOf(
                "http://127.0.0.1:8770",
                "http://192.168.1.42:8770",
                "http://10.0.0.5:8770",
            ),
            port = 8770,
        )
        assertEquals("http://192.168.1.42:8770", state.primaryUrl)
        assertEquals(
            listOf("http://127.0.0.1:8770", "http://10.0.0.5:8770"),
            state.otherUrls,
        )
    }

    @Test
    fun primaryUrlFallsBackToLoopbackWhenOnlyLocal() {
        val state = BrowserHostState(
            running = true,
            urls = listOf("http://127.0.0.1:8770"),
            port = 8770,
        )
        assertEquals("http://127.0.0.1:8770", state.primaryUrl)
        assertEquals(emptyList<String>(), state.otherUrls)
    }

    @Test
    fun primaryUrlNullWhenEmpty() {
        assertNull(BrowserHostState().primaryUrl)
    }

    @Test
    fun readySessionMapsToWebBrowserDevice() {
        val device = BrowserReadySession(
            sessionId = "sess-1",
            receiverId = "recv-1",
            name = "Safari on Apple TV",
        ).toTvDevice("192.168.1.42", 8770)
        assertEquals("sess-1", device.uuid)
        assertEquals("Safari on Apple TV", device.name)
        assertEquals("192.168.1.42", device.ip)
        assertEquals(8770, device.port)
        assertEquals(com.playbridge.sender.model.CastProtocol.WEB_BROWSER, device.resolvedProtocol)
    }
}
