package com.playbridge.sender.cast.remote

import android.os.VibrationEffect
import android.view.HapticFeedbackConstants
import androidx.annotation.RequiresApi

enum class RemoteHapticLevel(val storedValue: String) {
    Light("light"), Medium("medium"), Strong("strong");

    companion object {
        fun fromStoredValue(value: String?): RemoteHapticLevel = entries.firstOrNull { it.storedValue == value } ?: Light
    }
}

@RequiresApi(29)
internal fun remoteHapticEffectId(level: RemoteHapticLevel): Int = when (level) {
    RemoteHapticLevel.Light -> VibrationEffect.EFFECT_TICK
    RemoteHapticLevel.Medium -> VibrationEffect.EFFECT_CLICK
    RemoteHapticLevel.Strong -> VibrationEffect.EFFECT_HEAVY_CLICK
}

internal fun remoteHapticFallbackConstant(level: RemoteHapticLevel): Int = when (level) {
    RemoteHapticLevel.Light -> HapticFeedbackConstants.CLOCK_TICK
    RemoteHapticLevel.Medium -> HapticFeedbackConstants.VIRTUAL_KEY
    RemoteHapticLevel.Strong -> HapticFeedbackConstants.LONG_PRESS
}

internal enum class RemoteFeedback { PAD_STEP, SEEK_BOUND, PLAY_PAUSE, VOLUME, SCRUB, AXIS_LOCK, HOLD_START }

/** One allow-list for all remote feedback; enabled is the app preference, the sink respects system settings. */
internal class RemoteHaptics(private val enabled: () -> Boolean, private val emit: () -> Unit) {
    fun perform(feedback: RemoteFeedback) {
        if (enabled() && feedback in ALLOWED) emit()
    }
    companion object {
        val ALLOWED = setOf(RemoteFeedback.PAD_STEP, RemoteFeedback.SEEK_BOUND, RemoteFeedback.PLAY_PAUSE, RemoteFeedback.VOLUME, RemoteFeedback.SCRUB)
    }
}

internal fun crossedSeekBound(previous: Float, next: Float, duration: Long): Boolean =
    duration > 0 && (next <= 0f && previous > 0f || next >= duration.toFloat() && previous < duration.toFloat())

internal fun crossedScrubInterval(previous: Float, next: Float): Boolean =
    previous.coerceAtLeast(0f).toLong() / 10_000L != next.coerceAtLeast(0f).toLong() / 10_000L

/** Bound feedback takes priority; a pointer event emits at most one tick, even across many intervals. */
internal fun scrubFeedback(
    previous: Float,
    next: Float,
    duration: Long,
    started: Boolean,
    nowMs: Long,
    lastTickMs: Long?,
): RemoteFeedback? {
    if (duration <= 0L) return null
    if (crossedSeekBound(previous, next, duration)) return RemoteFeedback.SEEK_BOUND
    if (!started && !crossedScrubInterval(previous, next)) return null
    if (lastTickMs != null && nowMs - lastTickMs < 40L) return null
    return RemoteFeedback.SCRUB
}

/** The first tap already clicked; suppress the second click, but preserve image reset. */
internal fun remoteDoubleTapAction(image: Boolean): String = if (image) "reset" else "none"
