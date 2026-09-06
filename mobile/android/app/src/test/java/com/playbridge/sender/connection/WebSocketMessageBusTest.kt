package com.playbridge.sender.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebSocketMessageBusTest {
    @Test
    fun slowSubscriberDoesNotLoseSignalingAfterBufferFills() = runBlocking {
        withTimeout(5_000) {
            val bus = WebSocketMessageBus()
            val release = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val filled = CompletableDeferred<Unit>()
            val receivedAll = CompletableDeferred<Unit>()
            val expected = (0 until 65).map { "status:$it" } +
                listOf("screen_mirror_ready", "screen_mirror_answer", "screen_mirror_candidate", "stopped")
            val received = mutableListOf<String>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                bus.messages.collect {
                    received.add(it)
                    if (received.size == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    if (received.size == expected.size) receivedAll.complete(Unit)
                }
            }
            val publisher = launch(Dispatchers.IO) {
                bus.publish(expected.first())
                entered.await()
                expected.drop(1).forEachIndexed { index, message ->
                    if (index == 64) filled.complete(Unit)
                    bus.publish(message)
                }
            }
            try {
                filled.await()
                assertFalse(publisher.isCompleted)
                release.complete(Unit)
                receivedAll.await()
                publisher.join()
                assertEquals(expected, received)
            } finally {
                release.complete(Unit)
                collector.cancelAndJoin()
                publisher.cancelAndJoin()
            }
        }
    }
}
