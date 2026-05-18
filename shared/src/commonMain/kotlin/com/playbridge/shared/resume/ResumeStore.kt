package com.playbridge.shared.resume

/**
 * Platform-agnostic store for resume positions and minimal playback metadata.
 *
 * The Android implementation wraps [HistoryStore]; Apple implementations may use
 * [multiplatform-settings] or Core Data.
 */
interface ResumeStore {
    /**
     * Returns the last saved position (ms) for [url], or 0L if none.
     */
    suspend fun loadPosition(url: String): Long

    /**
     * Persists [positionMs] as the resume point for [url].
     */
    suspend fun savePosition(url: String, positionMs: Long)
}
