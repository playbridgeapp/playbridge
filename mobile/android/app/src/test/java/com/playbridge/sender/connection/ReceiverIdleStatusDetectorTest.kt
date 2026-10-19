package com.playbridge.sender.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverIdleStatusDetectorTest {
    private val detector = ReceiverIdleStatusDetector(holdMs = 3_000)

    @Test fun idleAfterPlaybackFiresOnceAfterHold() {
        assertFalse(detector.onStatus("player", "playing", 0))
        assertFalse(detector.onStatus("player", "idle", 1_000))
        assertFalse(detector.onStatus("player", "idle", 3_500))
        assertTrue(detector.onStatus("player", "idle", 4_000))
        assertFalse(detector.onStatus("player", "idle", 8_000))
    }

    @Test fun idleBeforeAnyPlaybackNeverFires() {
        // A cast that is still opening must not flip the phone to idle.
        assertFalse(detector.onStatus("player", "idle", 0))
        assertFalse(detector.onStatus("player", "idle", 10_000))
    }

    @Test fun briefIdleBetweenItemsDoesNotFire() {
        detector.onStatus("player", "playing", 0)
        detector.onStatus("player", "idle", 1_000)
        assertFalse(detector.onStatus("player", "buffering", 2_000))
        assertFalse(detector.onStatus("player", "idle", 3_000))
        assertFalse(detector.onStatus("player", "idle", 5_500))
        assertTrue(detector.onStatus("player", "stopped", 6_000))
    }

    @Test fun nonPlayerContextsAreIgnored() {
        detector.onStatus("player", "playing", 0)
        assertFalse(detector.onStatus("browser", "idle", 1_000))
        assertFalse(detector.onStatus("screen_mirror", "idle", 5_000))
        // Leaving the player context forgets earlier playback.
        assertFalse(detector.onStatus("player", "idle", 6_000))
        assertFalse(detector.onStatus("player", "idle", 10_000))
    }

    @Test fun endedOrErrorStateIsNotIdle() {
        detector.onStatus("player", "playing", 0)
        detector.onStatus("player", "idle", 1_000)
        assertFalse(detector.onStatus("player", "ended", 2_000))
        assertFalse(detector.onStatus("player", "idle", 3_000))
        assertFalse(detector.onStatus("player", "error", 7_000))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorIdleStatusTest {
    @Test fun receiverStoppedLocallyWithoutContextIdleBecomesIdle() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
        var now = 0L
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { now }
        runCurrent()
        coordinator.startLocalPlaybackSession(null, null, null)
        assertEquals("player", coordinator.tvActiveContext.value)

        messages.emit(status("playing"))
        runCurrent()
        now = 500
        messages.emit(status("idle"))
        runCurrent()
        assertEquals("player", coordinator.tvActiveContext.value)

        now = 3_500
        messages.emit(status("idle"))
        runCurrent()
        assertEquals("idle", coordinator.tvActiveContext.value)
        assertNull(coordinator.tvPlayback.value)
    }

    @Test fun explicitContextIdleStillWins() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        coordinator.startLocalPlaybackSession(null, null, null)
        messages.emit(status("playing"))
        messages.emit("""{"type":"context","active":"idle"}""")
        runCurrent()
        assertEquals("idle", coordinator.tvActiveContext.value)
    }

    private fun status(state: String) =
        """{"type":"status","state":"$state","position":0,"duration":0,"mediaKind":"video"}"""
}
