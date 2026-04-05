package com.playbridge.player.player

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.playbridge.player.server.ServerService

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
        // Mark the server context as "player" so the request_pairing guard works regardless
        // of whether this activity was launched by handleCommand() (phone cast) or directly
        // from the TV's history/favourites screen (which bypasses handleCommand entirely).
        ServerService.notifyContextPlayer()
    }

    override fun onDestroy() {
        if (current?.get() === this) {
            current = null
            // Only reset activeContext when THIS is genuinely the last player being torn down —
            // not when it's being replaced by a new PlayerActivity (which calls prev.finish()
            // from its own onCreate, setting `current` to itself before our onDestroy runs).
            // If we reset here while a new player is already in `current`, activeContext would
            // become "idle" during the new session, letting request_pairing open PairingScreen
            // on top of the new video and killing it.
            ServerService.notifyContextIdle()
        }
        super.onDestroy()
    }


    /**
     * Buffer caps computed from the device's current available memory.
     *
     * ExoPlayer strategy: time-based primary constraint (prioritizeTimeOverSizeThresholds=true),
     * with [targetBytes] as a hard ceiling for ultra-high-bitrate content only (e.g. 4K REMUX
     * at 100 Mbps).  Back-buffer is intentionally omitted: setBackBuffer() allocates from the
     * same DefaultAllocator pool as the forward buffer, so a 30 s back-buffer at 25 Mbps eats
     * ~94 MB of a 128 MB cap — starving the forward buffer and causing the oscillating
     * "buffer reset" symptom observed on Hisense TVs.
     *
     * MPV strategy: demuxer-max-bytes caps the in-RAM ring buffer; demuxer-max-back-bytes
     * is managed independently by libmpv outside Android's allocator, so it does not compete.
     *
     * Tiers (based on availMem at player launch):
     *
     *   ≥ 1 500 MB  →  90 s / 800 MB safety cap  (emulator / high-end)
     *   ≥   800 MB  →  60 s / 400 MB safety cap
     *   ≥   400 MB  →  45 s / 200 MB safety cap
     *      < 400 MB  →  30 s / 100 MB safety cap  (Hisense / very constrained)
     */
    data class BufferConfig(
        /** ExoPlayer DefaultLoadControl maxBufferMs (primary time-based cap) */
        val maxBufferMs: Int,
        /** ExoPlayer DefaultLoadControl byte ceiling — guards against very high-bitrate content */
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
            availMb >= 1_500 -> BufferConfig( 90_000, 800 * 1024 * 1024, "256MiB", "64MiB")
            availMb >=   800 -> BufferConfig( 60_000, 400 * 1024 * 1024, "192MiB", "48MiB")
            availMb >=   400 -> BufferConfig( 45_000, 200 * 1024 * 1024, "128MiB", "32MiB")
            else             -> BufferConfig( 30_000, 100 * 1024 * 1024,  "64MiB", "16MiB")
        }
    }

    companion object {
        @Volatile
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null
    }
}