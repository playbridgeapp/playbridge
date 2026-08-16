package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedPageCastCoordinatorTest {
    @Test
    fun `requests only the missing prefetch window`() {
        assertEquals(2, linkedQueueDemand(3, currentIndex = 1, totalCount = 3, false, false, false))
        assertEquals(0, linkedQueueDemand(3, currentIndex = 1, totalCount = 5, false, false, false))
    }

    @Test
    fun `does not overlap pending requests or continue past end`() {
        assertEquals(0, linkedQueueDemand(3, 1, 2, requestPending = true, endOfList = false, awaitingPlaylistEcho = false))
        assertEquals(0, linkedQueueDemand(3, 1, 2, requestPending = false, endOfList = true, awaitingPlaylistEcho = false))
        assertEquals(0, linkedQueueDemand(3, 1, 2, requestPending = false, endOfList = false, awaitingPlaylistEcho = true))
    }

    @Test
    fun `clamps the user prefetch setting`() {
        assertEquals(1, linkedQueueDemand(0, 0, 1, false, false, false))
        assertEquals(10, linkedQueueDemand(99, 0, 1, false, false, false))
    }

    @Test
    fun `accepts a repeated response idempotently`() {
        assertEquals(
            LinkedSupplyDisposition.ACCEPT,
            linkedSupplyDisposition("need-1", "need-1", null),
        )
        assertEquals(
            LinkedSupplyDisposition.ALREADY_ACCEPTED,
            linkedSupplyDisposition("need-1", null, "need-1"),
        )
        assertEquals(
            LinkedSupplyDisposition.STALE,
            linkedSupplyDisposition("need-old", "need-new", null),
        )
    }

    @Test
    fun `new document navigation supersedes only requests from that tab`() {
        assertEquals(true, pageRequestSuperseded(7, 3, 7, 4))
        assertEquals(false, pageRequestSuperseded(7, 3, 8, 4))
        assertEquals(false, pageRequestSuperseded(7, 3, 7, 3))
    }

    @Test
    fun `recent binding heartbeat keeps linked session alive`() {
        val now = 70 * 60 * 1_000L

        assertEquals(
            false,
            linkedSessionExpired(
                nowMillis = now,
                lastActivityAtMillis = now - 60_000L,
                createdAtMillis = 0L,
            ),
        )
    }

    @Test
    fun `linked session expires when binding is idle`() {
        val now = 20 * 60 * 1_000L

        assertEquals(
            true,
            linkedSessionExpired(
                nowMillis = now,
                lastActivityAtMillis = now - 10 * 60 * 1_000L - 1L,
                createdAtMillis = now - 20 * 60 * 1_000L,
            ),
        )
    }

    @Test
    fun `heartbeat cannot extend linked session past absolute lifetime`() {
        val now = 2 * 60 * 60 * 1_000L + 1L

        assertEquals(
            true,
            linkedSessionExpired(
                nowMillis = now,
                lastActivityAtMillis = now,
                createdAtMillis = 0L,
            ),
        )
    }
}
