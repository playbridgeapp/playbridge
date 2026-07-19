package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSessionCoordinatorTest {
    @Test
    fun sessionIdsRemainMonotonicAcrossHostRecreation() {
        val firstHostSession = RendererSessionCoordinator().begin(RendererKind.EXO)
        val recreatedHostSession = RendererSessionCoordinator().begin(RendererKind.EXO)

        assertTrue(recreatedHostSession.sessionId > firstHostSession.sessionId)
    }

    @Test
    fun staleCallbacksCannotChangeTheCurrentSession() {
        val coordinator = RendererSessionCoordinator()
        val first = coordinator.begin(RendererKind.MPV)
        val second = coordinator.begin(RendererKind.EXO)

        assertFalse(coordinator.markReady(first.sessionId))
        assertEquals(second, coordinator.current())
        assertTrue(coordinator.markReady(second.sessionId))
    }

    @Test
    fun firstFrameMovesReadySessionToPlaying() {
        val coordinator = RendererSessionCoordinator()
        val session = coordinator.begin(RendererKind.MPV)

        assertTrue(coordinator.markReady(session.sessionId))
        assertEquals(RendererSessionPhase.READY, coordinator.current().phase)
        assertTrue(coordinator.markFirstFrame(session.sessionId))
        assertEquals(RendererSessionPhase.PLAYING, coordinator.current().phase)
    }

    @Test
    fun endEventsAreRejectedUntilTheCurrentSessionIsReady() {
        val coordinator = RendererSessionCoordinator()
        val outgoing = coordinator.begin(RendererKind.MPV)
        assertTrue(coordinator.markReady(outgoing.sessionId))
        assertTrue(coordinator.canHandleEnded(outgoing.sessionId))

        val replacement = coordinator.begin(RendererKind.MPV)
        assertFalse(coordinator.canHandleEnded(outgoing.sessionId))
        assertFalse(coordinator.canHandleEnded(replacement.sessionId))

        assertTrue(coordinator.markReady(replacement.sessionId))
        assertTrue(coordinator.canHandleEnded(replacement.sessionId))
    }

    @Test
    fun pendingSeekKeepsRequestedPositionUntilRendererCatchesUp() {
        val tracker = PendingSeekTracker(confirmationToleranceMs = 2_000L, timeoutMs = 10_000L)
        tracker.start(sessionId = 7L, originMs = 30_000L, targetMs = 40_000L, nowMs = 100L)

        assertEquals(40_000L, tracker.displayPosition(7L, 30_500L, 200L))
        assertTrue(tracker.isPending(7L))
        assertEquals(38_500L, tracker.displayPosition(7L, 38_500L, 300L))
        assertFalse(tracker.isPending(7L))
    }

    @Test
    fun pendingSeekEventuallyAcceptsRendererPosition() {
        val tracker = PendingSeekTracker(confirmationToleranceMs = 1_000L, timeoutMs = 5_000L)
        tracker.start(sessionId = 9L, originMs = 50_000L, targetMs = 20_000L, nowMs = 1_000L)

        assertEquals(20_000L, tracker.displayPosition(9L, 49_000L, 2_000L))
        assertEquals(49_000L, tracker.displayPosition(9L, 49_000L, 6_000L))
        assertFalse(tracker.isPending(9L))
    }

    @Test
    fun stopAndFailureAreSessionScoped() {
        val coordinator = RendererSessionCoordinator()
        val session = coordinator.begin(RendererKind.EXO)

        assertTrue(coordinator.requestStop(session.sessionId))
        assertTrue(coordinator.markStopped(session.sessionId))
        assertFalse(coordinator.markFailed(session.sessionId, "late failure"))
        assertEquals(RendererSessionPhase.IDLE, coordinator.current().phase)
    }
}
