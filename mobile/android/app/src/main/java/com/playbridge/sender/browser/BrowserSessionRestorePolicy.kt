package com.playbridge.sender.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState

/** Selection alone (startup, tab removal, Dashboard) must not load a page. */
internal class BrowserSessionRestorePolicy {
    private val visible = MutableStateFlow<String?>(null)
    val visibleTabId: StateFlow<String?> = visible.asStateFlow()

    private var hostOwner: Any? = null

    fun attach(owner: Any) {
        hostOwner = owner
        hide()
    }

    fun show(owner: Any, tabId: String?) {
        if (hostOwner === owner) visible.value = tabId
    }

    fun detach(owner: Any): Boolean {
        if (hostOwner !== owner) return false
        hostOwner = null
        hide()
        return true
    }

    fun hide() { visible.value = null }

    fun tabToRestore(state: BrowserState): TabSessionState? {
        val id = visible.value ?: return null
        if (state.selectedTabId != id) return null
        return state.tabs.find { it.id == id }
    }
}
