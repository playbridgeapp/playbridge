package com.playbridge.shared.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MpvControlQueueTest {
    @Test
    fun commandsRunOnTheDedicatedWorkerAndSubmissionDoesNotBlock() {
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val workerThread = AtomicReference<String>()
        val queue = queue(timeoutMs = 5_000L)

        try {
            val callerThread = Thread.currentThread().name
            val startNanos = System.nanoTime()
            assertTrue(queue.submit("blocking") {
                workerThread.set(Thread.currentThread().name)
                started.countDown()
                unblock.await()
                completed.countDown()
            })
            val submissionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertNotEquals(callerThread, workerThread.get())
            assertTrue(submissionMs < 250L, "Submitting a native control took ${submissionMs}ms")
        } finally {
            unblock.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            queue.stopAccepting()
            queue.shutdownAfterQueuedTasks()
            queue.shutdownWatchdog()
        }
    }

    @Test
    fun timeoutOpensCircuitButStillAllowsQueuedTeardown() {
        val timedOut = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val teardownRan = CountDownLatch(1)
        val queue = queue(timeoutMs = 50L, onTimeout = { timedOut.countDown() })

        try {
            assertTrue(queue.submit("stuck") { unblock.await() })
            assertTrue(timedOut.await(1, TimeUnit.SECONDS))
            assertTrue(queue.isUnhealthy)
            assertFalse(queue.submit("seek") {})

            assertTrue(queue.submit("release", allowWhenUnhealthy = true) {
                teardownRan.countDown()
            })
            unblock.countDown()
            assertTrue(teardownRan.await(1, TimeUnit.SECONDS))
        } finally {
            unblock.countDown()
            queue.stopAccepting()
            queue.shutdownAfterQueuedTasks()
            queue.shutdownWatchdog()
        }
    }

    private fun queue(
        timeoutMs: Long,
        onTimeout: () -> Unit = {},
    ) = MpvControlQueue(
        timeoutMs = timeoutMs,
        threadName = "MpvControlQueueTest",
        onTimeout = { _, _ -> onTimeout() },
        onFailure = { name, error -> throw AssertionError("$name failed", error) },
    )
}
