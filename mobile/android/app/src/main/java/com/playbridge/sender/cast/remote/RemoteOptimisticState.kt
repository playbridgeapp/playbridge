package com.playbridge.sender.cast.remote

import kotlin.math.abs

/** Phone intent overlays reported status; a successful send is not a receiver acknowledgement. */
@ConsistentCopyVisibility
internal data class RemoteOptimisticState private constructor(
    private val clock: () -> Long,
    private val pendingSeek: PendingSeek?,
    private val pendingPlaying: PendingPlaying?,
) {
    constructor(clock: () -> Long) : this(clock, null, null)
    private data class PendingSeek(val targetMs: Long, val sentAt: Long)
    private data class PendingPlaying(val playing: Boolean, val sentAt: Long)

    val hasPending: Boolean get() = pendingSeek != null || pendingPlaying != null
    fun position(reportedMs: Long): Long = pendingSeek?.targetMs ?: reportedMs
    fun playing(reported: Boolean): Boolean = pendingPlaying?.playing ?: reported

    fun seek(targetMs: Long, durationMs: Long): RemoteOptimisticState = copy(
        pendingSeek = PendingSeek(boundedSeek(targetMs, durationMs), clock()),
    )

    fun play(playing: Boolean): RemoteOptimisticState = copy(
        pendingPlaying = PendingPlaying(playing, clock()),
    )

    fun reconcile(positionMs: Long, playing: Boolean): RemoteOptimisticState {
        val now = clock()
        return copy(
            pendingSeek = pendingSeek?.takeUnless {
                now > it.sentAt && abs(positionMs - it.targetMs) <= SEEK_TOLERANCE_MS ||
                    now - it.sentAt >= DEADLINE_MS
            },
            pendingPlaying = pendingPlaying?.takeUnless {
                now > it.sentAt && playing == it.playing || now - it.sentAt >= DEADLINE_MS
            },
        )
    }

    fun expire(): RemoteOptimisticState {
        val now = clock()
        return copy(
            pendingSeek = pendingSeek?.takeIf { now - it.sentAt < DEADLINE_MS },
            pendingPlaying = pendingPlaying?.takeIf { now - it.sentAt < DEADLINE_MS },
        )
    }

    /** Also used for command failure; identity changes create a fresh instance in the route. */
    fun clear(): RemoteOptimisticState = copy(pendingSeek = null, pendingPlaying = null)

    companion object {
        const val SEEK_TOLERANCE_MS = 1_500L
        const val DEADLINE_MS = 3_000L
    }
}

internal fun boundedSeek(targetMs: Long, durationMs: Long): Long =
    targetMs.coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)

internal fun relativeRemoteSeek(positionMs: Long, deltaMs: Long, durationMs: Long): Long =
    boundedSeek(positionMs.coerceAtLeast(0L).let {
        if (deltaMs > 0L && it > Long.MAX_VALUE - deltaMs) Long.MAX_VALUE else it + deltaMs
    }, durationMs)
