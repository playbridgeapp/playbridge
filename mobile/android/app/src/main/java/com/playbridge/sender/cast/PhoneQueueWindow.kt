package com.playbridge.sender.cast

/** Changes that bring the TV's queue in line with the phone's list. Applied in this order. */
data class WindowPlan(
    val removes: List<String> = emptyList(),
    val adds: List<String> = emptyList(),
    /** `queue_move` pairs of item id and the item it goes before (null: the end). */
    val moves: List<Pair<String, String?>> = emptyList(),
) {
    val isEmpty: Boolean get() = removes.isEmpty() && adds.isEmpty() && moves.isEmpty()
}

/**
 * The phone holds the whole list (any length); the TV holds a window of it: a few played
 * items, the current one and the next ones. Pure logic, so both directions can be tested:
 * [adopt] takes in edits made on the TV, [plan] works out what to send to the TV.
 *
 * Ids are the item ids the phone gave each entry; the TV keeps them as its queue item ids.
 * Ids on the TV that the phone doesn't know (added by another controller) are left alone.
 */
object PhoneQueueWindow {
    /** Items kept queued ahead of the current one once topped up. */
    const val AHEAD = 20
    /** Top up when this many or fewer are left ahead. */
    const val AHEAD_LOW = 10
    /** Played items kept for Previous after a trim. */
    const val BEHIND_KEEP = 10
    /** Trim played items once more than this many are queued. */
    const val BEHIND_MAX = 15
    /** Queued items further ahead than this (e.g. after a jump back) no longer belong to the window. */
    const val REACH_MAX = 50
    /** PlayBridge TVs reject queues larger than this. */
    const val TV_LIMIT = 200

    /**
     * Takes in changes made on the TV ahead of the current item: sent items that are gone
     * were removed there, and sent items in a new order were reordered there.
     * [cursor] must already point at the TV's current item.
     */
    fun adopt(order: List<String>, cursor: Int, sent: Set<String>, tv: List<String>, tvCurrent: String): List<String> {
        val currentAt = tv.indexOf(tvCurrent)
        if (currentAt < 0 || cursor !in order.indices) return order
        val onTv = tv.toHashSet()
        val kept = order.filterIndexed { index, id -> index <= cursor || id !in sent || id in onTv }
        val position = kept.withIndex().associate { it.value to it.index }
        val newCursor = position[order[cursor]] ?: return kept
        val tvAhead = tv.drop(currentAt + 1).filter { (position[it] ?: -1) > newCursor }
        val slots = tvAhead.map { position.getValue(it) }.sorted()
        if (tvAhead.isEmpty()) return kept
        val result = kept.toMutableList()
        slots.forEachIndexed { k, slot -> result[slot] = tvAhead[k] }
        return result
    }

    /** What to send so the TV holds the window around [cursor], which is the TV's current item. */
    fun plan(order: List<String>, cursor: Int, tv: List<String>, tvCurrent: String): WindowPlan {
        val currentAt = tv.indexOf(tvCurrent)
        if (currentAt < 0 || cursor !in order.indices) return WindowPlan()
        val behind = tv.take(currentAt)
        val removes = mutableListOf<String>()
        if (behind.size > BEHIND_MAX) removes += behind.take(behind.size - BEHIND_KEEP)

        val position = HashMap<String, Int>(order.size * 2).apply { order.forEachIndexed { i, id -> put(id, i) } }
        val ours = tv.drop(currentAt + 1).filter { it in position }
        // Ours but not just ahead in the phone's list (after a jump either way): drop them.
        val stale = ours.filter { position.getValue(it) - cursor !in 1..REACH_MAX }
        removes += stale
        val valid = ours - stale.toSet()
        val reach = valid.maxOfOrNull { position.getValue(it) - cursor } ?: 0
        val wanted = if (valid.size <= AHEAD_LOW) maxOf(reach, AHEAD) else reach
        val count = minOf(wanted, order.size - cursor - 1)
        val target = order.subList(cursor + 1, cursor + 1 + count)

        val validSet = valid.toHashSet()
        val room = TV_LIMIT - (tv.size - removes.size)
        val adds = target.filter { it !in validSet }.take(maxOf(0, room))

        // Simulate the TV's order of our items (adds land at the end) and move each into place.
        val addSet = adds.toHashSet()
        val simulated = (valid + adds).toMutableList()
        val placed = target.filter { it in validSet || it in addSet }
        val moves = mutableListOf<Pair<String, String?>>()
        placed.forEachIndexed { i, id ->
            if (simulated[i] != id) {
                simulated.remove(id)
                simulated.add(i, id)
                moves += id to simulated.getOrNull(i + 1)
            }
        }
        return WindowPlan(removes, adds, moves)
    }
}
