package com.playbridge.sender.browser

internal data class RemoteReturnTarget(val screen: Screen, val bridgedAppTabId: String? = null)

/** Restore the app that opened Remote only while its tab still belongs to that app. */
internal fun resolveRemoteReturnTarget(
    origin: Screen,
    bridgedAppTabId: String?,
    apps: List<BridgedApp>,
    tabUrls: Map<String, String>,
): RemoteReturnTarget {
    val screen = origin.takeUnless { it == Screen.Remote } ?: Screen.Browser
    if (screen != Screen.Browser || bridgedAppTabId == null) return RemoteReturnTarget(screen)

    val app = apps.find { it.tabId == bridgedAppTabId }
    val tabOrigin = tabUrls[bridgedAppTabId]?.let(BridgedAppStore::originFor)
    return if (app != null && tabOrigin == app.origin) {
        RemoteReturnTarget(Screen.Browser, bridgedAppTabId)
    } else {
        RemoteReturnTarget(Screen.Dashboard)
    }
}
