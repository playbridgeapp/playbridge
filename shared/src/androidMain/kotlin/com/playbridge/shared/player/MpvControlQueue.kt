package com.playbridge.shared.player

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes synchronous native player calls away from Android's main looper and opens a
 * circuit breaker when one call stops responding. It deliberately cannot cancel an active
 * native call; teardown work is instead allowed to remain queued behind it.
 */
internal class MpvControlQueue(
    private val timeoutMs: Long,
    threadName: String,
    private val onTimeout: (name: String, sequence: Long) -> Unit,
    private val onFailure: (name: String, error: Exception) -> Unit,
) {
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "$threadName-Control").apply { isDaemon = true }
    }
    private val watchdogExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "$threadName-Watchdog").apply { isDaemon = true }
        }
    private val accepting = AtomicBoolean(true)
    private val unhealthy = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)

    val isUnhealthy: Boolean
        get() = unhealthy.get()

    fun submit(
        name: String,
        allowWhenUnhealthy: Boolean = false,
        allowAfterClose: Boolean = false,
        watchForStall: Boolean = true,
        block: () -> Unit,
    ): Boolean {
        if (!accepting.get() && !allowAfterClose) return false
        if (unhealthy.get() && !allowWhenUnhealthy) return false

        val commandId = sequence.incrementAndGet()
        return try {
            controlExecutor.execute {
                if (unhealthy.get() && !allowWhenUnhealthy) return@execute

                val completed = AtomicBoolean(false)
                val watchdog = if (watchForStall) {
                    watchdogExecutor.schedule({
                        if (!completed.get() && unhealthy.compareAndSet(false, true)) {
                            onTimeout(name, commandId)
                        }
                    }, timeoutMs, TimeUnit.MILLISECONDS)
                } else {
                    null
                }

                try {
                    block()
                } catch (e: Exception) {
                    onFailure(name, e)
                } finally {
                    completed.set(true)
                    watchdog?.cancel(false)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun stopAccepting() {
        accepting.set(false)
    }

    fun shutdownAfterQueuedTasks() {
        controlExecutor.shutdown()
    }

    fun shutdownWatchdog() {
        watchdogExecutor.shutdownNow()
    }
}
