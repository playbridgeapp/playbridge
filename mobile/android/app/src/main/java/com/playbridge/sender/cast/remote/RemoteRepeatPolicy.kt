package com.playbridge.sender.cast.remote

/** Finite directional repetition. Hardware volume uses OS repeats instead of this policy. */
internal class RemoteRepeatPolicy(private val clock: () -> Long) {
    private var key: String? = null
    private var nextAt = 0L
    private var steps = 0

    fun press(key: String): Boolean {
        cancel()
        if (key !in ARROWS) return false
        this.key = key
        nextAt = clock() + INITIAL_DELAY_MS
        steps = 1
        return true
    }

    fun next(): Boolean {
        if (key == null || steps >= MAX_STEPS || clock() < nextAt) return false
        steps++
        // Never catch up a backlog after suspension.
        nextAt = clock() + CADENCE_MS
        return true
    }

    fun cancel() { key = null; steps = 0 }

    companion object {
        val ARROWS = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right")
        const val INITIAL_DELAY_MS = 350L
        const val CADENCE_MS = 100L
        const val MAX_STEPS = 100
    }
}
