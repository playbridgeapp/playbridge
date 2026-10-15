package com.playbridge.player.server

import com.playbridge.shared.protocol.MAX_RECEIVER_CAPABILITIES_REVISION
import com.playbridge.shared.protocol.createReceiverCapabilitiesJson

/** One serialized advertisement owner for auth, protected pairing and opted-in pushes.
 * Callbacks enqueue frames synchronously under this lock, preserving auth/update order.
 * Session keys are live sockets, never persisted receiver/sender IDs.
 */
internal class ReceiverCapabilityPublisher<C : Any>(
    private val resolve: () -> TvCapabilities,
    private val send: (C, String) -> Unit,
    private val close: (C) -> Unit = {},
) {
    private var snapshot = resolve()
    private val sessions = mutableMapOf<C, Long?>()

    @Synchronized fun authenticate(connection: C, credentials: (TvCapabilities) -> Unit) {
        refresh()
        try {
            credentials(snapshot)
            sessions[connection] = null
        } catch (error: Exception) {
            sessions.remove(connection)
            close(connection)
            throw error
        }
    }

    @Synchronized fun subscribe(connection: C) {
        if (!sessions.containsKey(connection) || sessions[connection] != null) return
        refresh()
        publish(connection, 1)
    }

    @Synchronized fun forget(connection: C) { sessions.remove(connection) }
    @Synchronized fun clear() { sessions.clear() }

    @Synchronized fun refresh() {
        val next = resolve()
        if (next == snapshot) return
        snapshot = next
        for ((connection, revision) in sessions.toMap()) {
            if (revision == null) continue
            if (revision == MAX_RECEIVER_CAPABILITIES_REVISION) {
                sessions.remove(connection)
                close(connection)
            } else publish(connection, revision + 1)
        }
    }

    private fun publish(connection: C, revision: Long) {
        // Do not let a closed socket prevent replacement for other subscribers.
        try {
            send(connection, createReceiverCapabilitiesJson(revision, snapshot.capabilities))
            sessions[connection] = revision
        } catch (_: Exception) {
            sessions.remove(connection)
            close(connection)
        }
    }
}
