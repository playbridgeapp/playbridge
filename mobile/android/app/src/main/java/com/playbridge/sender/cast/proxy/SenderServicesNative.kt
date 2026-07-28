package com.playbridge.sender.cast.proxy

import android.util.Log

/**
 * Minimal JNI surface for the embedded Rust sender-services worker
 * (stream proxy + browser-receiver host).
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
            // Wire Media3-equivalent HttpURLConnection origin fetch into the
            // embedded stream-proxy (upstream-jni). Safe to call before start().
            val installed = runCatching { installUpstreamHttpClient() }.getOrDefault(false)
            if (!installed) {
                Log.w(TAG, "Upstream HttpURLConnection callbacks not installed")
            }
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
