package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressRulesTest {
    @Test fun requiresAtLeastThirtySeconds() {
        assertNull(resumePosition(-1, 600_000))
        assertNull(resumePosition(29_999, 600_000))
        assertEquals(30_000L, resumePosition(30_000, 600_000))
    }

    @Test fun stopsResumingAtExactlyNinetyFivePercent() {
        assertEquals(569_999L, resumePosition(569_999, 600_000))
        assertNull(resumePosition(570_000, 600_000))
        assertNull(resumePosition(600_000, 600_000))
        assertNull(resumePosition(700_000, 600_000))
    }

    @Test fun requiresKnownPositiveDuration() {
        assertNull(resumePosition(30_000, 0))
        assertNull(resumePosition(30_000, -1))
        assertNull(resumePosition(30_000, 20_000))
    }

    @Test fun fractionalBoundaryAndLargeDurationsDoNotRoundOrOverflow() {
        assertEquals(95_000L, resumePosition(95_000, 100_001))
        assertNull(resumePosition(95_001, 100_001))
        assertEquals(30_000L, resumePosition(30_000, Long.MAX_VALUE))
        assertNull(resumePosition(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(8_762_203_435_012_037_016L,
            resumePosition(8_762_203_435_012_037_016L, Long.MAX_VALUE))
        assertNull(resumePosition(8_762_203_435_012_037_017L, Long.MAX_VALUE))
    }
}
