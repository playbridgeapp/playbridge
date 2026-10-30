package com.playbridge.sender.ui

/** Stable IDs, not titles or installation order, own dashboard placement. */
internal object DashboardTileOrder {
    const val BROWSER_ID = "browser"
    /** Kept from the old "Bridged Apps" placeholder so saved orders still place it. */
    const val ADD_WEB_APP_ID = "bridged-apps"

    /** Browser is how videos are found and web apps are added, so it can't be hidden. */
    val ALWAYS_SHOWN: Set<String> = setOf(BROWSER_ID)

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

    /** Tiles the Dashboard draws: the saved order minus hidden ones, never hiding [ALWAYS_SHOWN]. */
    fun visible(order: List<String>, hidden: Set<String>): List<String> =
        order.filter { it !in hidden || it in ALWAYS_SHOWN }
}
