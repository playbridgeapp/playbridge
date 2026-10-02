package com.playbridge.sender.data.nuvio

internal object NuvioLog {
    fun w(tag: String, message: String) {
        try {
            android.util.Log.w(tag, message)
        } catch (_: Throwable) {
            // JVM unit tests have no Android logger.
        }
    }

    fun i(tag: String, message: String) {
        try {
            android.util.Log.i(tag, message)
        } catch (_: Throwable) {
            // JVM unit tests have no Android logger.
        }
    }
}
