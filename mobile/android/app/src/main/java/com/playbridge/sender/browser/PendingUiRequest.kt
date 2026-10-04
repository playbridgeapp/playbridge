package com.playbridge.sender.browser

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Retains an unseen request, never replays a consumed one to a new UI host. */
internal class PendingUiRequest {
    private val sequence = AtomicLong()
    private val pending = MutableStateFlow(0L)
    val requests: StateFlow<Long> = pending.asStateFlow()

    fun request() { pending.value = sequence.incrementAndGet() }
    fun consume(id: Long): Boolean = id != 0L && pending.compareAndSet(id, 0L)
    fun clear() { pending.value = 0L }
}
