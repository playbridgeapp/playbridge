package com.playbridge.player.player

/**
 * Accepts playback launch requests in process-local creation order.
 *
 * Android may deliver rapid activity launches after a newer request has already reached the
 * player. Requests without an ID are legacy/in-process broadcasts and remain accepted.
 */
internal class PlaybackRequestGate {
    private var latestRequestId = Long.MIN_VALUE

    fun accept(requestId: Long?): Boolean {
        if (requestId == null) return true
        if (requestId <= latestRequestId) return false
        latestRequestId = requestId
        return true
    }
}
