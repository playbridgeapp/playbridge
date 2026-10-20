package com.playbridge.sender.cast.remote

import kotlin.math.roundToInt

internal fun volumeDragTarget(start: Double, accumulatedSteps: Double, step: Double): Int =
    ((start + accumulatedSteps * step).coerceIn(0.0, 1.0) * 100).roundToInt()

/** Latest absolute wins; relative runs retain every step, paced for older receivers. */
internal class RemoteVolumeDragThrottle {
    private var absolute: Int? = null
    private val relative = ArrayDeque<Int>()
    private var lastSent: Long? = null
    val pending get() = absolute != null || relative.isNotEmpty()
    fun absolute(percent: Int) { relative.clear(); absolute = percent.coerceIn(0, 100) }
    fun relative(steps: Int) {
        if (steps == 0) return
        if (relative.lastOrNull()?.let { (it > 0) == (steps > 0) } == true) relative.addLast(relative.removeLast() + steps)
        else relative.addLast(steps)
    }
    fun next(now: Long): RemoteAction? {
        if (!pending || lastSent?.let { now - it < 125 } == true) return null
        lastSent = now
        absolute?.let { absolute = null; return RemoteAction.SetVolume(it) }
        val run = relative.removeFirst()
        val step = if (run > 0) 1 else -1
        if (run != step) relative.addFirst(run - step)
        return RemoteAction.AdjustVolume(step, RemoteLearnedGesture.VOLUME)
    }
    fun clear() { absolute = null; relative.clear(); lastSent = null }
}
