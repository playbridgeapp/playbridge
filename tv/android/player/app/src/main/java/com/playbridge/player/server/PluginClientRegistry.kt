package com.playbridge.player.server

/** Serializes client replacement and delivery, including the broadcast fallback. */
internal class PluginClientRegistry<C : Any, M>(private val send: (C, M) -> Boolean) {
    private var client: C? = null

    @Synchronized fun current(): C? = client

    @Synchronized fun replace(next: C): C? {
        val old = client
        client = next
        return old
    }

    /** A late disconnect/goodbye must not clear a replacement client. */
    @Synchronized fun remove(expected: C): Boolean {
        if (client !== expected) return false
        client = null
        return true
    }

    @Synchronized fun deliver(message: M, fallback: () -> Unit) {
        val target = client
        if (target != null && send(target, message)) return
        if (target != null) remove(target)
        fallback()
    }
}
