package com.playbridge.sender.cast.remote.input

import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.RemoteRepeatPolicy
import java.util.ArrayDeque
import kotlin.math.hypot

enum class PadDirection(val key: RemoteKey) {
    Up(RemoteKey.Up), Down(RemoteKey.Down), Left(RemoteKey.Left), Right(RemoteKey.Right);

    companion object {
        /** Dominant axis. A tie prefers horizontal so a 45° swipe still has one direction. */
        fun from(dx: Float, dy: Float): PadDirection = when {
            kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> if (dx < 0f) Left else Right
            dy < 0f -> Up
            else -> Down
        }
    }
}

enum class PadStepKind { TAP, SWIPE, FLICK, HOLD }

data class PadStep(val key: String, val kind: PadStepKind, val direction: PadDirection?)

/**
 * One finger, one outcome. A swipe emits one arrow. A quick release above
 * [FLICK_MIN_VELOCITY_PX_PER_MS] schedules extra arrows, at most [MAX_FLICK_STEPS]
 * including the swipe. Holding after the swipe repeats through [RemoteRepeatPolicy]
 * and does not also flick. [cancel] and a new [down] drop every pending step.
 * [tick] never catches up a backlog.
 */
internal class PadGesturePolicy(
    private val clock: () -> Long,
    private val slopPx: Float = TOUCH_SLOP_PX,
    private val tapTimeoutMs: Long = TAP_TIMEOUT_MS,
    private val repeat: RemoteRepeatPolicy = RemoteRepeatPolicy(clock),
) {
    private var phase: Phase = Phase.Idle
    private val samples = ArrayDeque<Sample>()

    fun down(x: Float, y: Float) = synchronized(this) {
        reset()
        val t = clock()
        samples.add(Sample(t, x, y))
        phase = Phase.Tracking(x, y, t)
    }

    fun move(x: Float, y: Float): List<PadStep> = synchronized(this) {
        track(x, y)
        val tracking = phase as? Phase.Tracking ?: return emptyList()
        val dx = x - tracking.x0
        val dy = y - tracking.y0
        if (hypot(dx, dy) < slopPx) return emptyList()
        arm(PadDirection.from(dx, dy))
    }

    fun up(x: Float, y: Float): List<PadStep> = synchronized(this) {
        track(x, y)
        when (val current = phase) {
            is Phase.Tracking -> releaseTracking(current, x, y)
            is Phase.Armed -> releaseArmed(current)
            else -> emptyList()
        }
    }

    fun tick(): List<PadStep> = synchronized(this) {
        when (val current = phase) {
            is Phase.Armed -> {
                if (clock() < current.nextHoldAt || !repeat.next()) return emptyList()
                phase = current.copy(holdSteps = current.holdSteps + 1, nextHoldAt = clock() + RemoteRepeatPolicy.CADENCE_MS)
                listOf(PadStep(current.direction.key.wireValue, PadStepKind.HOLD, current.direction))
            }
            is Phase.Flicking -> drainFlick(current)
            else -> emptyList()
        }
    }

    fun cancel() = synchronized(this) { reset() }

    fun isArmed(): Boolean = synchronized(this) { phase is Phase.Armed }
    fun hasPendingFlick(): Boolean = synchronized(this) { phase is Phase.Flicking }
    fun holdDeadline(): Long = synchronized(this) { (phase as? Phase.Armed)?.nextHoldAt ?: clock() }
    fun flickDeadline(): Long = synchronized(this) { (phase as? Phase.Flicking)?.nextAt ?: clock() }

    private fun releaseTracking(tracking: Phase.Tracking, x: Float, y: Float): List<PadStep> {
        val dx = x - tracking.x0
        val dy = y - tracking.y0
        if (hypot(dx, dy) >= slopPx) {
            val swipe = arm(PadDirection.from(dx, dy))
            val armed = phase as Phase.Armed
            return swipe + releaseArmed(armed)
        }
        val tapped = clock() - tracking.t0 <= tapTimeoutMs
        reset()
        return if (tapped) listOf(PadStep(RemoteKey.Ok.wireValue, PadStepKind.TAP, null)) else emptyList()
    }

    private fun releaseArmed(armed: Phase.Armed): List<PadStep> {
        val velocity = velocity()
        val held = armed.holdSteps > 0
        repeat.cancel()
        samples.clear()
        val extra = if (held) 0 else (flickStepsForVelocity(velocity) - 1).coerceAtLeast(0)
        if (extra == 0) {
            phase = Phase.Idle
            return emptyList()
        }
        val flicking = Phase.Flicking(armed.direction, extra, clock())
        phase = flicking
        return drainFlick(flicking)
    }

    private fun drainFlick(flicking: Phase.Flicking): List<PadStep> {
        if (flicking.remaining <= 0 || clock() < flicking.nextAt) return emptyList()
        val step = PadStep(flicking.direction.key.wireValue, PadStepKind.FLICK, flicking.direction)
        val left = flicking.remaining - 1
        phase = if (left == 0) Phase.Idle else flicking.copy(remaining = left, nextAt = clock() + FLICK_CADENCE_MS)
        return listOf(step)
    }

    private fun arm(direction: PadDirection): List<PadStep> {
        val now = clock()
        repeat.press(direction.key.wireValue)
        phase = Phase.Armed(direction, holdSteps = 0, nextHoldAt = now + RemoteRepeatPolicy.INITIAL_DELAY_MS)
        return listOf(PadStep(direction.key.wireValue, PadStepKind.SWIPE, direction))
    }

    private fun track(x: Float, y: Float) {
        val t = clock()
        samples.add(Sample(t, x, y))
        val cutoff = t - FLICK_WINDOW_MS
        while (samples.size > 2 && samples.first().t < cutoff) samples.removeFirst()
    }

    private fun velocity(): Float {
        if (samples.size < 2) return 0f
        val newest = samples.last()
        val oldest = samples.first()
        val dt = newest.t - oldest.t
        if (dt <= 0L) return 0f
        return hypot(newest.x - oldest.x, newest.y - oldest.y) / dt.toFloat()
    }

    private fun reset() {
        repeat.cancel()
        samples.clear()
        phase = Phase.Idle
    }

    private data class Sample(val t: Long, val x: Float, val y: Float)

    private sealed interface Phase {
        data object Idle : Phase
        data class Tracking(val x0: Float, val y0: Float, val t0: Long) : Phase
        data class Armed(val direction: PadDirection, val holdSteps: Int, val nextHoldAt: Long) : Phase
        data class Flicking(val direction: PadDirection, val remaining: Int, val nextAt: Long) : Phase
    }

    companion object {
        const val TOUCH_SLOP_PX = 24f
        const val TAP_TIMEOUT_MS = 300L
        const val MAX_FLICK_STEPS = 5
        const val FLICK_MIN_VELOCITY_PX_PER_MS = 1.5f
        const val FLICK_VELOCITY_PER_STEP = 0.8f
        const val FLICK_WINDOW_MS = 80L
        const val FLICK_CADENCE_MS = 80L
    }
}

/** Total arrows for one flick, including the swipe that already fired. Never above [PadGesturePolicy.MAX_FLICK_STEPS]. */
internal fun flickStepsForVelocity(velocityPxPerMs: Float): Int {
    if (velocityPxPerMs < PadGesturePolicy.FLICK_MIN_VELOCITY_PX_PER_MS) return 1
    val extras = ((velocityPxPerMs - PadGesturePolicy.FLICK_MIN_VELOCITY_PX_PER_MS) /
        PadGesturePolicy.FLICK_VELOCITY_PER_STEP).toInt() + 1
    return (1 + extras).coerceAtMost(PadGesturePolicy.MAX_FLICK_STEPS)
}
