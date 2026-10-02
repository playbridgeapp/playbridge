package com.playbridge.sender.browser

/** Keeps hidden app sessions from replacing the regular browser's saved selection. */
internal fun resolveBrowserTabSelection(
    tabIds: List<String>,
    selectedId: String?,
    bridgedAppTabIds: Set<String>,
    previousBrowserTabId: String?,
): String? {
    fun isBrowserTab(id: String?): Boolean =
        id != null && id in tabIds && id !in bridgedAppTabIds

    return when {
        isBrowserTab(selectedId) -> selectedId
        isBrowserTab(previousBrowserTabId) -> previousBrowserTabId
        else -> tabIds.firstOrNull { it !in bridgedAppTabIds }
    }
}
