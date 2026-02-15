package com.playbridge.sender.browser

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import org.mozilla.geckoview.GeckoSession
import java.util.UUID

/**
 * Manages browser tab lifecycle and engine sessions.
 *
 * Centralises tab creation/removal, session-to-tab synchronisation,
 * GeckoSession reflection helpers, and find-in-page operations.
 */
class TabManager {

    companion object {
        private const val TAG = "TabManager"
    }

    /** Map of tab-id → live EngineSession. Observable by Compose. */
    val sessions = mutableStateMapOf<String, EngineSession>()

    // ── Tab operations ───────────────────────────────────────────────

    /** Ensure [store] always has at least one tab. */
    fun ensureAtLeastOneTab(store: BrowserStore) {
        if (store.state.tabs.isEmpty()) {
            createTab("https://www.google.com", store)
        }
    }

    /** Create a new tab with [url], optionally set [parentId], and select it. */
    fun createTab(
        url: String,
        store: BrowserStore,
        parentId: String? = null,
        select: Boolean = true
    ): String {
        val tabId = UUID.randomUUID().toString()
        store.dispatch(
            TabListAction.AddTabAction(
                tab = TabSessionState(
                    id = tabId,
                    content = ContentState(url = url),
                    parentId = parentId
                ),
                select = select
            )
        )
        return tabId
    }

    /** Remove a tab by id. */
    fun closeTab(tabId: String, store: BrowserStore) {
        store.dispatch(TabListAction.RemoveTabAction(tabId))
    }

    /** Select a tab by id. */
    fun selectTab(tabId: String, store: BrowserStore) {
        store.dispatch(TabListAction.SelectTabAction(tabId))
    }

    // ── Session synchronisation ──────────────────────────────────────

    /**
     * Create engine sessions for tabs that don't have one yet and
     * clean up sessions whose tabs have been closed.
     */
    fun syncSessions(tabs: List<TabSessionState>) {
        // Create sessions for new tabs
        tabs.forEach { tab ->
            if (!sessions.containsKey(tab.id)) {
                val newSession = Components.engine.createSession()
                val url = tab.content.url.ifEmpty { "https://www.google.com" }
                if (url != "about:blank") {
                    newSession.loadUrl(url)
                }
                sessions[tab.id] = newSession
            }
        }

        // Cleanup sessions for closed tabs
        val activeTabIds = tabs.map { it.id }.toSet()
        val removedIds = sessions.keys.filter { it !in activeTabIds }

        removedIds.forEach { id ->
            val session = sessions[id]
            if (session != null) {
                try {
                    session.stopLoading()
                    // Close the internal GeckoSession via reflection so media stops immediately
                    val geckoEngineSession = session as? GeckoEngineSession
                    if (geckoEngineSession != null) {
                        val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
                        field.isAccessible = true
                        val internalSession = field.get(geckoEngineSession) as? GeckoSession
                        internalSession?.close()
                        Log.d(TAG, "Closed GeckoSession for tab $id")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing session for tab $id", e)
                }
            }
            sessions.remove(id)
        }
    }

    // ── GeckoSession helpers ─────────────────────────────────────────

    /** Access the underlying [GeckoSession] from an [EngineSession] via reflection. */
    fun getGeckoSession(engineSession: EngineSession?): GeckoSession? {
        if (engineSession == null) return null
        val geckoEngineSession = engineSession as? GeckoEngineSession ?: return null
        return try {
            val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
            field.isAccessible = true
            field.get(geckoEngineSession) as? GeckoSession
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing GeckoSession", e)
            null
        }
    }

    // ── Find in page ─────────────────────────────────────────────────

    /**
     * Trigger a find-in-page operation.
     * @param text  the query text
     * @param direction  0 = forward / highlight, 1 = backwards
     */
    fun findInPage(session: EngineSession?, text: String, direction: Int = 0) {
        val geckoSession = getGeckoSession(session) ?: return
        try {
            val finderClass = Class.forName("org.mozilla.geckoview.GeckoSession\$Finder")
            val findDisplayHighlights = finderClass.getField("FIND_DISPLAY_HIGHLIGHTS").getInt(null)
            val findBackwards = finderClass.getField("FIND_BACKWARDS").getInt(null)

            val flags = if (direction == 0) findDisplayHighlights
            else findDisplayHighlights or findBackwards

            geckoSession.finder.find(text, flags)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding in page", e)
            try { geckoSession.finder.find(text, 0) } catch (_: Exception) {}
        }
    }

    /** Clear find-in-page highlights. */
    fun clearFind(session: EngineSession?) {
        getGeckoSession(session)?.finder?.clear()
    }
}
