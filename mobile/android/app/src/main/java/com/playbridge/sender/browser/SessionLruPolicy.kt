package com.playbridge.sender.browser

/**
 * Which tabs keep a live engine session. The selected tab and tabs playing media are never
 * hibernated. The most recently used bridged app (for example Streams) is also kept, so
 * returning to it after browsing other tabs shows the live page instead of rebuilding it. It
 * still counts toward [maxAlive], so memory stays bounded, and it is released under memory
 * pressure.
 */
internal object SessionLruPolicy {
    /** [lru] is oldest first. Returns the ids that stay live, in the same order. */
    fun retain(
        lru: List<String>,
        maxAlive: Int,
        selectedTabId: String?,
        playingTabIds: Set<String>,
        keptBridgedAppTabId: String?,
    ): List<String> {
        val live = lru.toMutableList()
        while (live.size > maxAlive) {
            val victim = live.firstOrNull { id -> !isProtected(id, selectedTabId, playingTabIds, keptBridgedAppTabId) }
                ?: break // Everything left is selected, playing or the kept bridged app.
            live.remove(victim)
        }
        return live
    }

    fun isProtected(id: String, selectedTabId: String?, playingTabIds: Set<String>, keptBridgedAppTabId: String?): Boolean =
        id == selectedTabId || id in playingTabIds || id == keptBridgedAppTabId

    /** The bridged app to keep: the selected tab if it is one, otherwise the previous choice if still open. */
    fun keptBridgedApp(previous: String?, selectedTabId: String?, bridgedTabIds: Set<String>, openTabIds: Set<String>): String? = when {
        selectedTabId != null && selectedTabId in bridgedTabIds -> selectedTabId
        previous != null && previous in openTabIds && previous in bridgedTabIds -> previous
        else -> null
    }
}

/** Memory signals that release the kept bridged app. Moving to the background alone does not. */
internal object BridgedAppMemoryPolicy {
    @Suppress("DEPRECATION") // Running-level signals still arrive on older Android versions.
    fun shouldRelease(level: Int): Boolean = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
}
