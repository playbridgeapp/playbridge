package com.playbridge.player.server

import java.net.BindException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketServerPortSelectionTest {
    @Test
    fun invalidPersistedPortFallsBackToDefaultAndTries32Ports() {
        val candidates = receiverPortCandidates(requestedPort = 0)

        assertEquals(32, candidates.size)
        assertEquals(8765, candidates.first())
        assertEquals(8796, candidates.last())
    }

    @Test
    fun validPersistedPortIsFirstCandidate() {
        assertEquals((9000..9031).toList(), receiverPortCandidates(requestedPort = 9000))
    }

    @Test
    fun candidatesStopAtHighestValidPort() {
        assertEquals(listOf(65534, 65535), receiverPortCandidates(requestedPort = 65534))
    }

    @Test
    fun onlyAddressInUseBindFailuresAreRetryable() {
        val inUse = IllegalStateException(
            "wrapped",
            BindException("bind failed: EADDRINUSE (Address already in use)"),
        )
        val otherBindFailure = BindException("Cannot assign requested address")

        assertTrue(inUse.isAddressAlreadyInUse())
        assertFalse(otherBindFailure.isAddressAlreadyInUse())
        assertFalse(IllegalStateException("TLS configuration failed").isAddressAlreadyInUse())
    }
}
