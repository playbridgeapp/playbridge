package com.playbridge.sender.ui

/** Stable IDs, not titles or installation order, own dashboard placement. */
internal object DashboardTileOrder {
    const val TILES_PER_PAGE = 8

    fun reconcile(saved: List<String>, available: List<String>): List<String> {
        val availableIds = available.toSet()
        return (saved.filter { it in availableIds } + available).distinct()
    }

    /** [position] is the final, zero-based position (not an insertion offset). */
    fun move(ids: List<String>, id: String, position: Int): List<String> {
        if (id !in ids || position !in ids.indices) return ids
        return ids.toMutableList().apply {
            remove(id)
            add(position, id)
        }
    }

    fun pageCount(tileCount: Int): Int = ((tileCount + TILES_PER_PAGE - 1) / TILES_PER_PAGE).coerceAtLeast(1)
}
