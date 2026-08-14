package com.playbridge.sender.cast.googlecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCastNetworkBindingPolicyTest {
    @Test
    fun `does not bind when selected LAN is already active`() {
        assertFalse(
            googleCastNeedsExplicitNetworkBinding(
                activeNetworkHandle = 139L,
                selectedNetworkHandle = 139L,
            ),
        )
    }

    @Test
    fun `binds when selected LAN differs from active network`() {
        assertTrue(
            googleCastNeedsExplicitNetworkBinding(
                activeNetworkHandle = 137L,
                selectedNetworkHandle = 139L,
            ),
        )
    }

    @Test
    fun `binds when Android has no active network handle`() {
        assertTrue(
            googleCastNeedsExplicitNetworkBinding(
                activeNetworkHandle = null,
                selectedNetworkHandle = 139L,
            ),
        )
    }

    @Test
    fun `never binds an invalid selected network handle`() {
        assertFalse(
            googleCastNeedsExplicitNetworkBinding(
                activeNetworkHandle = 137L,
                selectedNetworkHandle = 0L,
            ),
        )
    }

    @Test
    fun `explicit live stream type is normalized for Cast Core`() {
        assertEquals("LIVE", googleCastStreamType(explicit = "live", inferred = "BUFFERED"))
        assertEquals("BUFFERED", googleCastStreamType(explicit = null, inferred = "BUFFERED"))
    }
}
