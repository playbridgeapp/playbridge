package com.playbridge.sender.browser

import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState

/** Home edits affect tabs that have never loaded, not live or hibernated pages. */
internal fun TabSessionState.atEditedBridgedAppHome(apps: List<BridgedApp>): TabSessionState {
    if (engineState.engineSession != null || engineState.engineSessionState != null) return this
    return atBridgedAppHome(apps)
}

/** Fresh app sessions must not restore a deep link, page history or old engine state. */
internal fun TabSessionState.atBridgedAppHome(apps: List<BridgedApp>): TabSessionState {
    val app = apps.find { it.tabId == id } ?: return this
    return TabSessionState(id = id, content = ContentState(url = app.startUrl, title = app.name))
}
