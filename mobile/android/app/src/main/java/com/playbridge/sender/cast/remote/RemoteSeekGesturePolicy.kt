package com.playbridge.sender.cast.remote

import kotlin.math.abs

enum class RemoteSeekAxis { Horizontal, Vertical }

/** Pixel geometry only: Android touch slop and dp hit tolerance are converted by the caller. */
data class RemoteSeekGesturePolicy(
    val widthPx: Float, val insetPx: Float, val durationMs: Long, val initialPositionMs: Long,
    val downX: Float, val downY: Float, val playheadY: Float,
    val hitTolerancePx: Float, val slopPx: Float,
) {
    private val span get() = (widthPx - 2f * insetPx).coerceAtLeast(1f)
    val direct: Boolean get() = durationMs > 0 && abs(downX - (insetPx +
        span * (initialPositionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)).toFloat()) <= hitTolerancePx &&
        abs(downY - playheadY) <= hitTolerancePx

    fun lockAxis(dx: Float, dy: Float, locked: RemoteSeekAxis? = null): RemoteSeekAxis? =
        locked ?: if (maxOf(abs(dx), abs(dy)) <= slopPx) null
        else if (abs(dx) >= abs(dy)) RemoteSeekAxis.Horizontal else RemoteSeekAxis.Vertical

    fun positionAt(x: Float): Long {
        if (durationMs <= 0L || widthPx <= 0f) return initialPositionMs
        val position = if (direct) ((x - insetPx) / span).coerceIn(0f, 1f).toDouble() * durationMs
        else initialPositionMs + (x - downX).toDouble() / widthPx * (durationMs / 10L).coerceIn(120_000L, 600_000L)
        return position.toLong().coerceIn(0L, durationMs)
    }
}

object RemoteLearnedGesture {
    const val SEEK = "seek"
    const val TAP = "tap"
    const val VOLUME = "volume"
    fun hintLearned(learned: Set<String>, seek: Boolean, volume: Boolean, tap: Boolean = true): Boolean =
        (!tap || TAP in learned) && (!seek || SEEK in learned) && (!volume || VOLUME in learned)
}
