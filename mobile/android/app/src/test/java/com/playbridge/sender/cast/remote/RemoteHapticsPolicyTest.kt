package com.playbridge.sender.cast.remote

import android.os.VibrationEffect
import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHapticsPolicyTest {
    @Test fun allowListHonorsAppSwitch() {
        var enabled = true
        var count = 0
        val haptics = RemoteHaptics({ enabled }, { count++ })
        assertEquals(
            setOf(RemoteFeedback.PAD_STEP, RemoteFeedback.SEEK_BOUND, RemoteFeedback.PLAY_PAUSE, RemoteFeedback.VOLUME, RemoteFeedback.SCRUB),
            RemoteHaptics.ALLOWED,
        )
        RemoteFeedback.entries.forEach(haptics::perform)
        assertEquals(5, count)
        enabled = false
        RemoteFeedback.entries.forEach(haptics::perform)
        assertEquals(5, count)
    }
    @Test fun eachLevelMapsToItsPredefinedEffectAndLegacyViewFeedback() {
        assertEquals(VibrationEffect.EFFECT_TICK, remoteHapticEffectId(RemoteHapticLevel.Light))
        assertEquals(VibrationEffect.EFFECT_CLICK, remoteHapticEffectId(RemoteHapticLevel.Medium))
        assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK, remoteHapticEffectId(RemoteHapticLevel.Strong))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, remoteHapticFallbackConstant(RemoteHapticLevel.Light))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, remoteHapticFallbackConstant(RemoteHapticLevel.Medium))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, remoteHapticFallbackConstant(RemoteHapticLevel.Strong))
    }

    @Test fun boundsOnlyTickOnCrossing() {
        assertTrue(crossedSeekBound(5f, 0f, 100))
        assertTrue(crossedSeekBound(95f, 100f, 100))
        assertFalse(crossedSeekBound(0f, 0f, 100))
        assertFalse(crossedSeekBound(100f, 100f, 100))
        assertFalse(crossedSeekBound(50f, 60f, 100))
        assertFalse(crossedSeekBound(5f, 0f, 0))
    }
    @Test fun secondNonImageTapAddsNoClickAndImageDoubleTapResets() {
        val actions = mutableListOf("click") // First tap is sent immediately by the pointer handler.
        if (remoteDoubleTapAction(false) != "none") actions += remoteDoubleTapAction(false)
        assertEquals(listOf("click"), actions)
        assertEquals("reset", remoteDoubleTapAction(true))
    }

    @Test fun tenSecondCrossingsTickForwardAndBackwardButNotWithinAnInterval() {
        assertTrue(crossedScrubInterval(9_999f, 10_000f))
        assertTrue(crossedScrubInterval(10_000f, 9_999f))
        assertTrue(crossedScrubInterval(19_999f, 20_000f))
        assertTrue(crossedScrubInterval(20_000f, 19_999f))
        assertFalse(crossedScrubInterval(10_001f, 19_999f))
        assertFalse(crossedScrubInterval(19_999f, 10_001f))
        assertFalse(crossedScrubInterval(10_000f, 10_000f))
        assertEquals(RemoteFeedback.SCRUB, scrubFeedback(9_999f, 10_000f, 100_000L, false, 100L, null))
        assertEquals(RemoteFeedback.SCRUB, scrubFeedback(10_000f, 9_999f, 100_000L, false, 100L, null))
        assertNull(scrubFeedback(10_001f, 19_999f, 100_000L, false, 100L, null))
    }

    @Test fun axisLockTicksWithoutWaitingForAnIntervalAndIsTheOnlyTickOnThatEvent() {
        assertEquals(RemoteFeedback.SCRUB, scrubFeedback(1_000f, 2_000f, 100_000L, true, 100L, null))
        assertEquals(RemoteFeedback.SCRUB, scrubFeedback(9_000f, 11_000f, 100_000L, true, 100L, null))
        assertNull(scrubFeedback(1_000f, 2_000f, 100_000L, false, 100L, null))
    }

    @Test fun scrubCrossingsAreRateLimitedUntilFortyMillisecondsIncludingAfterStart() {
        assertNull(scrubFeedback(9_000f, 11_000f, 100_000L, false, 139L, 100L))
        assertEquals(RemoteFeedback.SCRUB, scrubFeedback(19_000f, 21_000f, 100_000L, false, 140L, 100L))
        assertNull(scrubFeedback(21_000f, 19_000f, 100_000L, false, 179L, 140L))
        assertEquals(RemoteFeedback.SCRUB, scrubFeedback(11_000f, 9_000f, 100_000L, false, 180L, 140L))
    }

    @Test fun aFastLongScrubEmitsAtMostOneTickPerEventAndPerFortyMilliseconds() {
        val feedback = mutableListOf<RemoteFeedback>()
        var lastTick: Long? = null
        var previous = 0f
        for (index in 0..8) {
            val next = (index + 1) * 50_000f
            val time = index * 10L
            scrubFeedback(previous, next, 1_000_000L, index == 0, time, lastTick)?.let {
                feedback += it
                lastTick = time
            }
            previous = next
        }
        assertEquals(List(3) { RemoteFeedback.SCRUB }, feedback)
    }

    @Test fun boundsTakePriorityOverIntervalAndStartWithoutDoubleFiringOrRateLimiting() {
        assertEquals(RemoteFeedback.SEEK_BOUND, scrubFeedback(9_000f, 0f, 20_000L, false, 110L, 100L))
        assertEquals(RemoteFeedback.SEEK_BOUND, scrubFeedback(19_000f, 20_000f, 20_000L, false, 110L, 100L))
        assertEquals(RemoteFeedback.SEEK_BOUND, scrubFeedback(19_000f, 20_000f, 20_000L, true, 100L, null))
        assertNull(scrubFeedback(0f, 0f, 20_000L, false, 200L, 100L))
        assertNull(scrubFeedback(20_000f, 20_000f, 20_000L, false, 200L, 100L))
        // The caller records bound ticks too, preventing an immediate nearby interval tick.
        assertNull(scrubFeedback(20_000f, 19_000f, 20_000L, false, 139L, 100L))
        assertEquals(RemoteFeedback.SCRUB, scrubFeedback(20_000f, 19_000f, 20_000L, false, 140L, 100L))
    }

    @Test fun noDurationHasNoScrubTickAndEachGestureStartsWithAFreshClock() {
        assertNull(scrubFeedback(5f, 0f, 0L, true, 100L, null))
        assertNull(scrubFeedback(0f, 20_000f, -1L, true, 100L, null))
        assertEquals(RemoteFeedback.SCRUB, scrubFeedback(1_000f, 2_000f, 100_000L, true, 110L, null))
    }
}
