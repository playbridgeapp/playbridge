package com.playbridge.sender.cast.remote

/**
 * The Remote's one volume control. [exact] routes get a slider in the sheet; step-only routes
 * (whole-TV control, Roku, relative receivers) get large −/+ buttons instead.
 */
internal data class RemoteVolumePill(val percent: Int?, val muted: Boolean, val exact: Boolean) {
    /** Exact routes always show a value ("—" until reported); step routes may have none. */
    val text: String get() = if (muted) "Muted" else percent?.toString() ?: if (exact) "—" else ""
}

/**
 * Whole-TV control (Android TV Remote pairing) and a system route falling back to app
 * attenuation can only step, so they have no absolute target for a slider.
 */
internal fun remoteVolumePill(
    absolute: Boolean,
    wholeTvControl: Boolean,
    appFallbackOnSystemRoute: Boolean,
    percent: Int?,
    muted: Boolean?,
): RemoteVolumePill = RemoteVolumePill(
    percent = percent?.coerceIn(0, 100),
    muted = muted == true,
    exact = absolute && !wholeTvControl && !appFallbackOnSystemRoute,
)

/** Latest value wins: at most one send per [intervalMs], always followed by the final value. */
internal class LatestVolumeThrottle(private val intervalMs: Long = 125) {
    private var pending: Int? = null
    private var lastSent: Long? = null
    val hasPending get() = pending != null
    fun offer(percent: Int) { pending = percent.coerceIn(0, 100) }
    /** The value to send now, or null while the previous send is too recent. */
    fun poll(now: Long): Int? {
        val value = pending ?: return null
        if (lastSent?.let { now - it < intervalMs } == true) return null
        pending = null
        lastSent = now
        return value
    }
    fun waitMs(now: Long): Long = lastSent?.let { (intervalMs - (now - it)).coerceAtLeast(0) } ?: 0
    fun clear() { pending = null; lastSent = null }
}
