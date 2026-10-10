package com.playbridge.sender.ui

/** Stable IDs, not titles or installation order, own dashboard placement. */
internal object DashboardTileOrder {
    const val TILES_PER_PAGE = 8
    const val BROWSER_ID = "browser"
    const val LEGACY_LIBRARY_ID = "library"
    const val STREAMS_APP_ID = "app:https://streams.playbridge.app"

    fun reconcile(saved: List<String>, available: List<String>): List<String> {
        val availableIds = available.toSet()
        return (saved.filter { it in availableIds } + available).distinct()
    }

    /**
     * One-time migration / default ordering:
     * 1. Browser
     * 2. Streams bridged app ("app:https://streams.playbridge.app")
     * 3. Library (legacy) ("library")
     * followed by all other tiles in their existing relative order.
     */
    fun migrateOrder(
        saved: List<String>,
        available: List<String>,
        streamsId: String = STREAMS_APP_ID,
        legacyLibraryId: String = LEGACY_LIBRARY_ID,
        browserId: String = BROWSER_ID,
    ): List<String> {
        val reconciled = reconcile(saved, available)
        val remaining = reconciled.filterNot { it == streamsId || it == legacyLibraryId }
        val hasBrowser = browserId in remaining
        val restWithoutBrowser = if (hasBrowser) remaining.filterNot { it == browserId } else remaining.drop(1)
        val firstItem = if (hasBrowser) browserId else remaining.firstOrNull()

        return buildList {
            if (firstItem != null) add(firstItem)
            if (streamsId in reconciled) add(streamsId)
            if (legacyLibraryId in reconciled) add(legacyLibraryId)
            addAll(restWithoutBrowser)
        }
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
