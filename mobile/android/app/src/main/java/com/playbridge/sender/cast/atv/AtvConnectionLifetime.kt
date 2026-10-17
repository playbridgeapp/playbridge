package com.playbridge.sender.cast.atv

/** Socket lifetime is independent of Remote UI visibility. Timers belong to the platform owner. */
internal data class AtvConnectionLifetime(
    val foreground: Boolean = false,
    val backgroundGrace: Boolean = false,
    val backgroundDeadlineMs: Long? = null,
) {
    val keepConnection: Boolean get() = foreground || backgroundGrace

    fun foregroundChanged(value: Boolean, nowMs: Long = 0): AtvConnectionLifetime =
        if (value == foreground) this else AtvConnectionLifetime(value, backgroundGrace = !value,
            backgroundDeadlineMs = if (value) null else nowMs + BACKGROUND_GRACE_MS)

    fun graceExpired(): AtvConnectionLifetime =
        if (foreground) this else copy(backgroundGrace = false, backgroundDeadlineMs = null)

    fun expiredAt(nowMs: Long): Boolean = !foreground && backgroundDeadlineMs?.let { nowMs >= it } == true

    companion object {
        const val BACKGROUND_GRACE_MS = 60_000L
        fun waitForDiscovery(hasSelection: Boolean, settled: Boolean): Boolean = !hasSelection && !settled
    }
}
