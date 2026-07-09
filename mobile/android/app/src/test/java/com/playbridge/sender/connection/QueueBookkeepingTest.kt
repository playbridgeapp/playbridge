package com.playbridge.sender.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueBookkeepingTest {

    @Test
    fun `empty echo leaves known unchanged`() {
        assertEquals(listOf(5, 6), QueueBookkeeping.mergeQueuedEpisodeIndices(listOf(5, 6), emptyList()))
    }

    @Test
    fun `empty known takes echo order`() {
        assertEquals(
            listOf(5, 6, 7),
            QueueBookkeeping.mergeQueuedEpisodeIndices(emptyList(), listOf(5, 6, 7)),
        )
    }

    @Test
    fun `echo superset replaces known in TV order`() {
        // Phone only thought E5 was queued; TV reports E5–E7 in order.
        assertEquals(
            listOf(5, 6, 7),
            QueueBookkeeping.mergeQueuedEpisodeIndices(listOf(5), listOf(5, 6, 7)),
        )
    }

    @Test
    fun `echo order is preserved not sorted by plan index`() {
        // Non-chronological TV queue must stay parallel to playlist positions.
        assertEquals(
            listOf(10, 3, 7),
            QueueBookkeeping.mergeQueuedEpisodeIndices(emptyList(), listOf(10, 3, 7)),
        )
    }

    @Test
    fun `echo de-dupes while keeping first position`() {
        assertEquals(
            listOf(5, 6),
            QueueBookkeeping.mergeQueuedEpisodeIndices(emptyList(), listOf(5, 6, 5)),
        )
    }

    @Test
    fun `partial echo does not shrink known`() {
        // Stale partial playlist_status after disconnect must not drop E7.
        assertEquals(
            listOf(5, 6, 7),
            QueueBookkeeping.mergeQueuedEpisodeIndices(listOf(5, 6, 7), listOf(5, 6)),
        )
    }

    @Test
    fun `partial echo still appends newly seen indices`() {
        assertEquals(
            listOf(5, 6, 8),
            QueueBookkeeping.mergeQueuedEpisodeIndices(listOf(5, 6), listOf(5, 8)),
        )
    }

    @Test
    fun `nextToResolve advances past max queued`() {
        assertEquals(8, QueueBookkeeping.nextToResolveAfter(listOf(5, 6, 7), currentNext = 6))
        assertEquals(10, QueueBookkeeping.nextToResolveAfter(listOf(5, 6, 7), currentNext = 10))
        assertEquals(0, QueueBookkeeping.nextToResolveAfter(emptyList(), currentNext = 0))
    }
}
