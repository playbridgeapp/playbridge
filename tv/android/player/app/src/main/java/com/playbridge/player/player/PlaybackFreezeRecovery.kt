package com.playbridge.player.player

import kotlin.math.abs

/**
 * Coordinates stream freeze and premature-EOF reload recovery for MPV playback on Android TV.
 *
 * Mirrors Apple TV's `PlaybackFreezeRecovery`:
 * - Waits passively while a slow server is still filling the demuxer cache (`bufferedEndMs` grows).
 * - Reloads at the last good position only when genuinely stuck (position and cache both flat for
 *   [STALL_RELOAD_DELAY_MS], or frozen at EOF for [EOF_FREEZE_RELOAD_DELAY_MS], or on a recoverable
 *   premature-EOF / 5xx / network error after playback has started).
 * - Caps retries to [MAX_RECOVERIES_WITHOUT_PROGRESS] consecutive attempts without
 *   [MIN_PROGRESS_ADVANCE_MS] of forward progress and [MAX_TOTAL_RECOVERIES] total attempts per
 *   item (preserved across pauses and seeks; reset only on a new item / retry launch).
 * - Never triggers during pause (user or party), seeking, live streams, pre-buffering, or Still watching.
 */
internal class PlaybackFreezeRecovery {
    sealed class Decision {
        data object None : Decision()
        data class Reload(
            val resumePositionMs: Long,
            val attempt: Int,
            val withoutProgressAttempt: Int,
        ) : Decision()

        data class FailTerminal(
            val totalAttempts: Int,
            val withoutProgressAttempts: Int,
        ) : Decision()
    }

    var totalRecoveries: Int = 0
        private set
    var recoveriesWithoutProgress: Int = 0
        private set
    var lastRecoveryPositionMs: Long? = null
        private set
    var lastGoodPositionMs: Long = 0L
        private set
    var itemHasEverRenderedFirstFrame: Boolean = false
        private set

    private var frozenTicks: Int = 0
    private var stallSinceMs: Long? = null
    private var lastSamplePositionMs: Long? = null
    private var lastSampleBufferedEndMs: Long? = null

    fun markFirstFrame() {
        itemHasEverRenderedFirstFrame = true
    }

    fun resetForNewItem() {
        totalRecoveries = 0
        recoveriesWithoutProgress = 0
        lastRecoveryPositionMs = null
        lastGoodPositionMs = 0L
        itemHasEverRenderedFirstFrame = false
        resetTransientFreeze()
    }

    /**
     * Resets transient stall timers and anchor after an intentional position change (seek, switch).
     * Intentionally preserves [totalRecoveries] so seeking/pausing cannot bypass the per-item cap.
     */
    fun resetForPositionChange(newPositionMs: Long? = null) {
        recoveriesWithoutProgress = 0
        lastRecoveryPositionMs = null
        if (newPositionMs != null && newPositionMs >= 0L) {
            lastGoodPositionMs = newPositionMs
        }
        resetTransientFreeze()
    }

    private fun resetTransientFreeze() {
        frozenTicks = 0
        stallSinceMs = null
        lastSamplePositionMs = null
        lastSampleBufferedEndMs = null
    }

    /**
     * Periodically evaluates playback health (1 Hz alongside stall monitoring).
     * Handles both buffering stalls (`paused-for-cache`) and silent freezes (playing status with
     * non-advancing position and non-growing cache).
     */
    fun sample(
        nowMs: Long,
        playbackRequested: Boolean,
        isPaused: Boolean = false,
        isSeeking: Boolean = false,
        isLive: Boolean = false,
        isPreBuffering: Boolean = false,
        isStillWatchingPrompting: Boolean = false,
        hasFirstFrame: Boolean = true,
        @Suppress("UNUSED_PARAMETER") isBuffering: Boolean = false,
        eofReached: Boolean = false,
        positionMs: Long,
        bufferedEndMs: Long,
    ): Decision {
        if (hasFirstFrame) itemHasEverRenderedFirstFrame = true
        if (!playbackRequested || isPaused || isSeeking || isLive || isPreBuffering || isStillWatchingPrompting) {
            resetTransientFreeze()
            return Decision.None
        }
        if (!itemHasEverRenderedFirstFrame && totalRecoveries == 0) {
            resetTransientFreeze()
            return Decision.None
        }

        val clampedPosMs = positionMs.coerceAtLeast(0L)
        val clampedBufferedEndMs = maxOf(clampedPosMs, bufferedEndMs.coerceAtLeast(0L))

        val prevPos = lastSamplePositionMs
        val prevBuffered = lastSampleBufferedEndMs
        lastSamplePositionMs = clampedPosMs
        lastSampleBufferedEndMs = clampedBufferedEndMs

        if (prevPos == null || prevBuffered == null) {
            if (clampedPosMs > lastGoodPositionMs) lastGoodPositionMs = clampedPosMs
            return Decision.None
        }

        val positionAdvanced = abs(clampedPosMs - prevPos) >= POSITION_ADVANCE_EPSILON_MS
        val cacheAdvanced = clampedBufferedEndMs > prevBuffered

        if (positionAdvanced) {
            if (clampedPosMs > lastGoodPositionMs) lastGoodPositionMs = clampedPosMs
            val anchor = lastRecoveryPositionMs
            if (anchor != null && clampedPosMs - anchor >= MIN_PROGRESS_ADVANCE_MS) {
                recoveriesWithoutProgress = 0
                lastRecoveryPositionMs = null
            }
            frozenTicks = 0
            stallSinceMs = null
            return Decision.None
        }

        if (cacheAdvanced) {
            // Data is still arriving into the demuxer cache (e.g. slow connection): keep waiting.
            frozenTicks = 0
            stallSinceMs = null
            return Decision.None
        }

        frozenTicks += 1
        if (stallSinceMs == null) stallSinceMs = nowMs
        if (frozenTicks < FROZEN_TICKS_THRESHOLD) return Decision.None

        val delayMs = if (eofReached) EOF_FREEZE_RELOAD_DELAY_MS else STALL_RELOAD_DELAY_MS
        val elapsed = nowMs - (stallSinceMs ?: nowMs)
        if (elapsed < delayMs) return Decision.None

        return recordRecoveryOrFail(resumePositionMs = maxOf(lastGoodPositionMs, clampedPosMs))
    }

