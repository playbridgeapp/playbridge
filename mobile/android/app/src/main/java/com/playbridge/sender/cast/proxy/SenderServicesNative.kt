package com.playbridge.sender.cast.proxy

import android.util.Log

/**
 * Minimal JNI surface for the embedded Rust sender-services worker
 * (stream proxy + unused-for-now browser host).
 */
internal object SenderServicesNative {
    private const val TAG = "SenderServicesNative"

    @Volatile
    var libraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("playbridge_cast_core_ffi")
            libraryLoaded = true
        } catch (e: Throwable) {
            Log.w(TAG, "Native library unavailable: ${e.message}")
            libraryLoaded = false
        }
    }

    external fun abiVersion(): Int
    external fun start(): Long
    external fun submitJson(handle: Long, commandJson: String): Boolean
    external fun nextEvent(handle: Long, waitMs: Long): String?
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}
