package com.playbridge.sender.cast.routing

/** Rejects late status/failure events emitted by a superseded external-media load. */
object ExternalLoadEventGate {
    fun isCurrent(
        eventEpoch: Long?,
        currentGeneration: Long,
        mediaLoaded: Boolean,
    ): Boolean = !mediaLoaded || eventEpoch == currentGeneration
}
