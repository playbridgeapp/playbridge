package com.playbridge.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestGateTest {

    @Test
    fun `newer numbered requests replace older requests`() {
        val gate = PlaybackRequestGate()

        assertTrue(gate.accept(1L))
        assertTrue(gate.accept(3L))
        assertFalse(gate.accept(2L))
        assertFalse(gate.accept(3L))
        assertTrue(gate.accept(4L))
    }

    @Test
    fun `legacy requests remain accepted without changing numbered ordering`() {
        val gate = PlaybackRequestGate()

        assertTrue(gate.accept(5L))
        assertTrue(gate.accept(null))
        assertFalse(gate.accept(4L))
        assertTrue(gate.accept(6L))
    }
}
