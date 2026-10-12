package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.PlaybackStatus
import kotlinx.coroutines.flow.StateFlow

/** Published by the existing session owner, including otherwise identical accepted ticks. */
data class ExternalRemoteObservation(
    val status: PlaybackStatus? = null, val loadGeneration: Long = 0L,
    val sequence: Long = 0L, val atMs: Long = 0L, val controlFailureVersion: Long = 0L,
)
data class ExternalRemoteSnapshot(
    val identity: Any? = null, val receiverSnapshot: RemoteReceiverSnapshot = RemoteReceiverSnapshot(),
)

/** No transport lifecycle: calls the existing singleton session owner only. */
interface ExternalRemoteTransport {
    val snapshot: StateFlow<ExternalRemoteSnapshot>
    fun isCurrent(identity: Any, loadGeneration: Long): Boolean
    fun submit(identity: Any, loadGeneration: Long, action: RemoteAction): RemoteCommandResult
}
