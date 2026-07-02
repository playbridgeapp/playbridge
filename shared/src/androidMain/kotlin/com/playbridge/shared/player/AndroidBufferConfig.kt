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
    // ExoPlayer's DefaultAllocator holds the buffer on the JAVA HEAP, and a TV app's
    // heap is typically 256–512MB. The byte ceiling must therefore be a HARD cap:
    // prioritizeTime=true made it advisory, so a 60–100Mbps 4K remux buffering the
    // full time window allocated hundreds of MB and OOM-killed the app mid-playback.
    // Ceilings are sized so the cap — not the heap — is what stops buffering, and
    // prioritizeTime is false in every tier. High-bitrate content simply carries a
    // shorter (still tens of seconds) buffer; low/normal bitrates hit the time cap
    // long before the byte cap and are unaffected.
    fun compute(context: Context): BufferConfig {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        return when {
            availMb >= 1_500 -> BufferConfig( 60_000, 256 * 1024 * 1024, "256MiB", "64MiB", false)
            availMb >=   800 -> BufferConfig( 50_000, 128 * 1024 * 1024, "192MiB", "48MiB", false)
            availMb >=   400 -> BufferConfig( 40_000,  96 * 1024 * 1024, "128MiB", "32MiB", false)
            else             -> BufferConfig( 30_000,  64 * 1024 * 1024,  "64MiB", "16MiB", false)
        }
    }
}
