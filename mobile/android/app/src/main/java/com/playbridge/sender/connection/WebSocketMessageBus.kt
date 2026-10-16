package com.playbridge.sender.connection

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking

/** Ordered delivery of both snapshots and one-time responses, including WebRTC signaling. */
internal class WebSocketMessageBus {
    private data class Message(val text: String, val isCurrent: () -> Boolean)
    private val events = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messages = events.mapNotNull { if (it.isCurrent()) it.text else null }

    // Called on OkHttp's reader thread, never Main. When a subscriber falls behind,
    // backpressure the reader rather than dropping signaling or queuing unbounded jobs.
    fun publish(text: String, isCurrent: () -> Boolean = { true }) {
        runBlocking { events.emit(Message(text, isCurrent)) }
    }
}
