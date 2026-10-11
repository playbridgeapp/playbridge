package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOptimisticStateTest {
    private var now = 10L
    private fun state() = RemoteOptimisticState { now }

    @Test fun staleStatusDoesNotUndoReleasedSeek() {
        val pending = state().seek(60_000, 120_000)
        now += 1
        val stale = pending.reconcile(10_000, true)
        assertEquals(60_000L, stale.position(10_000))
        assertTrue(stale.hasPending)
        val matched = stale.reconcile(58_500, true)
        assertFalse(matched.hasPending)
        assertEquals(58_500L, matched.position(58_500))
    }
    @Test fun replacementBackwardSeekUsesItsOwnDeadline() {
        val first = state().seek(60_000, 120_000)
        now += 2_000
        val second = first.seek(5_000, 120_000)
        now += 1_000
        assertEquals(5_000L, second.expire().position(90_000))
        now += 2_000
        assertFalse(second.expire().hasPending)
    }
    @Test fun rapidTogglesShareTheDisplayedIntentAndSettleSeparatelyFromSeek() {
        val pending = state().seek(40_000, 100_000).play(false)
        assertFalse(pending.playing(true))
        val again = pending.play(!pending.playing(true))
        assertTrue(again.playing(false))
        now++
        val matchedPlay = again.reconcile(2_000, true)
        assertTrue(matchedPlay.hasPending)
        assertEquals(40_000L, matchedPlay.position(2_000))
        assertFalse(matchedPlay.reconcile(40_000, true).hasPending)
    }
    @Test fun failureAndFreshIdentityDropBothIntents() {
        val pending = state().seek(40_000, 100_000).play(false)
        assertFalse(pending.clear().hasPending)
        assertEquals(3_000L, pending.clear().position(3_000))
        assertTrue(pending.clear().playing(true))
        assertFalse(state().hasPending)
    }
    @Test fun timeoutAndBounds() {
        var pending = state().seek(-10, 100).play(false)
        assertEquals(0L, pending.position(50))
        pending = pending.seek(200, 100)
        assertEquals(100L, pending.position(50))
        now += 2_999
        assertTrue(pending.expire().hasPending)
        now++
        assertFalse(pending.expire().hasPending)
        assertEquals(0L, relativeRemoteSeek(5_000, -10_000, 100_000))
        assertEquals(100_000L, relativeRemoteSeek(95_000, 10_000, 100_000))
        assertEquals(Long.MAX_VALUE, relativeRemoteSeek(Long.MAX_VALUE - 1, 10_000, 0))
    }
}
