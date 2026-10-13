package com.playbridge.sender.cast.remote.input

import com.playbridge.sender.cast.remote.RemoteKey
import kotlin.math.abs
import kotlin.math.hypot

enum class PadDirection(val key: RemoteKey) {
    Up(RemoteKey.Up), Down(RemoteKey.Down), Left(RemoteKey.Left), Right(RemoteKey.Right);

    companion object {
        /** Dominant axis. A tie prefers horizontal so a 45° swipe still has one direction. */
        fun from(dx: Float, dy: Float): PadDirection = when {
            abs(dx) >= abs(dy) -> if (dx < 0f) Left else Right
            dy < 0f -> Up
            else -> Down
        }
    }
}

enum class PadStepKind { TAP, SWIPE, DRAG, HOLD }

data class PadStep(val key: String, val kind: PadStepKind, val direction: PadDirection?)

/**
 * Crossing the initial slop emits one arrow. Further drag steps consume
 * [stepPx] along the dominant axis, re-evaluating direction from each anchor.
 * Hold repeats require remaining within [holdSlopPx] of the last step's pointer
 * position. Movement outside that area cancels hold until another drag step.
 * Release adds no arrows; [cancel] and a new [down] clear the entire gesture.
 */
internal class PadGesturePolicy(
    private val clock: () -> Long,
    private val slopPx: Float,
    private val stepPx: Float,
    private val holdSlopPx: Float,
    private val tapTimeoutMs: Long = TAP_TIMEOUT_MS,
) {
    private var phase: Phase = Phase.Idle

    fun down(x: Float, y: Float) = synchronized(this) {
        phase = Phase.Tracking(x, y, clock())
    }

    fun move(x: Float, y: Float): List<PadStep> = synchronized(this) {
        when (val current = phase) {
            is Phase.Tracking -> {
                val dx = x - current.x0
                val dy = y - current.y0
                if (hypot(dx, dy) < slopPx) return emptyList()
                val direction = PadDirection.from(dx, dy)
                phase = stepping(x, y, x, y, direction)
                listOf(PadStep(direction.key.wireValue, PadStepKind.SWIPE, direction))
            }
            is Phase.Stepping -> drag(current, x, y)
            Phase.Idle -> emptyList()
        }
    }

    fun up(x: Float, y: Float): List<PadStep> = synchronized(this) {
        val current = phase
        phase = Phase.Idle
        if (current is Phase.Tracking &&
            hypot(x - current.x0, y - current.y0) < slopPx &&
            clock() - current.t0 <= tapTimeoutMs
        ) {
            listOf(PadStep(RemoteKey.Ok.wireValue, PadStepKind.TAP, null))
        } else {
            emptyList()
        }
    }

    fun tick(): List<PadStep> = synchronized(this) {
        val current = phase as? Phase.Stepping ?: return emptyList()
        val deadline = current.nextHoldAt ?: return emptyList()
        val now = clock()
        if (now < deadline || current.steps >= MAX_HOLD_STEPS) return emptyList()
        // Never catch up a backlog after suspension.
        phase = current.copy(steps = current.steps + 1, nextHoldAt = now + HOLD_CADENCE_MS)
        listOf(PadStep(current.direction.key.wireValue, PadStepKind.HOLD, current.direction))
    }

    fun cancel() = synchronized(this) { phase = Phase.Idle }

    fun holdDeadline(): Long? = synchronized(this) {
        (phase as? Phase.Stepping)?.takeIf { it.steps < MAX_HOLD_STEPS }?.nextHoldAt
    }

    private fun drag(current: Phase.Stepping, x: Float, y: Float): List<PadStep> {
        var next = if (hypot(x - current.holdX, y - current.holdY) > holdSlopPx) {
            current.copy(nextHoldAt = null)
        } else {
            current
        }
        val steps = mutableListOf<PadStep>()
        while (steps.size < MAX_STEPS_PER_MOVE) {
            val dx = x - next.anchorX
            val dy = y - next.anchorY
            val distance = maxOf(abs(dx), abs(dy))
            if (distance < stepPx) break
            val direction = PadDirection.from(dx, dy)
            val fraction = stepPx / distance
            next = stepping(
                next.anchorX + dx * fraction,
                next.anchorY + dy * fraction,
                x,
                y,
                direction,
            )
            steps += PadStep(direction.key.wireValue, PadStepKind.DRAG, direction)
        }
        if (steps.size == MAX_STEPS_PER_MOVE) {
            // Discard travel beyond this event's cap instead of replaying it on
            // a stationary sample or a later hold tick.
            next = next.copy(anchorX = x, anchorY = y)
        }
        phase = next
        return steps
    }

    private fun stepping(
        anchorX: Float,
        anchorY: Float,
        holdX: Float,
        holdY: Float,
        direction: PadDirection,
    ) = Phase.Stepping(anchorX, anchorY, holdX, holdY, direction, clock() + HOLD_DELAY_MS, steps = 1)

    private sealed interface Phase {
        data object Idle : Phase
        data class Tracking(val x0: Float, val y0: Float, val t0: Long) : Phase
        data class Stepping(
            val anchorX: Float,
            val anchorY: Float,
            val holdX: Float,
            val holdY: Float,
            val direction: PadDirection,
            val nextHoldAt: Long?,
            val steps: Int,
        ) : Phase
    }

    companion object {
        const val TOUCH_SLOP_DP = 20f
        const val DRAG_STEP_DP = 56f
        const val HOLD_SLOP_DP = 12f
        const val TAP_TIMEOUT_MS = 300L
        const val HOLD_DELAY_MS = 500L
        const val HOLD_CADENCE_MS = 150L
        const val MAX_HOLD_STEPS = 100
        const val MAX_STEPS_PER_MOVE = 3

        fun forDensity(clock: () -> Long, density: Float) = PadGesturePolicy(
            clock = clock,
            slopPx = TOUCH_SLOP_DP * density,
            stepPx = DRAG_STEP_DP * density,
            holdSlopPx = HOLD_SLOP_DP * density,
        )
    }
}
