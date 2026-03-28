package com.playbridge.player.player

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

abstract class PlayerActivity : ComponentActivity() {

    // Common abstract properties and functions for player controls
    abstract fun play()
    abstract fun pause()
    abstract fun isPlaying(): Boolean
    abstract fun getMediaDuration(): Long
    abstract fun getCurrentPosition(): Long
    abstract fun seekTo(position: Long)
    abstract fun getVideoSurfaceView(): android.view.SurfaceView?

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Handle window insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ensure only one player activity is alive at a time so that pressing back always
        // returns to the home/library screen rather than a previously-used player.
        current?.get()?.let { prev ->
            if (prev !== this && !prev.isFinishing) {
                prev.finish()
            }
        }
        current = java.lang.ref.WeakReference(this)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Dynamic buffer configuration
    // -------------------------------------------------------------------------

    /**
     * Buffer caps computed from the device's current available memory so that
     * low-RAM TV hardware (e.g. Hisense, ~1 GB free) doesn't trigger Android's
     * memory manager into trimming buffer allocations — which causes the visible
     * buffer bar to collapse a few seconds after playback starts.
     *
     * Tiers (based on availMem at player launch):
     *
     *   ≥ 1 500 MB  →  120 s / 64 s back / 256 MB  (high-end, emulator-class)
     *   ≥   800 MB  →   90 s / 45 s back / 192 MB
     *   ≥   400 MB  →   60 s / 30 s back / 128 MB
     *      < 400 MB  →   30 s / 15 s back /  64 MB  (very constrained)
     */
    data class BufferConfig(
        /** ExoPlayer DefaultLoadControl maxBufferMs */
        val maxBufferMs: Int,
        /** ExoPlayer DefaultLoadControl back-buffer duration */
        val backBufferMs: Int,
        /** ExoPlayer DefaultLoadControl byte cap (passed to setTargetBufferBytes) */
        val targetBytes: Int,
        /** MPV demuxer-max-bytes option string, e.g. "128MiB" */
        val demuxerMaxBytes: String,
        /** MPV demuxer-max-back-bytes option string */
        val demuxerMaxBackBytes: String,
    )

    fun computeBufferConfig(): BufferConfig {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        return when {
            availMb >= 1_500 -> BufferConfig(120_000, 60_000, 256 * 1024 * 1024, "256MiB", "64MiB")
            availMb >=   800 -> BufferConfig( 90_000, 45_000, 192 * 1024 * 1024, "192MiB", "48MiB")
            availMb >=   400 -> BufferConfig( 60_000, 30_000, 128 * 1024 * 1024, "128MiB", "32MiB")
            else             -> BufferConfig( 30_000, 15_000,  64 * 1024 * 1024,  "64MiB", "16MiB")
        }
    }

    companion object {
        @Volatile
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null
    }
}