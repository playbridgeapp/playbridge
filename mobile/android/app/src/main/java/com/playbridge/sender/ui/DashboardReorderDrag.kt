package com.playbridge.sender.ui

/** Actual measured row bounds; row height can change with accessibility text size. */
internal data class DashboardReorderRowBounds(
    val id: String,
    val index: Int,
    val offset: Int,
    val size: Int,
) {
    val center: Float get() = offset + size / 2f
}

internal object DashboardReorderDrag {
    /** Swap only after crossing another row's center, avoiding midpoint oscillation. */
    fun targetIndex(
        id: String,
        order: List<String>,
        rows: List<DashboardReorderRowBounds>,
        draggedCenter: Float,
    ): Int? {
        val current = rows.find { it.id == id } ?: return null
        // Lazy layout catches up on the next frame after a move. Do not swap twice
        // against the old layout, or the tile can bounce back to its previous slot.
        if (order.getOrNull(current.index) != id) return null
        val candidates = rows.filter { order.getOrNull(it.index) == it.id }
        return when {
            draggedCenter > current.center -> candidates
                .filter { it.index > current.index && it.center <= draggedCenter }
                .maxOfOrNull { it.index }
            draggedCenter < current.center -> candidates
                .filter { it.index < current.index && it.center >= draggedCenter }
                .minOfOrNull { it.index }
            else -> null
        }
    }

    /** Pixels per second, not per frame; scroll speed follows edge proximity. */
    fun edgeScrollSpeed(center: Float, start: Int, end: Int, edgeSize: Float, maxSpeed: Float): Float {
        if (end <= start || edgeSize <= 0f) return 0f
        val edge = edgeSize.coerceAtMost((end - start) / 2f)
        return when {
            center < start + edge -> -maxSpeed * ((start + edge - center) / edge).coerceIn(0f, 1f)
            center > end - edge -> maxSpeed * ((center - (end - edge)) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}
