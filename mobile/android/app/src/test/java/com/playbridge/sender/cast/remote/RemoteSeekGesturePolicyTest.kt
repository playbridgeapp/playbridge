package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSeekGesturePolicyTest {
    private fun gesture(x: Float = 200f) = RemoteSeekGesturePolicy(400f, 52f, 120_000L, 60_000L, x, 8f)

    @Test fun holdingBeforeDraggingScrubsAndTracksTheInsetSpan() {
        val policy = gesture()
        assertTrue(policy.scrubs(RemoteSeekGesturePolicy.HOLD_MS))
        assertEquals(RemoteSeekAxis.Horizontal, policy.lockAxis(10f, 0f))
        assertEquals(90_000L, policy.positionAt(274f, scrub = true))
        assertEquals(0L, policy.positionAt(0f, scrub = true))
        assertEquals(120_000L, policy.positionAt(400f, scrub = true))
    }
    @Test fun aQuickSwipeSeeksRelativelyEvenWhenItStartsOnThePlayhead() {
        val policy = gesture()
        assertFalse(policy.scrubs(RemoteSeekGesturePolicy.HOLD_MS - 1))
        assertEquals(60_000L, policy.positionAt(200f))
        assertEquals(72_000L, policy.positionAt(240f))
        assertEquals(0L, policy.positionAt(-500f))
    }
    @Test fun touchSlopLeavesTapsAloneAndAxisStaysLockedAcrossDirectionChanges() {
        val policy = gesture()
        assertEquals(null, policy.lockAxis(8f, 8f))
        assertEquals(RemoteSeekAxis.Vertical, policy.lockAxis(3f, -10f))
        assertEquals(RemoteSeekAxis.Horizontal, policy.lockAxis(10f, 3f))
        assertEquals(RemoteSeekAxis.Vertical, policy.lockAxis(100f, 0f, RemoteSeekAxis.Vertical))
        assertEquals(RemoteSeekAxis.Horizontal, policy.lockAxis(0f, 100f, RemoteSeekAxis.Horizontal))
    }
    @Test fun invalidGeometryCannotSeekAndLongRelativeRangeIsBounded() {
        assertFalse(gesture().copy(durationMs = 0).scrubs(10_000L))
        assertEquals(60_000L, gesture().copy(widthPx = 0f).positionAt(0f))
        val long = gesture(100f).copy(durationMs = 12_000_000L)
        assertEquals(660_000L, long.positionAt(500f))
    }
    @Test fun hintsRequireOnlyGesturesAvailableInTheCurrentMode() {
        assertFalse(RemoteLearnedGesture.hintLearned(emptySet(), false, false))
        assertTrue(RemoteLearnedGesture.hintLearned(setOf("tap"), false, false))
        assertFalse(RemoteLearnedGesture.hintLearned(setOf("tap", "seek"), true, true))
        assertTrue(RemoteLearnedGesture.hintLearned(setOf("tap", "seek", "volume"), true, true))
    }
}
