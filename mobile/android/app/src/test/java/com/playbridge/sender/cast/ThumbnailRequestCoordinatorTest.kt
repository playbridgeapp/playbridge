package com.playbridge.sender.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailRequestCoordinatorTest {

    @Test
    fun concurrentRequests_shareOneLoad() = runBlocking {
        val coordinator = ThumbnailRequestCoordinator<String, String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loads = 0

        val first = async {
            coordinator.run("video", ThumbnailRequestPriority.PREFETCH) {
                loads++
                started.complete(Unit)
                release.await()
                "frame"
            }
        }
        started.await()
        val second = async {
            coordinator.run("video", ThumbnailRequestPriority.VISIBLE) {
                error("the joined request must not start another load")
            }
        }
        yield()
        release.complete(Unit)

        assertEquals("frame", first.await())
        assertEquals("frame", second.await())
        assertEquals(1, loads)
    }

    @Test
    fun visibleRequest_retriesPrefetchFailureImmediately() = runBlocking {
        val coordinator = ThumbnailRequestCoordinator<String, String>()

        assertNull(
            coordinator.run("video", ThumbnailRequestPriority.PREFETCH) { null },
        )
        assertEquals(
            "frame",
            coordinator.run("video", ThumbnailRequestPriority.VISIBLE) { "frame" },
        )
    }

    @Test
    fun visibleWaiter_retriesWhenJoinedPrefetchFails() = runBlocking {
        val coordinator = ThumbnailRequestCoordinator<String, String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val prefetch = async {
            coordinator.run("video", ThumbnailRequestPriority.PREFETCH) {
                started.complete(Unit)
                release.await()
                null
            }
        }
        started.await()
        val visible = async {
            coordinator.run("video", ThumbnailRequestPriority.VISIBLE) { "frame" }
        }
        yield()
        release.complete(Unit)

        assertNull(prefetch.await())
        assertEquals("frame", visible.await())
    }

    @Test
    fun visibleFailure_usesCooldownBeforeRetrying() = runBlocking {
        var now = 1_000L
        val coordinator = ThumbnailRequestCoordinator<String, String>(
            retryCooldownMs = 30_000L,
            nowMs = { now },
        )
        var retried = false

        assertNull(coordinator.run("video", ThumbnailRequestPriority.VISIBLE) { null })
        assertNull(
            coordinator.run("video", ThumbnailRequestPriority.VISIBLE) {
                retried = true
                "too soon"
            },
        )
        assertFalse(retried)

        now += 30_000L
        assertEquals(
            "frame",
            coordinator.run("video", ThumbnailRequestPriority.VISIBLE) { "frame" },
        )
    }

    @Test
    fun waiterTakesOverWhenPrefetchOwnerIsCancelled() = runBlocking {
        val coordinator = ThumbnailRequestCoordinator<String, String>()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()

        val prefetch = async {
            coordinator.run("video", ThumbnailRequestPriority.PREFETCH) {
                started.complete(Unit)
                never.await()
                "unused"
            }
        }
        started.await()
        val visible = async {
            coordinator.run("video", ThumbnailRequestPriority.VISIBLE) { "frame" }
        }
        yield()
        prefetch.cancelAndJoin()

        assertEquals("frame", visible.await())
    }
}
