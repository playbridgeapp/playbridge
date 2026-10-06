package com.playbridge.sender.cast.proxy

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Reference-counted native playback ownership. HTTP reads never reach this registry. */
internal class PlaybackLeaseRegistry(
    private val scope: CoroutineScope,
    private val renew: suspend (String) -> Boolean,
    private val revoke: suspend (String) -> Unit,
) {
    private class State(var references: Int = 0, var job: Job? = null)
    private val known = mutableSetOf<String>()
    private val active = mutableMapOf<String, State>()

    fun register(id: String) = synchronized(active) { known.add(id) }
    fun forget(id: String) = synchronized(active) { known.remove(id) }

    fun retain(id: String): AutoCloseable? {
        val retained = synchronized(active) {
            if (id !in known) return null
            val state = active.getOrPut(id) { State() }
            state.references++
            if (state.job == null) state.job = scope.launch {
                while (runCatching { renew(id) }.getOrDefault(false)) delay(60_000)
            }
            state
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(active) {
                val state = active[id]
                if (state === retained && --retained.references == 0) {
                    active.remove(id)
                    known.remove(id)
                    retained.job?.cancel()
                    scope.launch { runCatching { revoke(id) } }
                }
            }
        }
    }

    /** Native host shutdown clears grants itself; don't submit to a freed host. */
    fun clear() = synchronized(active) {
        active.values.forEach { it.job?.cancel() }
        active.clear()
        known.clear()
    }
}
