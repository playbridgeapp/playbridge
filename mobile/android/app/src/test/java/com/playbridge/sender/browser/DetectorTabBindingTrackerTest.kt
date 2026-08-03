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
}
