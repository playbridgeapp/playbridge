package com.playbridge.sender.cast.atv

/** Minimal JNI surface. The Stage 4c wrapper owns credentials and lifecycle. */
internal object RustAtvNative {
    init {
        System.loadLibrary("playbridge_cast_core_ffi")
    }

    external fun abiVersion(): Int
    external fun start(): Long
    external fun submitJson(handle: Long, commandJson: String): Boolean
    external fun nextEvent(handle: Long, waitMs: Long): String?
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}
