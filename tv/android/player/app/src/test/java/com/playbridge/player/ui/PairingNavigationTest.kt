package com.playbridge.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNavigationTest {
    @Test
    fun `approved active request opens library`() {
        val transition = PairingNavigationState()
            .onRequest("sender-a")
            .onCompletion("sender-a", approved = true)

        assertTrue(transition.openLibrary)
        assertNull(transition.state.pendingDeviceUUID)
    }

    @Test
    fun `denied active request stays on pairing`() {
        val transition = PairingNavigationState()
            .onRequest("sender-a")
            .onCompletion("sender-a", approved = false)

        assertFalse(transition.openLibrary)
        assertNull(transition.state.pendingDeviceUUID)
    }

    @Test
    fun `stale completion cannot finish a different request`() {
        val state = PairingNavigationState().onRequest("sender-b")
        val transition = state.onCompletion("sender-a", approved = true)

        assertFalse(transition.openLibrary)
        assertEquals(state, transition.state)
    }
}
