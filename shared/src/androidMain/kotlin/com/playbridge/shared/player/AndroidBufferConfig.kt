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
    // ExoPlayer's DefaultAllocator holds the buffer on the JAVA HEAP. Even if the device
    // has gigabytes of physical RAM (availMb), the app process is constrained by its Dalvik/ART
    // JVM max heap limit (Runtime.getRuntime().maxMemory()), typically 256MB on phones and TVs
    // without largeHeap.
    //
    // Setting targetBytes anywhere near the process heap limit guarantees an OutOfMemoryError
    // when buffering high-bitrate video while coexisting with other components (GeckoView, Coil,
    // Compose). The byte ceiling must therefore be a HARD cap clamped to at most ~20–25% of the
    // JVM process max heap (e.g. 48–64MB on a 256MB heap, 128MB on a 512MB heap).
    //
    // prioritizeTime is false in every tier so the byte cap stops buffering before exhausting heap.
    // MPV demuxer options allocate native C memory directly from physical RAM, so they remain
    // sized by availMb.
    fun compute(context: Context): BufferConfig {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)

        // Heap-relative clamp: never let ExoPlayer buffer chunks exceed 25% of the JVM max heap.
        // On a 256MB heap, maxHeapClamp is 64MB (~40–50s of 1080p buffer) leaving >190MB of heap
        // free for the rest of the application.
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val maxHeapClamp = (maxHeapBytes * 0.25).toInt().coerceAtLeast(32 * 1024 * 1024)

        return when {
            availMb >= 1_500 -> BufferConfig( 60_000, minOf(256 * 1024 * 1024, maxHeapClamp), "256MiB", "64MiB", false)
            availMb >=   800 -> BufferConfig( 50_000, minOf(128 * 1024 * 1024, maxHeapClamp), "192MiB", "48MiB", false)
            availMb >=   400 -> BufferConfig( 40_000, minOf( 96 * 1024 * 1024, maxHeapClamp), "128MiB", "32MiB", false)
            else             -> BufferConfig( 30_000, minOf( 64 * 1024 * 1024, maxHeapClamp),  "64MiB", "16MiB", false)
        }
    }
}
