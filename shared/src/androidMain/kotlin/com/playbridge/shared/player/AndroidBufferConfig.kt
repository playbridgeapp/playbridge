package com.playbridge.shared.player

import android.app.ActivityManager
import android.content.Context

/**
 * Buffer caps computed from the device's current available memory.
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
    /** Whether ExoPlayer should prioritize time goals over size thresholds. */
    val prioritizeTime: Boolean,
)

object AndroidBufferConfig {
    fun compute(context: Context): BufferConfig {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        return when {
            availMb >= 1_500 -> BufferConfig( 90_000, 800 * 1024 * 1024, "256MiB", "64MiB", true)
            availMb >=   800 -> BufferConfig( 60_000, 400 * 1024 * 1024, "192MiB", "48MiB", true)
            availMb >=   400 -> BufferConfig( 45_000, 200 * 1024 * 1024, "128MiB", "32MiB", true)
            else             -> BufferConfig( 30_000, 128 * 1024 * 1024,  "64MiB", "16MiB", false)
        }
    }
}
