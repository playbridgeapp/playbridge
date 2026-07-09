package com.playbridge.sender.connection

/**
 * Pure helpers for [TvQueueCoordinator] queue bookkeeping so they can be unit-tested
 * without a live WebSocket or Android runtime.
 */
object QueueBookkeeping {
    /**
     * Align the phone's plan-index list (parallel to TV queue positions) with a
     * [playlist_status] echo.
     *
     * [echoedInOrder] must be in **TV playlist order** (not sorted by plan index).
     * Never shrinks what we already believe is queued (stale partial echoes must not
     * rewind [nextToResolve] bookkeeping); may append newly seen plan indices.
     *
     * @return the merged list, or [known] unchanged when [echoedInOrder] is empty.
     */
    fun mergeQueuedEpisodeIndices(known: List<Int>, echoedInOrder: List<Int>): List<Int> {
        if (echoedInOrder.isEmpty()) return known
        val echoed = echoedInOrder.fold(mutableListOf<Int>()) { acc, idx ->
            if (idx !in acc) acc.add(idx)
            acc
        }
        if (known.isEmpty()) return echoed
        // Prefer echo order when it covers everything we already know (reconnect after
        // under-estimate). Otherwise keep known order and append newly seen indices.
        return if (echoed.containsAll(known)) {
            echoed
        } else {
            val out = known.toMutableList()
            for (idx in echoed) {
                if (idx !in out) out.add(idx)
            }
            out
        }
    }

    /** Next plan index to resolve after [merged] is already on the TV. */
    fun nextToResolveAfter(merged: List<Int>, currentNext: Int): Int {
        val maxQueued = merged.maxOrNull() ?: return currentNext
        return if (currentNext <= maxQueued) maxQueued + 1 else currentNext
    }
}
