package com.playbridge.sender.browser

/** What decides whether the most recently opened bridged app is preloaded at startup. */
internal data class BridgedAppPreloadConditions(
    val enabled: Boolean,
    val lastOpenedAt: Long?,
    val now: Long,
    val alreadyLive: Boolean,
    val online: Boolean,
    val metered: Boolean,
    val dataSaver: Boolean,
    val powerSave: Boolean,
    val lowMemory: Boolean,
    val maxAliveSessions: Int,
)

/**
 * Preload one app, the most recently opened one, so opening it from the dashboard shows a
 * live page. It is skipped whenever it would cost the user data, battery or memory they
 * have asked to save, or when the app hasn't been used recently.
 */
internal object BridgedAppPreloadPolicy {
    const val RECENT_USE_MS = 14L * 24 * 60 * 60 * 1000

    fun shouldPreload(c: BridgedAppPreloadConditions): Boolean {
        val openedAt = c.lastOpenedAt ?: return false
        return c.enabled && !c.alreadyLive && c.online && !c.metered && !c.dataSaver && !c.powerSave &&
            !c.lowMemory && c.maxAliveSessions >= 2 && openedAt <= c.now && c.now - openedAt < RECENT_USE_MS
    }
}
