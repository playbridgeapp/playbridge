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
    fun `home edits trim values and preserve installed identity and session`() {
        val app = BridgedApp("https://streams.example", "Streams", "https://streams.example/", "https://streams.example/icon.png", "app-tab")
        val edited = app.editing(" My Streams ", " https://streams.example/home?tab=movies#watch ")!!
        assertEquals("My Streams", edited.name)
        assertEquals("https://streams.example/home?tab=movies#watch", edited.startUrl)
        assertEquals(app.origin, edited.origin)
        assertEquals(app.iconUrl, edited.iconUrl)
        assertEquals(app.tabId, edited.tabId)
        assertNull(app.editing(" ", app.startUrl))
        assertNull(app.editing("x".repeat(61), app.startUrl))
        for (url in listOf("https://other.example/", "https://user:pass@streams.example/", "http://streams.example/", "javascript:alert(1)", "not a url")) {
            assertNull(app.editing("Streams", url))
        }
        assertEquals(app, app.editing(app.name, app.startUrl))
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
