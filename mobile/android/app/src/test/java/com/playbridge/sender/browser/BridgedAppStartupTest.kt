package com.playbridge.sender.browser

import android.util.JsonWriter
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.EngineState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.EngineSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BridgedAppStartupTest {
    private val app = BridgedApp("https://streams.example", "Streams", "https://streams.example/home#movies", null, "app-tab")
    private val oldPageHistory = object : EngineSessionState {
        override fun writeTo(writer: JsonWriter) = Unit
    }

    @Test
    fun `fresh app tab starts at configured home with no previous history`() {
        val tab = TabSessionState(id = "app-tab", content = ContentState(url = "https://streams.example/watch/123", title = "Episode"), parentId = "old-parent",
            engineState = EngineState(engineSessionState = oldPageHistory))
        val restored = tab.atBridgedAppHome(listOf(app))
        assertEquals(tab.id, restored.id)
        assertEquals(app.startUrl, restored.content.url)
        assertEquals(app.name, restored.content.title)
        assertNull(restored.parentId)
        assertNull(restored.engineState.engineSessionState)
        assertNull(restored.engineState.engineSession)
    }

    @Test
    fun `ordinary browser tabs keep their session and deep link`() {
        val tab = TabSessionState(id = "normal-tab", content = ContentState(url = "https://streams.example/watch/123"), parentId = "parent",
            engineState = EngineState(engineSessionState = oldPageHistory))
        assertSame(tab, tab.atBridgedAppHome(listOf(app)))
        assertSame(tab, tab.atBridgedAppHome(emptyList()))
    }

    @Test
    fun `home edit updates a restored tab before its first open`() {
        val lazyTab = TabSessionState(id = "app-tab", content = ContentState(url = app.startUrl, title = app.name))
        val edited = app.editing("Edited", "https://streams.example/edited-home")!!
        val updated = lazyTab.atEditedBridgedAppHome(listOf(edited))
        assertEquals(edited.startUrl, updated.content.url)
        assertEquals(edited.name, updated.content.title)
        assertEquals(lazyTab.id, updated.id)
    }

    @Test
    fun `home edit keeps hibernated app history and ordinary tabs intact`() {
        val hibernatedTab = TabSessionState(id = "app-tab", content = ContentState(url = "https://streams.example/watch/123"),
            engineState = EngineState(engineSessionState = oldPageHistory))
        val edited = app.editing("Edited", "https://streams.example/edited-home")!!
        assertSame(hibernatedTab, hibernatedTab.atEditedBridgedAppHome(listOf(edited)))
        val normalTab = hibernatedTab.copy(id = "normal-tab", engineState = EngineState())
        assertSame(normalTab, normalTab.atEditedBridgedAppHome(listOf(edited)))
    }

    @Test
    fun `edited home is used even when saved page has left the app origin`() {
        val tab = TabSessionState(id = "app-tab", content = ContentState(url = "https://external.example/"))
        val edited = app.editing("Edited", "https://streams.example/new-home")!!
        assertEquals(edited.startUrl, tab.atBridgedAppHome(listOf(edited)).content.url)
    }
}
