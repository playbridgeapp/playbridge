package com.playbridge.sender.cast.remote.input

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Pointer timestamps and the sleeping hold timer share one recognition clock. */
internal class PadGestureSession(density: Float) {
    private var now = 0L
    private val policy = PadGesturePolicy.forDensity(clock = { now }, density = density)
    private val changes = Channel<Unit>(Channel.CONFLATED)

    fun down(time: Long, x: Float, y: Float) {
        observe(time)
        policy.down(x, y)
        changes.trySend(Unit)
    }

    fun move(time: Long, x: Float, y: Float): List<PadStep> {
        observe(time)
        return policy.move(x, y).also { changes.trySend(Unit) }
    }

    fun up(time: Long, x: Float, y: Float): List<PadStep> {
        observe(time)
        return policy.up(x, y).also { changes.trySend(Unit) }
    }

    fun cancel() {
        policy.cancel()
        changes.trySend(Unit)
    }

    /** Movement wakes a sleeping timer so a canceled/replaced deadline cannot emit. */
    suspend fun repeatHold(emit: (List<PadStep>) -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val deadline = policy.holdDeadline()
            if (deadline == null) {
                changes.receive()
                continue
            }
            val changed = withTimeoutOrNull((deadline - now).coerceAtLeast(0)) {
                changes.receive()
                true
            } == true
            currentCoroutineContext().ensureActive()
            if (!changed && policy.holdDeadline() == deadline) {
                observe(deadline)
                val steps = policy.tick()
                if (steps.isNotEmpty()) emit(steps)
            }
        }
    }

    private fun observe(time: Long) {
        if (time > now) now = time
    }
}
