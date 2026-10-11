package com.playbridge.player.player

/** Main-thread prewarm lifecycle, independent of Android so disconnect/teardown races are tested. */
internal class RendererPrewarmController(
    private val platform: Platform,
    private val onWarm: (RendererProcessSupervisor.Kind) -> Unit = {},
    private val onDisconnected: (RendererProcessSupervisor.Kind) -> Unit = {},
) {
    interface Platform {
        /** Returns an unbind action, or null when binding failed. */
        fun bind(
            kind: RendererProcessSupervisor.Kind,
            connected: () -> Unit,
            disconnected: () -> Unit,
        ): (() -> Unit)?
        /** Returns a cancellation action. */
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
        fun releaseProcess(kind: RendererProcessSupervisor.Kind)
    }

    private class State {
        var generation = 0L
        var unbind: (() -> Unit)? = null
        var cancelRebind: (() -> Unit)? = null
    }

    private val states = RendererProcessSupervisor.Kind.entries.associateWith { State() }
    private var enabled = true

    init {
        // Give ServerService's context query time to recover a still-visible browser after
        // process death. An actual player can bind directly during this cooldown.
        states.keys.forEach(::scheduleRebind)
    }

    fun setBrowserActive(browserActive: Boolean) {
        if (enabled == !browserActive) return
        enabled = !browserActive // Close the gate BEFORE unbind/kill can report a disconnect.
        if (enabled) {
            states.keys.forEach(::bind)
        } else {
            states.forEach { (kind, state) ->
                state.generation++
                state.cancelRebind?.invoke()
                state.cancelRebind = null
                val unbind = state.unbind
                state.unbind = null
                unbind?.invoke()
                // Unbinding alone can leave a cached process retaining loaded native libraries.
                // Browser context has replaced playback, so release that process as well.
                platform.releaseProcess(kind)
            }
        }
    }

    private fun bind(kind: RendererProcessSupervisor.Kind) {
        if (!enabled) return
        val state = states.getValue(kind)
        if (state.unbind != null) return
        state.cancelRebind?.invoke()
        state.cancelRebind = null
        val generation = ++state.generation
        val unbind = platform.bind(
            kind,
            connected = { if (enabled && state.generation == generation) onWarm(kind) },
            disconnected = {
                if (enabled && state.generation == generation) {
                    state.generation++
                    val release = state.unbind
                    state.unbind = null
                    release?.invoke()
                    onDisconnected(kind)
                    scheduleRebind(kind)
                }
            },
        )
        if (!enabled || state.generation != generation) {
            unbind?.invoke()
        } else {
            state.unbind = unbind
            if (unbind == null) scheduleRebind(kind)
        }
    }

    private fun scheduleRebind(kind: RendererProcessSupervisor.Kind) {
        if (!enabled) return
        val state = states.getValue(kind)
        if (state.cancelRebind != null) return
        val generation = state.generation
        state.cancelRebind = platform.schedule(REBIND_DELAY_MS) {
            // A canceled callback may already be queued. Never let it mutate a new binding.
            if (enabled && state.generation == generation) {
                state.cancelRebind = null
                bind(kind)
            }
        }
    }

    private companion object {
        const val REBIND_DELAY_MS = 1_500L
    }
}
