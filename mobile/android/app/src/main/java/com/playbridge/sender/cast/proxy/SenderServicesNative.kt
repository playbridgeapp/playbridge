package com.playbridge.sender.cast.proxy

import android.util.Log

/**
 * Minimal JNI surface for the embedded Rust sender-services worker
 * (stream proxy + browser-receiver host).
 */
internal object SenderServicesNative {
    private const val TAG = "SenderServicesNative"

    /** Must match stream-proxy-rust `UPSTREAM_JNI_ABI_VERSION`. */
    const val EXPECTED_UPSTREAM_ABI: Int = 1

    @Volatile
    var libraryLoaded: Boolean = false
        private set

    /**
     * True when the packaged native lib exposes the expected upstream ABI and
     * HttpURLConnection callbacks were installed successfully.
     */
    @Volatile
    var jniUpstreamReady: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("playbridge_cast_core_ffi")
            libraryLoaded = true
            // Wire Media3-equivalent HttpURLConnection origin fetch into the
            // embedded stream-proxy (upstream-jni). Safe to call before start().
            try {
                val abi = runCatching { upstreamAbiVersion() }.getOrDefault(0)
                if (abi != EXPECTED_UPSTREAM_ABI) {
                    Log.e(
                        TAG,
                        "Rust proxy JNI upstream ABI mismatch: got $abi, " +
                            "expected $EXPECTED_UPSTREAM_ABI — Via phone remotes " +
                            "will use LocalProxy fallback (rebuild libplaybridge_cast_core_ffi.so)",
                    )
                    jniUpstreamReady = false
                } else {
                    val installed = installUpstreamHttpClient()
                    val registered = runCatching { upstreamCallbacksRegistered() }.getOrDefault(false)
                    jniUpstreamReady = installed && registered
                    if (!jniUpstreamReady) {
                        Log.e(
                            TAG,
                            "Rust proxy JNI upstream not ready " +
                                "(install=$installed registered=$registered) — " +
                                "Via phone remotes will use LocalProxy fallback",
                        )
                    } else {
                        Log.i(TAG, "Rust proxy JNI upstream ready (ABI $abi)")
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                jniUpstreamReady = false
                Log.e(
                    TAG,
                    "Rust proxy JNI upstream symbols missing from packaged .so " +
                        "(${e.message}) — Via phone remotes will use LocalProxy fallback. " +
                        "Rebuild with cast/build-android.sh (sender-services-android).",
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Native library unavailable: ${e.message}")
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

    /**
     * stream-proxy-rust `upstream-jni` callback ABI version (Android builds).
     * Returns 0 if the native lib was not built with sender-services-android.
     */
    external fun upstreamAbiVersion(): Int

    /** True when host origin-fetch callbacks are installed. */
    external fun upstreamCallbacksRegistered(): Boolean

    /**
     * Register [JniUpstreamHttpClient] as the proxy origin transport.
     * @return true when callbacks are live.
     */
    external fun installUpstreamHttpClient(): Boolean
}
