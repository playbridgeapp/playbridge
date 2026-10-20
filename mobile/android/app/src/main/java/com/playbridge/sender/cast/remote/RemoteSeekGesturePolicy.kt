package com.playbridge.sender.cast.remote

import kotlin.math.abs

enum class RemoteSeekAxis { Horizontal, Vertical }

/**
 * Pixel geometry only: Android touch slop is converted by the caller. A plain swipe always seeks
 * relative to the current position; holding [HOLD_MS] before moving scrubs the playhead under the
 * finger (same rule as iOS's RemoteSeekBehavior), so touching near the playhead can't jump it.
 */
data class RemoteSeekGesturePolicy(
    val widthPx: Float, val insetPx: Float, val durationMs: Long, val initialPositionMs: Long,
    val downX: Float, val slopPx: Float,
) {
    private val span get() = (widthPx - 2f * insetPx).coerceAtLeast(1f)

    /** Whether a horizontal drag that locked after [heldMs] scrubs instead of seeking. */
    fun scrubs(heldMs: Long): Boolean = durationMs > 0 && heldMs >= HOLD_MS

    fun lockAxis(dx: Float, dy: Float, locked: RemoteSeekAxis? = null): RemoteSeekAxis? =
        locked ?: if (maxOf(abs(dx), abs(dy)) <= slopPx) null
        else if (abs(dx) >= abs(dy)) RemoteSeekAxis.Horizontal else RemoteSeekAxis.Vertical

    fun positionAt(x: Float, scrub: Boolean = false): Long {
        if (durationMs <= 0L || widthPx <= 0f) return initialPositionMs
        val position = if (scrub) ((x - insetPx) / span).coerceIn(0f, 1f).toDouble() * durationMs
        else initialPositionMs + (x - downX).toDouble() / widthPx * (durationMs / 10L).coerceIn(120_000L, 600_000L)
        return position.toLong().coerceIn(0L, durationMs)
    }

    companion object {
        const val HOLD_MS = 400L
    }
}

object RemoteLearnedGesture {
    const val SEEK = "seek"
    const val TAP = "tap"
    const val VOLUME = "volume"
    fun hintLearned(learned: Set<String>, seek: Boolean, volume: Boolean, tap: Boolean = true): Boolean =
        (!tap || TAP in learned) && (!seek || SEEK in learned) && (!volume || VOLUME in learned)
}
