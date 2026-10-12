package com.playbridge.sender.cast.remote

internal enum class RemoteFeedback { PAD_STEP, SEEK_BOUND, PLAY_PAUSE, VOLUME, SCRUB, AXIS_LOCK, HOLD_START }

/** One allow-list for all remote feedback; the Android sink respects View/system settings. */
internal class RemoteHaptics(private val enabled: () -> Boolean, private val emit: () -> Unit) {
    fun perform(feedback: RemoteFeedback) {
        if (enabled() && feedback in ALLOWED) emit()
    }
    companion object {
        val ALLOWED = setOf(RemoteFeedback.PAD_STEP, RemoteFeedback.SEEK_BOUND, RemoteFeedback.PLAY_PAUSE)
    }
}

internal fun crossedSeekBound(previous: Float, next: Float, duration: Long): Boolean =
    duration > 0 && (next <= 0f && previous > 0f || next >= duration.toFloat() && previous < duration.toFloat())

/** The first tap already clicked; suppress the second click, but preserve image reset. */
internal fun remoteDoubleTapAction(image: Boolean): String = if (image) "reset" else "none"
