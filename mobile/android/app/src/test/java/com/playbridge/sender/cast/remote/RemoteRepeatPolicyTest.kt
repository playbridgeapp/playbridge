package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRepeatPolicyTest {
    @Test fun immediateDelayCadenceAndCancellation() {
        var now = 0L
        val repeat = RemoteRepeatPolicy { now }
        assertTrue(repeat.press("dpad_left"))
        assertFalse(repeat.next())
        now = 349
        assertFalse(repeat.next())
        now = 350
        assertTrue(repeat.next())
        now = 449
        assertFalse(repeat.next())
        now = 450
        assertTrue(repeat.next())
        repeat.cancel()
        now = 1_000
        assertFalse(repeat.next())
    }
    @Test fun onlyArrowsRepeatAndBurstIsFiniteWithoutCatchup() {
        var now = 0L
        val repeat = RemoteRepeatPolicy { now }
        for (key in listOf("dpad_center", "back", "home", "volume_up", "key_enter")) {
            assertFalse(repeat.press(key))
            now += 1_000
            assertFalse(repeat.next())
        }
        for (key in RemoteRepeatPolicy.ARROWS) {
            assertTrue(repeat.press(key))
            var count = 1
            now += 10_000
            assertTrue(repeat.next()); count++
            assertFalse(repeat.next())
            repeat(RemoteRepeatPolicy.MAX_STEPS) { now += 100; if (repeat.next()) count++ }
            assertEquals(RemoteRepeatPolicy.MAX_STEPS, count)
        }
    }
}
