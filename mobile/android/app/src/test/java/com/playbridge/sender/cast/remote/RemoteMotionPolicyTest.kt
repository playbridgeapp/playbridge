package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMotionPolicyTest {
    @Test fun clocksRequirePlayingResumedScreenOnAndPositiveScale() {
        val active = RemoteMotionPolicy(1f, true, true)
        assertTrue(active.waveRuns(true))
        assertFalse(active.waveRuns(false))
        assertFalse(active.copy(animatorScale = 0f).waveRuns(true))
        assertFalse(active.copy(animatorScale = Float.NaN).animate)
        assertFalse(active.copy(resumed = false).waveRuns(true))
        assertFalse(active.copy(screenOn = false).waveRuns(true))
        assertEquals(0, active.copy(animatorScale = 0f).duration(80))
        assertEquals(160, active.copy(animatorScale = 2f).duration(80))
        assertTrue(active.copy(animatorScale = 0f).copy(animatorScale = 1f).waveRuns(true))
    }
}
