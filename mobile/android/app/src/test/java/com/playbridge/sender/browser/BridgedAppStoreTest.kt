package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgedAppStoreTest {
    @Test
    fun `accepts secure sites and local development servers without credentials`() {
        assertEquals("https://streams.example", BridgedAppStore.originFor("https://streams.example/watch/123"))
        assertEquals("https://streams.example:8443", BridgedAppStore.originFor("https://streams.example:8443/"))
        assertEquals("http://192.168.1.23:5182", BridgedAppStore.originFor("http://192.168.1.23:5182/"))
        assertEquals("http://localhost:5182", BridgedAppStore.originFor("http://localhost:5182/"))
        assertNull(BridgedAppStore.originFor("http://streams.example/"))
        assertNull(BridgedAppStore.originFor("http://8.8.8.8/"))
        assertNull(BridgedAppStore.originFor("http://192.169.1.23:5182/"))
        assertNull(BridgedAppStore.originFor("https://user:pass@streams.example/"))
        assertNull(BridgedAppStore.originFor("javascript:alert(1)"))
    }

    @Test
    fun `opens external web navigation outside a bridged app`() {
        val origin = "https://jellyfin.example"
        assertFalse(BridgedAppStore.isExternalWebNavigation(origin, "$origin/watch/1"))
        assertTrue(BridgedAppStore.isExternalWebNavigation(origin, "https://login.example/authorize"))
        assertTrue(BridgedAppStore.isExternalWebNavigation(origin, "http://login.example/authorize"))
        assertFalse(BridgedAppStore.isExternalWebNavigation(origin, "about:blank"))
        assertFalse(BridgedAppStore.isExternalWebNavigation(origin, "blob:https://jellyfin.example/123"))
    }
}
