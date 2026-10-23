package com.playbridge.sender.browser

import android.os.SystemClock
import android.util.Log
import com.playbridge.sender.BuildConfig

/**
 * Debug-only open timings for bridged apps: labels and elapsed milliseconds, never URLs.
 * Shows whether a tap found a live (kept or preloaded) page or had to load one.
 */
internal object BridgedAppStartupTiming {
    private const val TAG = "BridgedStartup"
    private var tabId: String? = null
    private var start = 0L
    private var sawUnpainted = false
    private var sawLoading = false
    private var painted = false

    fun begin(tabId: String, live: Boolean) {
        if (!BuildConfig.DEBUG) return
        start = SystemClock.elapsedRealtime()
        Log.d(TAG, "tile_tap session=${if (live) "live" else "cold"}")
        // A live page is already on screen when the tab is selected.
        this.tabId = if (live) null else tabId
        sawUnpainted = false
        sawLoading = false
        painted = false
    }

    /** Called from the store mirror for every tab update. */
    fun observe(tabId: String, firstContentfulPaint: Boolean, loading: Boolean) {
        if (!BuildConfig.DEBUG || tabId != this.tabId) return
        val elapsed = SystemClock.elapsedRealtime() - start
        // Ignore paint and load state left over from before this open.
        if (!firstContentfulPaint) sawUnpainted = true
        else if (sawUnpainted && !painted) { painted = true; Log.d(TAG, "first_contentful_paint elapsed_ms=$elapsed") }
        if (loading) sawLoading = true
        else if (sawLoading) { Log.d(TAG, "load_finished elapsed_ms=$elapsed"); this.tabId = null }
    }
}
