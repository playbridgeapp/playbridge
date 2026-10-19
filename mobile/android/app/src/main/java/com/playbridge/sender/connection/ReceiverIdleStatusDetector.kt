package com.playbridge.sender.connection

/**
 * Infers an idle receiver from `status` frames for receivers that never send
 * `context: idle` when their player stops locally (desktop before it broadcast player
 * context transitions). Fires once when, in the "player" context, a receiver that
 * reported active playback has reported only idle/stopped status for [holdMs] — long
 * enough to ride out brief idle frames between items or while a new item opens.
 */
internal class ReceiverIdleStatusDetector(private val holdMs: Long = DEFAULT_HOLD_MS) {
    private var sawActivePlayback = false
    private var idleSinceMs: Long? = null

    fun reset() {
        sawActivePlayback = false
        idleSinceMs = null
    }

    /** Returns true when the player context should be treated as idle. */
    fun onStatus(context: String, state: String, nowMs: Long): Boolean {
        if (context != "player") {
            reset()
            return false
        }
        when (state) {
            "playing", "paused", "buffering" -> {
                sawActivePlayback = true
                idleSinceMs = null
            }
            "idle", "stopped" -> if (sawActivePlayback) {
                val since = idleSinceMs ?: nowMs.also { idleSinceMs = it }
                if (nowMs - since >= holdMs) {
                    reset()
                    return true
                }
            }
            else -> idleSinceMs = null
        }
        return false
    }

    companion object {
        const val DEFAULT_HOLD_MS = 3_000L
    }
}
