package com.playbridge.sender.browser

import android.util.JsonWriter
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.EngineState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.EngineSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionRestorePolicyTest {
    private val owner = Any()
    private fun policy() = BrowserSessionRestorePolicy().apply { attach(owner) }
    private val history = object : EngineSessionState {
        override fun writeTo(writer: JsonWriter) = Unit
    }
    private val browser = TabSessionState(id = "browser", content = ContentState(url = "https://example.test/deep"),
        engineState = EngineState(engineSessionState = history))
    private val app = browser.copy(id = "app")
    private val state = BrowserState(tabs = listOf(browser, app), selectedTabId = browser.id)

    @Test fun `cold Dashboard does not restore selected browser or app`() {
        val policy = policy()
        assertNull(policy.tabToRestore(state))
        assertNull(policy.tabToRestore(state.copy(selectedTabId = app.id)))
        assertSame(history, browser.engineState.engineSessionState)
    }
    @Test fun `opening Browser restores only its selected page`() {
        val policy = policy()
        policy.show(owner, browser.id)
        assertSame(browser, policy.tabToRestore(state))
        assertNull(policy.tabToRestore(state.copy(selectedTabId = app.id)))
    }
    @Test fun `opening an app allows its home page`() {
        val policy = policy()
        policy.show(owner, app.id)
        assertSame(app, policy.tabToRestore(state.copy(selectedTabId = app.id)))
    }
    @Test fun `closing selected tab in Tabs does not load fallback page`() {
        val policy = policy()
        policy.show(owner, browser.id)
        policy.show(owner, null) // Tabs is visible; existing engines remain independently owned by the store.
        assertNull(policy.tabToRestore(state.copy(tabs = listOf(app), selectedTabId = app.id)))
    }
    @Test fun `shutdown fences already queued state snapshots`() {
        val policy = policy()
        policy.show(owner, browser.id)
        policy.show(owner, null)
        assertNull(policy.tabToRestore(state))
        assertSame(history, state.tabs.first().engineState.engineSessionState)
    }
    @Test fun `page may reopen after Dashboard without resetting history`() {
        val policy = policy()
        policy.show(owner, browser.id)
        policy.show(owner, null)
        policy.show(owner, browser.id)
        assertSame(browser, policy.tabToRestore(state))
        assertSame(history, policy.tabToRestore(state)!!.engineState.engineSessionState)
    }
    @Test fun `selection of a different tab cannot restore stale visible page`() {
        val policy = policy()
        policy.show(owner, browser.id)
        val switched = state.copy(selectedTabId = app.id)
        assertNull(policy.tabToRestore(switched))
        policy.show(owner, app.id)
        assertSame(app, policy.tabToRestore(switched))
    }
    @Test fun `removed tab and absent selection never restore`() {
        val policy = policy()
        policy.show(owner, browser.id)
        assertNull(policy.tabToRestore(state.copy(tabs = listOf(app))))
        assertNull(policy.tabToRestore(state.copy(selectedTabId = null)))
    }
    @Test fun `finished host cannot reenable restoration from a stale composition`() {
        val policy = policy()
        policy.show(owner, browser.id)
        assertTrue(policy.detach(owner))
        policy.show(owner, browser.id)
        assertNull(policy.tabToRestore(state))
    }
    @Test fun `old host cannot hide or load pages owned by its replacement`() {
        val policy = policy()
        val replacement = Any()
        policy.attach(replacement)
        policy.show(replacement, browser.id)
        policy.show(owner, app.id)
        assertFalse(policy.detach(owner))
        assertSame(browser, policy.tabToRestore(state))
    }
}
