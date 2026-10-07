package com.playbridge.player.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Maps a live socket to the connection id carried on later commands, and to the
 * paired phone name once that socket is authenticated.
 *
 * Pairing approval authenticates the same socket that sent `pairing_commit` and
 * does not send a follow-up `auth` frame. The handshake device name has to be
 * recorded there. Token reconnects record the name looked up from the pairing store.
 * Forgetting either leaves [nameFor] null and the TV prompt has no sender.
 */
class ConnectionSenderNames {
    private val ids = ConcurrentHashMap<Any, Long>()
    private val byId = ConcurrentHashMap<Long, Any>()
    private val names = ConcurrentHashMap<Long, String>()
    private val nextId = AtomicLong(1)

    fun open(connection: Any): Long {
        val id = nextId.getAndIncrement()
        val previous = ids.put(connection, id)
        if (previous != null) {
            byId.remove(previous)
            names.remove(previous)
        }
        byId[id] = connection
        return id
    }

    fun close(connection: Any): Long? {
        val id = ids.remove(connection) ?: return null
        byId.remove(id)
        names.remove(id)
        return id
    }

    fun idOf(connection: Any): Long? = ids[connection]

    @Suppress("UNCHECKED_CAST")
    fun <T> connection(connectionId: Long): T? = byId[connectionId] as T?

    fun remember(connection: Any, name: String?) {
        val id = ids[connection] ?: return
        rememberId(id, name)
    }

    fun rememberId(connectionId: Long, name: String?) {
        val cleaned = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (byId.containsKey(connectionId)) names[connectionId] = cleaned
    }

    fun nameFor(connectionId: Long): String? = names[connectionId]

    fun clear() {
        ids.clear()
        byId.clear()
        names.clear()
    }
}

/**
 * Name to attach when a socket becomes authenticated.
 * Token auth has a store lookup. Pairing approval has only the handshake name.
 */
fun authenticatedSenderName(handshakeDeviceName: String?, tokenOwnerName: String?): String? =
    tokenOwnerName?.trim()?.takeIf { it.isNotEmpty() }
        ?: handshakeDeviceName?.trim()?.takeIf { it.isNotEmpty() }
