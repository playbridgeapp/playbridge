package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserTabSelectionTest {
    private val browserTabs = (1..5).map { "tab-$it" }
    private val appTabs = setOf("streams", "jellyfin")
    private val allTabs = browserTabs + appTabs

    @Test
    fun `saving while in an app restores the fifth browser tab on cold launch`() {
        val savedSelection = resolveBrowserTabSelection(allTabs, "streams", appTabs, "tab-5")
        assertEquals("tab-5", savedSelection)
        // A fresh launch has no Compose previous-tab state; the persisted selection suffices.
        assertEquals("tab-5", resolveBrowserTabSelection(allTabs, savedSelection, appTabs, null))
    }

    @Test
    fun `legacy saved app selection uses the persisted previous browser tab`() {
        assertEquals("tab-5", resolveBrowserTabSelection(allTabs, "streams", appTabs, "tab-5"))
        assertEquals("tab-5", resolveBrowserTabSelection(allTabs, "jellyfin", appTabs, "tab-5"))
    }

    @Test
    fun `current browser selection supersedes an older remembered tab`() {
        assertEquals("tab-3", resolveBrowserTabSelection(allTabs, "tab-3", appTabs, "tab-5"))
    }

    @Test
    fun `closed browser tab falls back to the first remaining regular tab`() {
        assertEquals("tab-1", resolveBrowserTabSelection(allTabs - "tab-5", "streams", appTabs, "tab-5"))
    }

    @Test
    fun `invalid and hidden previous selections never restore an app as a browser tab`() {
        assertEquals("tab-1", resolveBrowserTabSelection(allTabs, "missing", appTabs, "streams"))
        assertEquals("tab-1", resolveBrowserTabSelection(allTabs, null, appTabs, "missing"))
        assertNull(resolveBrowserTabSelection(appTabs.toList(), "streams", appTabs, "jellyfin"))
        assertNull(resolveBrowserTabSelection(emptyList(), null, appTabs, "tab-5"))
    }
}
