package com.playbridge.player.proxy

import android.util.Log

/**
 * Minimal JNI surface for the embedded Rust receiver stream-proxy worker on Android TV.
 */
internal object ReceiverProxyNative {
    private const val TAG = "ReceiverProxyNative"

    /** Must match `cast/ffi` `pb_sender_services_abi_version`. */
    const val EXPECTED_ABI: Int = 2

    /** Must match `stream-proxy-rust` `UPSTREAM_JNI_ABI_VERSION`. */
    const val EXPECTED_UPSTREAM_ABI: Int = 1

    @Volatile
    var libraryLoaded: Boolean = false
        private set

    /**
     * True when the packaged native library exposes the expected service and upstream ABIs
     * and the Kotlin origin-fetch callbacks were installed successfully.
     */
    @Volatile
    var jniUpstreamReady: Boolean = false
        private set

    val isReady: Boolean
        get() = libraryLoaded && jniUpstreamReady

    init {
        try {
            System.loadLibrary("playbridge_cast_core_ffi")
            val serviceAbi = runCatching { abiVersion() }.getOrDefault(0)
            if (serviceAbi != EXPECTED_ABI) {
                logError(
                    "Rust receiver proxy ABI mismatch: got $serviceAbi, expected $EXPECTED_ABI",
                )
                libraryLoaded = false
                jniUpstreamReady = false
            } else {
                libraryLoaded = true
                try {
                    val upstreamAbi = runCatching { upstreamAbiVersion() }.getOrDefault(0)
                    if (upstreamAbi != EXPECTED_UPSTREAM_ABI) {
                        logError(
                            "Rust receiver proxy JNI upstream ABI mismatch: got $upstreamAbi, " +
                                "expected $EXPECTED_UPSTREAM_ABI",
                        )
                        jniUpstreamReady = false
                    } else {
                        val installed = installUpstreamHttpClient()
                        val registered = runCatching {
                            upstreamCallbacksRegistered()
                        }.getOrDefault(false)
                        jniUpstreamReady = installed && registered
                        if (!jniUpstreamReady) {
                            logError(
                                "Rust receiver proxy JNI upstream not ready " +
                                    "(install=$installed registered=$registered)",
                            )
                        } else {
                            logInfo("Rust receiver proxy JNI upstream ready (ABI $upstreamAbi)")
                        }
                    }
                } catch (e: UnsatisfiedLinkError) {
                    jniUpstreamReady = false
                    logError(
                        "Rust receiver proxy JNI upstream symbols missing (${e.message}); " +
                            "rebuild with cast/build-android.sh --tv",
                    )
                }
            }
        } catch (e: Throwable) {
            logWarn("Native receiver proxy library unavailable: ${e.message}")
            libraryLoaded = false
            jniUpstreamReady = false
        }
    }

    external fun abiVersion(): Int
    external fun start(): Long
    external fun submitJson(handle: Long, commandJson: String): Boolean
    external fun nextEvent(handle: Long, waitMs: Long): String?
    external fun cancel(handle: Long)
    external fun free(handle: Long)

    external fun upstreamAbiVersion(): Int
    external fun upstreamCallbacksRegistered(): Boolean
    external fun installUpstreamHttpClient(): Boolean

    private fun logInfo(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
            // Pure JVM unit tests do not mock android.util.Log.
        }
    }

    private fun logWarn(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {
            // Pure JVM unit tests do not mock android.util.Log.
        }
    }

    private fun logError(message: String) {
        try {
            Log.e(TAG, message)
        } catch (_: Throwable) {
            // Pure JVM unit tests do not mock android.util.Log.
        }
    }
}
