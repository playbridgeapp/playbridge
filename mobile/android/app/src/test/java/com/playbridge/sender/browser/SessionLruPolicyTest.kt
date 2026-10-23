package com.playbridge.sender.browser

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLruPolicyTest {
    @Test
    fun recentBridgedAppSurvivesBrowsingOtherTabs() {
        // Streams was used, then six ordinary tabs; limit five.
        val lru = listOf("streams", "a", "b", "c", "d", "e", "f")
        val live = SessionLruPolicy.retain(lru, maxAlive = 5, selectedTabId = "f", playingTabIds = emptySet(), keptBridgedAppTabId = "streams")
        assertEquals(listOf("streams", "c", "d", "e", "f"), live)
    }

    @Test
    fun withoutAKeptAppTheOldestTabsGoFirst() {
        val lru = listOf("streams", "a", "b", "c", "d", "e", "f")
        val live = SessionLruPolicy.retain(lru, maxAlive = 5, selectedTabId = "f", playingTabIds = emptySet(), keptBridgedAppTabId = null)
        assertEquals(listOf("b", "c", "d", "e", "f"), live)
    }

    @Test
    fun theLimitStillHoldsWithTheKeptApp() {
        val lru = (1..10).map { "t$it" } + "streams"
        val live = SessionLruPolicy.retain(lru, maxAlive = 3, selectedTabId = "t10", playingTabIds = emptySet(), keptBridgedAppTabId = "streams")
        assertEquals(3, live.size)
        assertTrue("streams" in live && "t10" in live)
    }

    @Test
    fun selectedPlayingAndKeptTabsAreNeverCulledEvenOverTheLimit() {
        val lru = listOf("streams", "music", "video", "now")
        val live = SessionLruPolicy.retain(lru, maxAlive = 1, selectedTabId = "now", playingTabIds = setOf("music", "video"), keptBridgedAppTabId = "streams")
        assertEquals(lru, live)
    }

    @Test
    fun onlyTheMostRecentBridgedAppIsKept() {
        val bridged = setOf("streams", "jellyfin")
        val open = setOf("streams", "jellyfin", "a")
        assertEquals("jellyfin", SessionLruPolicy.keptBridgedApp("streams", "jellyfin", bridged, open))
        // Selecting an ordinary tab keeps the previous choice.
        assertEquals("jellyfin", SessionLruPolicy.keptBridgedApp("jellyfin", "a", bridged, open))
        // A closed or uninstalled app is forgotten.
        assertNull(SessionLruPolicy.keptBridgedApp("jellyfin", "a", setOf("streams"), open))
        assertNull(SessionLruPolicy.keptBridgedApp("jellyfin", "a", bridged, setOf("streams", "a")))
    }

    @Suppress("DEPRECATION")
    @Test
    fun onlyRealMemoryPressureReleasesTheKeptApp() {
        assertTrue(BridgedAppMemoryPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(BridgedAppMemoryPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(BridgedAppMemoryPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
        // Leaving the app or its UI is routine, not pressure.
        assertFalse(BridgedAppMemoryPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(BridgedAppMemoryPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    }
}
