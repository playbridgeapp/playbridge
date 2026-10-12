package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVolumeKeyHandlerTest {
    @Test fun downOsRepeatsAndMatchingUp() {
        val handler = RemoteVolumeKeyHandler()
        val calls = mutableListOf<Boolean>()
        val unregister = handler.register { calls += it }
        assertTrue(handler.handle(24, true, true))
        assertTrue(handler.handle(24, true, true, repeat = true))
        assertTrue(handler.handle(24, false, true))
        assertFalse(handler.handle(24, false, true))
        assertTrue(handler.handle(25, true, true))
        unregister()
        assertTrue(handler.handle(25, true, true, repeat = true))
        assertTrue(handler.handle(25, false, false))
        assertFalse(handler.handle(25, true, true))
        assertEquals(listOf(true, true, false), calls)
    }
    @Test fun otherScreensPausedAndNonVolumeFallThrough() {
        val handler = RemoteVolumeKeyHandler()
        assertFalse(handler.handle(24, true, true))
        handler.register { error("Must not route") }
        assertFalse(handler.handle(24, true, false))
        assertFalse(handler.handle(4, true, true))
        assertFalse(handler.handle(25, false, true))
    }
    @Test fun routeSwitchDoesNotDeliverHeldRepeatsToNewReceiver() {
        val handler = RemoteVolumeKeyHandler()
        var oldCalls = 0
        var newCalls = 0
        handler.register { oldCalls++ }
        handler.handle(24, true, true)
        handler.register { newCalls++ }
        assertTrue(handler.handle(24, true, true, repeat = true))
        assertTrue(handler.handle(24, false, true))
        assertEquals(1, oldCalls)
        assertEquals(0, newCalls)
        handler.handle(24, true, true)
        assertEquals(1, newCalls)
    }
    @Test fun freshDownAfterUnobservedUpCannotGetStuckOrCapturePhoneVolume() {
        val handler = RemoteVolumeKeyHandler()
        var calls = 0
        val unregister = handler.register { calls++ }
        handler.handle(24, true, true)
        unregister()
        // The system received UP outside the app; it is intentionally absent here.
        assertFalse(handler.handle(24, true, true))
        assertFalse(handler.handle(24, false, true))
        handler.register { calls++ }
        assertTrue(handler.handle(24, true, true))
        assertTrue(handler.handle(24, false, true))
        assertEquals(2, calls)
    }
    @Test fun oldRegistrationCannotRemoveReplacement() {
        val handler = RemoteVolumeKeyHandler()
        val old = handler.register { error("Old route") }
        var calls = 0
        handler.register { calls++ }
        old()
        assertTrue(handler.handle(24, true, true))
        assertEquals(1, calls)
    }
}
