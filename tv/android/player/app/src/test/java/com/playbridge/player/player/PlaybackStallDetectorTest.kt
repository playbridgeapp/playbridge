package com.playbridge.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallDetectorTest {
    @Test fun startupAndMidPlayUseSeparateBudgetsAndReportOnce() {
        for ((firstFrame, timeout) in listOf(false to 30_000L, true to 45_000L)) {
            val detector = PlaybackStallDetector()
            assertFalse(detector.sample(0, true, true, firstFrame, 0, 0))
            assertFalse(detector.sample(timeout - 1, true, true, firstFrame, 0, 0))
            assertTrue(detector.sample(timeout, true, true, firstFrame, 0, 0))
            assertFalse(detector.sample(timeout + 1, true, true, firstFrame, 0, 0))
        }
    }
    @Test fun positionBufferGrowthPauseSeekAndRecoveryResetTheClock() {
        for (reason in listOf("position", "buffer", "pause", "seek", "playing")) {
            val d = PlaybackStallDetector()
            d.sample(0, true, true, false, 0, 0)
            val p = if (reason == "position") 1L else 0L
            val b = if (reason == "buffer") 1L else 0L
            assertFalse(d.sample(29_000, reason != "playing", reason != "pause", false, p, b, reason == "seek"))
            assertFalse(d.sample(30_000, true, true, false, p, b))
            assertFalse(d.sample(58_999, true, true, false, p, b))
            assertTrue(d.sample(60_000, true, true, false, p, b))
        }
    }
    @Test fun userPauseNeverTimesOutAndNewSessionGetsItsOwnBudget() {
        val d = PlaybackStallDetector()
        d.sample(0, true, false, false, 0, 0)
        assertFalse(d.sample(100_000, true, false, false, 0, 0))
        d.sample(100_000, true, true, false, 0, 0)
        d.reset()
        assertFalse(d.sample(130_000, true, true, false, 0, 0))
        assertTrue(d.sample(160_000, true, true, false, 0, 0))
    }
}
