package com.playbridge.sender.cast.remote

import com.playbridge.sender.connection.QueueCommandResult
import kotlinx.coroutines.flow.StateFlow

/** Reuses the existing connection and confirmation owner; never connects or polls. */
interface NativeRemoteTransport {
    val snapshot: StateFlow<RemoteReceiverSnapshot>
    fun send(command: String): Boolean
    fun pointer(event: String, x: Float = 0f, y: Float = 0f): Boolean
    fun endPointerGesture(): Boolean
    /** Descriptor routes reset only a touch anchor that the gesture actually established. */
    fun endPointerGesture(resetAnchor: Boolean): Boolean = endPointerGesture()
    suspend fun confirmed(requestId: String, command: String, timeoutMs: Long): QueueCommandResult?
    fun stopMirror(): Boolean
}
