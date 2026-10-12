package com.playbridge.sender.cast.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteLearnedHintTest {
    @Test fun learnedHintDropsTheSeparatorWhenDurationIsUnknown() {
        assertNull(learnedTimeHint(positionMs = 0L, durationMs = 0L, isLive = false))
    }

    @Test fun learnedHintUsesLeftWithoutAMinusSign() {
        assertEquals("24:25 left", learnedTimeHint(positionMs = 18 * 60_000L + 29_000L, durationMs = 42 * 60_000L + 54_000L, isLive = false))
        assertEquals("1:30 left", learnedTimeHint(positionMs = 30_000L, durationMs = 120_000L, isLive = false))
    }

    @Test fun learnedHintKeepsLiveWithoutADanglingSeparator() {
        assertEquals("Live", learnedTimeHint(positionMs = 0L, durationMs = 0L, isLive = true))
    }
}
