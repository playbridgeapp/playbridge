package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPA (same-document) navigations advance a tab's media lifecycle without clearing
 * detections; stale detector messages must not move it. Unique tab ids keep the
 * [VideoDetector] singleton isolated between tests.
 */
class VideoDetectorLifecycleTest {

    @Test
    fun sameDocumentNavigationBumpsLifecycleForCurrentDocumentOnly() {
        val tabId = "spa-lifecycle-bump-${System.nanoTime()}"
        val version = DetectorPageVersion(detectorEpoch = 7L, navigationGeneration = 2L)

        // First contact with the document advances (nothing to clear yet).
        assertTrue(VideoDetector.acceptDetectorVideo(tabId, version))
        assertEquals(0, VideoDetector.lifecycleIndexForTab(tabId))

        assertTrue(VideoDetector.onSameDocumentNavigation(tabId, version, atMs = 1_000_000L))
        assertEquals(1, VideoDetector.lifecycleIndexForTab(tabId))

        assertTrue(VideoDetector.onSameDocumentNavigation(tabId, version, atMs = 1_001_000L))
        assertEquals(2, VideoDetector.lifecycleIndexForTab(tabId))
    }

    @Test
    fun staleSameDocumentNavigationDoesNotBumpLifecycle() {
        val tabId = "spa-lifecycle-stale-${System.nanoTime()}"
        val version = DetectorPageVersion(detectorEpoch = 7L, navigationGeneration = 3L)
        assertTrue(VideoDetector.acceptDetectorVideo(tabId, version))

        val stale = version.copy(navigationGeneration = 1L)
        assertFalse(VideoDetector.onSameDocumentNavigation(tabId, stale, atMs = 1_000_000L))
        assertEquals(0, VideoDetector.lifecycleIndexForTab(tabId))
    }

    @Test
    fun fullNavigationResetsLifecycle() {
        val tabId = "spa-lifecycle-reset-${System.nanoTime()}"
        val first = DetectorPageVersion(detectorEpoch = 7L, navigationGeneration = 4L)
        assertEquals(
            DetectorMessageOrder.ADVANCE,
            VideoDetector.onDetectorNavigation(tabId, first),
        )
        assertTrue(VideoDetector.onSameDocumentNavigation(tabId, first, atMs = 1_000_000L))
        assertEquals(1, VideoDetector.lifecycleIndexForTab(tabId))

        // A real document commit clears the tab and its lifecycle counter.
        val next = first.copy(navigationGeneration = 5L)
        assertEquals(
            DetectorMessageOrder.ADVANCE,
            VideoDetector.onDetectorNavigation(tabId, next),
        )
        assertEquals(0, VideoDetector.lifecycleIndexForTab(tabId))
    }
}
