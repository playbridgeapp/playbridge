package com.playbridge.sender.browser

/** Revoking document authority and receiving transport teardown may race/reenter. */
internal class DocumentPortLifetime(private val cleanup: () -> Unit) {
    var closed = false
        private set

    fun close(): Boolean {
        if (closed) return false
        closed = true
        cleanup()
        return true
    }
}
