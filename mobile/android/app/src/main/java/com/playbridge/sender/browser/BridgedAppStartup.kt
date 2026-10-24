package com.playbridge.sender.browser

import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.EngineSession
import java.net.URI

internal enum class BridgedAppResetAction {
    NONE,
    GO_TO_FIRST_HISTORY_ITEM,
    LOAD_START_URL,
}

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

internal fun TabSessionState.bridgedAppResetAction(startUrl: String): BridgedAppResetAction =
    bridgedAppResetAction(
        startUrl = startUrl,
        canGoBack = content.canGoBack || content.history.currentIndex > 0,
        firstHistoryUrl = content.history.items.firstOrNull()?.uri,
    )

internal fun bridgedAppResetAction(
    startUrl: String,
    canGoBack: Boolean,
    firstHistoryUrl: String?,
): BridgedAppResetAction {
    if (!canGoBack) return BridgedAppResetAction.NONE
    val startOrigin = BridgedAppStore.originFor(startUrl) ?: return BridgedAppResetAction.LOAD_START_URL
    if (firstHistoryUrl == null ||
        BridgedAppStore.originFor(firstHistoryUrl) != startOrigin ||
        normalizedBridgedAppPath(firstHistoryUrl) != normalizedBridgedAppPath(startUrl)
    ) {
        return BridgedAppResetAction.LOAD_START_URL
    }
    return BridgedAppResetAction.GO_TO_FIRST_HISTORY_ITEM
}

internal fun TabSessionState.resetBridgedAppToStart(
    startUrl: String,
    session: EngineSession? = engineState.engineSession,
    onReopened: (EngineSession) -> Unit = {},
): BridgedAppResetAction {
    val action = bridgedAppResetAction(startUrl)
    when (action) {
        BridgedAppResetAction.NONE -> Unit
        BridgedAppResetAction.GO_TO_FIRST_HISTORY_ITEM -> session?.goToHistoryIndex(0)
        BridgedAppResetAction.LOAD_START_URL -> session?.loadUrl(startUrl)
    }
    session?.let(onReopened)
    return action
}

private fun normalizedBridgedAppPath(url: String): String? {
    val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
    return path.ifEmpty { "/" }
}