    /**
     * Evaluates an MPV error / premature-EOF event after playback started or during a recovery
     * reload. Returns [Decision.None] when the event should take normal failure/failover handling
     * (e.g., initial load before first frame, live stream, paused, or non-recoverable HTTP 4xx/format error).
     */
    fun evaluateRecoverableError(
        errorCode: String,
        playbackRequested: Boolean,
        isPaused: Boolean = false,
        isLive: Boolean = false,
        isPreBuffering: Boolean = false,
        isStillWatchingPrompting: Boolean = false,
        positionMs: Long = lastGoodPositionMs,
    ): Decision {
        if (!playbackRequested || isPaused || isLive || isPreBuffering || isStillWatchingPrompting) {
            return Decision.None
        }
        if (!itemHasEverRenderedFirstFrame && totalRecoveries == 0) {
            return Decision.None
        }
        if (!isRecoverableErrorCode(errorCode, isReloadAttempt = totalRecoveries > 0)) {
            return Decision.None
        }
        return recordRecoveryOrFail(resumePositionMs = maxOf(lastGoodPositionMs, positionMs.coerceAtLeast(0L)))
    }

    private fun recordRecoveryOrFail(resumePositionMs: Long): Decision {
        if (totalRecoveries >= MAX_TOTAL_RECOVERIES ||
            recoveriesWithoutProgress >= MAX_RECOVERIES_WITHOUT_PROGRESS
        ) {
            resetTransientFreeze()
            return Decision.FailTerminal(
                totalAttempts = totalRecoveries,
                withoutProgressAttempts = recoveriesWithoutProgress,
            )
        }
        totalRecoveries += 1
        recoveriesWithoutProgress += 1
        lastGoodPositionMs = maxOf(lastGoodPositionMs, resumePositionMs)
        lastRecoveryPositionMs = resumePositionMs
        resetTransientFreeze()
        return Decision.Reload(
            resumePositionMs = resumePositionMs,
            attempt = totalRecoveries,
            withoutProgressAttempt = recoveriesWithoutProgress,
        )
    }

    companion object {
        const val FROZEN_TICKS_THRESHOLD = 2
        const val STALL_RELOAD_DELAY_MS = 15_000L
        const val EOF_FREEZE_RELOAD_DELAY_MS = 3_000L
        const val MAX_RECOVERIES_WITHOUT_PROGRESS = 2
        const val MAX_TOTAL_RECOVERIES = 6
        const val MIN_PROGRESS_ADVANCE_MS = 2_000L
        private const val POSITION_ADVANCE_EPSILON_MS = 150L
        private val httpStatusRegex = Regex("^mpv_http_([1-5][0-9]{2})$")

        fun isRecoverableErrorCode(errorCode: String, isReloadAttempt: Boolean): Boolean {
            if (errorCode == "mpv_playback_failed" ||
                errorCode == "mpv_network_failed" ||
                errorCode == "mpv_control_timeout"
            ) {
                return true
            }
            if (errorCode == "mpv_load_failed") {
                return isReloadAttempt
            }
            val status = httpStatusRegex.matchEntire(errorCode)?.groupValues?.get(1)?.toIntOrNull()
            return status == 429 || (status != null && status in 500..599)
        }
    }
}
