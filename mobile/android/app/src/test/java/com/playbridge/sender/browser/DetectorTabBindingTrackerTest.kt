package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectorTabBindingTrackerTest {
    private val tracker = DetectorTabBindingTracker()

    @Test
    fun `existing binding survives cross-origin committed navigation`() {
        val tabs = listOf(DetectorTabCandidate("opener", "https://site.example/watch"))

        assertEquals(
            "opener",
            tracker.resolve(10L, 4, listOf("https://site.example/watch"), tabs, "opener"),
        )
        assertEquals(
            "opener",
            tracker.resolve(10L, 4, listOf("https://other.example/video"), tabs, "opener"),
        )
    }

    @Test
    fun `blocked popup cannot take the Kotlin tab already owned by its opener`() {
        val tabs = listOf(DetectorTabCandidate("opener", "https://site.example/watch"))
        tracker.resolve(10L, 4, listOf("https://site.example/watch"), tabs, "opener")

        assertNull(
            tracker.resolve(10L, 9, listOf("https://site.example/watch"), tabs, "opener"),
        )
    }

    @Test
    fun `unknown popup URL is not assigned to selected tab`() {
        val tabs = listOf(DetectorTabCandidate("opener", "https://site.example/watch"))

        assertNull(
            tracker.resolve(10L, 9, listOf("https://ads.example/popup"), tabs, "opener"),
        )
    }

    @Test
    fun `previous committed URL can establish binding before store URL changes`() {
        val tabs = listOf(DetectorTabCandidate("opener", "https://site.example/watch"))

        assertEquals(
            "opener",
            tracker.resolve(
                10L,
                4,
                listOf("https://site.example/watch", "https://other.example/video"),
                tabs,
                "opener",
            ),
        )
    }

    @Test
    fun `new detector epoch replaces old numeric tab bindings`() {
        val firstTabs = listOf(DetectorTabCandidate("first", "https://one.example"))
        val secondTabs = listOf(DetectorTabCandidate("second", "https://two.example"))
        tracker.resolve(10L, 4, listOf("https://one.example"), firstTabs, "first")

        assertEquals(
            "second",
            tracker.resolve(11L, 4, listOf("https://two.example"), secondTabs, "second"),
        )
        assertNull(
            tracker.resolve(10L, 4, listOf("https://one.example"), firstTabs, "first"),
        )
    }

    @Test
    fun `legacy messages require an exact URL match`() {
        val tabs = listOf(DetectorTabCandidate("selected", "https://site.example/watch"))

        assertNull(
            tracker.resolveLegacy(
                listOf("https://ads.example/popup"),
                tabs,
                "selected",
            ),
        )
    }
    @Test
    fun `current navigation document cannot bind previous or origin URL alone`() {
        val tabs = listOf(DetectorTabCandidate("opener", "https://site.example/watch"))
        assertNull(tracker.resolve(
            10L, 9, listOf("https://site.example/watch", "https://other.example/video"),
            tabs, "opener", documentUrl = "https://other.example/video",
        ))
    }

    @Test
    fun `newest native tab breaks background duplicate URL tie`() {
        val tabs = listOf(
            DetectorTabCandidate("old", "https://site.example/watch"),
            DetectorTabCandidate("new", "https://site.example/watch"),
        )
        tracker.registerCreatedTab("old", "https://site.example/watch", null)
        tracker.registerCreatedTab("new", "https://site.example/watch", null)
        assertEquals("new", tracker.resolve(10L, 9, listOf("https://site.example/watch"), tabs, null))
    }

    @Test
    fun `closed detector cannot rebind and old epoch close cannot retire current binding`() {
        val tabs = listOf(DetectorTabCandidate("tab", "https://site.example/watch"))
        tracker.resolve(10L, 9, listOf("https://site.example/watch"), tabs, "tab")
        assertNull(tracker.forgetDetectorTab(9L, 9))
        assertEquals("tab", tracker.resolve(10L, 9, listOf("https://site.example/watch"), tabs, "tab"))
        assertEquals("tab", tracker.forgetDetectorTab(10L, 9))
        assertNull(tracker.resolve(10L, 9, listOf("https://site.example/watch"), tabs, "tab"))
        assertEquals("tab", tracker.resolve(11L, 9, listOf("https://site.example/watch"), tabs, "tab"))
    }

    @Test
    fun `blank unrelated selected tab does not receive an unknown detector`() {
        val tabs = listOf(
            DetectorTabCandidate("opener", "https://site.example/watch"),
            DetectorTabCandidate("blank", "about:blank"),
        )
        tracker.registerCreatedTab("blank", "about:blank", null)
        assertNull(tracker.resolve(10L, 9, listOf("https://ads.example/popup"), tabs, "blank"))
    }

    @Test
    fun `close in newer epoch fences late messages even before hello`() {
        val tabs = listOf(DetectorTabCandidate("tab", "https://site.example/watch"))
        tracker.resolve(10L, 9, listOf("https://site.example/watch"), tabs, "tab")
        org.junit.Assert.assertTrue(tracker.acceptEpoch(11L))
        assertNull(tracker.forgetDetectorTab(11L, 12))
        assertNull(tracker.resolve(11L, 12, listOf("https://site.example/watch"), tabs, "tab"))
        assertNull(tracker.resolve(10L, 9, listOf("https://site.example/watch"), tabs, "tab"))
    }

    @Test
    fun `message for tab just pruned cannot bind another tab with its URL`() {
        val original = listOf(DetectorTabCandidate("closed", "https://site.example/watch"))
        tracker.resolve(10L, 9, listOf("https://site.example/watch"), original, "closed")
        val remaining = listOf(DetectorTabCandidate("remaining", "https://site.example/watch"))
        assertNull(tracker.resolve(10L, 9, listOf("https://site.example/watch"), remaining, "remaining"))
        assertEquals("remaining", tracker.resolve(10L, 12, listOf("https://site.example/watch"), remaining, "remaining"))
    }

}
