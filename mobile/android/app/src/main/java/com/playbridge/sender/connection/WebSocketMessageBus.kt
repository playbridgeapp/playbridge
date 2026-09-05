package com.playbridge.sender.connection

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking

/** Ordered delivery of both snapshots and one-time responses, including WebRTC signaling. */
internal class WebSocketMessageBus {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages = events.asSharedFlow()

    // Called on OkHttp's reader thread, never Main. When a subscriber falls behind,
    // backpressure the reader rather than dropping signaling or queuing unbounded jobs.
    fun publish(text: String) {
        runBlocking { events.emit(text) }
    }
}
