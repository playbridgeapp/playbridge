package com.playbridge.shared.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoTargetSelectorTest {
    @Test fun emptySelectorAndCycleAreSafe() {
        val selector = VideoTargetSelector<String>()
        selector.cycle()
        assertNull(selector.current())
        assertFalse(selector.isActive)
    }

    @Test fun sizeWinsOverTinyPlayingAd() {
        val selector = VideoTargetSelector<String>()
        selector.update("player", 1000.0, false)
        selector.update("ad", 100.0, true)
        assertEquals("player", selector.current())
    }

    @Test fun playingTieBreakIncludesSixtyPercentBoundary() {
        val selector = VideoTargetSelector<String>()
        selector.update("large", 1000.0, false)
        selector.update("small", 599.0, true)
        assertEquals("large", selector.current())
        selector.update("small", 600.0, true)
        assertEquals("small", selector.current())
    }

    @Test fun largestPlayingFrameWinsInitially() {
        val selector = VideoTargetSelector<String>()
        selector.update("small", 600.0, true)
        selector.update("large", 1000.0, true)
        assertEquals("large", selector.current())
    }

    @Test fun hysteresisIncludesOnePointTwoFiveBoundary() {
        val selector = VideoTargetSelector<String>()
        selector.update("incumbent", 1000.0, true)
        assertEquals("incumbent", selector.current())
        selector.update("challenger", 1249.0, true)
        assertEquals("incumbent", selector.current())
        selector.update("challenger", 1250.0, true)
        assertEquals("challenger", selector.current())
    }

    @Test fun newlyPlayingComparableFrameBreaksHysteresis() {
        val selector = VideoTargetSelector<String>()
        selector.update("incumbent", 1000.0, false)
        assertEquals("incumbent", selector.current())
        selector.update("challenger", 700.0, true)
        assertEquals("challenger", selector.current())
    }

    @Test fun scoreChangesDoNotThrashTarget() {
        val selector = VideoTargetSelector<String>()
        selector.update("first", 1000.0, false)
        assertEquals("first", selector.current())
        selector.update("second", 1100.0, false)
        assertEquals("first", selector.current())
        selector.update("second", 900.0, false)
        selector.update("first", 950.0, false)
        assertEquals("first", selector.current())
    }

    @Test fun cyclePinsInInsertionOrderAndWraps() {
        val selector = VideoTargetSelector<String>()
        selector.update("large", 1000.0, true)
        selector.update("small", 100.0, false)
        selector.update("middle", 500.0, true)
        assertEquals("large", selector.current())
        selector.cycle()
        assertEquals("small", selector.current())
        selector.update("large", 2000.0, true)
        assertEquals("small", selector.current())
        selector.cycle()
        assertEquals("middle", selector.current())
        selector.cycle()
        assertEquals("large", selector.current())
    }

    @Test fun removalOfPinnedFrameRestoresAutomaticSelection() {
        val selector = VideoTargetSelector<String>()
        selector.update("large", 1000.0, true)
        selector.update("small", 100.0, false)
        selector.cycle()
        assertEquals("small", selector.current())
        selector.remove("small")
        assertEquals("large", selector.current())
        selector.remove("large")
        assertNull(selector.current())
        assertFalse(selector.isActive)
    }

    @Test fun removalOfIncumbentSelectsRemainingFrame() {
        val selector = VideoTargetSelector<String>()
        selector.update("first", 1000.0, true)
        selector.update("second", 900.0, false)
        assertEquals("first", selector.current())
        selector.remove("first")
        assertEquals("second", selector.current())
    }

    @Test fun navigationResetClearsPinAndHysteresis() {
        val selector = VideoTargetSelector<String>()
        selector.update("first", 1000.0, true)
        selector.update("second", 100.0, false)
        selector.cycle()
        assertEquals("second", selector.current())
        selector.reset()
        assertNull(selector.current())
        assertFalse(selector.isActive)
        selector.update("second", 100.0, false)
        selector.update("first", 1000.0, true)
        assertTrue(selector.isActive)
        assertEquals("first", selector.current())
    }

    @Test fun frameKeysUseValueEqualityForGeckoTabAndFrameIds() {
        data class Frame(val tabId: Int, val frameId: Int)
        val selector = VideoTargetSelector<Frame>()
        selector.update(Frame(1, 0), 1000.0, true)
        selector.update(Frame(1, 0), 900.0, false)
        assertEquals(Frame(1, 0), selector.current())
        selector.remove(Frame(1, 0))
        assertFalse(selector.isActive)
    }
}
