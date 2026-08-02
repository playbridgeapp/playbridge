package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectorPageVersionTest {
    private val tracker = DetectorPageTracker()
    private val tabId = "detector-generation-test"

    @Test
    fun `detection arriving before navigation is not cleared by matching navigation`() {
        val version = DetectorPageVersion(detectorEpoch = 100L, navigationGeneration = 3L)

        assertEquals(DetectorMessageOrder.ADVANCE, tracker.observe(tabId, version))

        assertEquals(
            DetectorMessageOrder.CURRENT,
            tracker.observe(tabId, version),
        )
    }

    @Test
    fun `stale detection from previous document is rejected`() {
        val current = DetectorPageVersion(detectorEpoch = 100L, navigationGeneration = 4L)
        val stale = current.copy(navigationGeneration = 3L)

        assertEquals(
            DetectorMessageOrder.ADVANCE,
            tracker.observe(tabId, current),
        )
        assertEquals(DetectorMessageOrder.STALE, tracker.observe(tabId, stale))
    }

    @Test
    fun `new detector epoch replaces state from older extension background`() {
        val previous = DetectorPageVersion(detectorEpoch = 100L, navigationGeneration = 9L)
        val restarted = DetectorPageVersion(detectorEpoch = 101L, navigationGeneration = 0L)

        assertEquals(
            DetectorMessageOrder.ADVANCE,
            tracker.observe(tabId, previous),
        )
        assertEquals(
            DetectorMessageOrder.ADVANCE,
            tracker.observe(tabId, restarted),
        )
    }
}
