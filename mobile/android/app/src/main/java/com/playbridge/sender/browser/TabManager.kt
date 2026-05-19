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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** Data class representing the navigation capabilities of a tab. */
data class TabNavigationState(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

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


    /** The maximum number of EngineSessions to keep alive to prevent OOM errors. */
    var maxAliveSessions = 5

    /** LRU tracker for recently active tab IDs (engine-session lifetime). */
    private val recentlyActiveTabIds = linkedSetOf<String>()

    /**
     * Stack of recently *selected* tab IDs. Used to pick the next tab when the
     * current one is closed. Most-recent at the end.
     */
    private val selectionStack = ArrayDeque<String>()

    /** Set of tab IDs currently playing media (audio/video). Observable by Compose. */
    val playingTabIds = mutableStateMapOf<String, Boolean>()

    /** Serialized GeckoSession state for hibernated tabs (or tabs loaded from DB). */
    val savedStates = mutableMapOf<String, ByteArray>()

    /** Latest EngineSessionState received from observers for each tab. */
    internal val engineStates = mutableMapOf<String, mozilla.components.concept.engine.EngineSessionState>()

    /** Navigation capabilities (back/forward) for each tab ID. Observable by Compose. */
    val navigationStates = mutableStateMapOf<String, TabNavigationState>()

    /**
     * Tabs that were just restored from a saved state. While this flag is set,
     * spurious `onNavigationStateChange(false, false)` events from GeckoView's
     * initial repaint are ignored so they don't clobber the pre-calculated
     * back/forward state derived from `currentIndex`.
     */
    private val justRestored = mutableSetOf<String>()

    /** Tabs that need a reload triggered upon selection (because they were restored in the background). */
    private val needsReloadOnSelect = mutableSetOf<String>()

    /** Map of tab-id -> parent-id. Decouples parent-child relationships from the store state to ensure stable restoration order. */
    val parentIds = mutableMapOf<String, String>()

    /**
     * Optional callback invoked whenever any tab's `onStateUpdated` fires.
     * BrowserActivity wires this to a debounced DB persistence trigger so
     * navigation state isn't lost when the process is killed before
     * `onPause` runs.
     */
    var onAnyStateUpdated: ((tabId: String) -> Unit)? = null

    // ── Tab operations ───────────────────────────────────────────────

    /** Ensure [store] always has at least one tab. */
    fun ensureAtLeastOneTab(store: BrowserStore) {
        if (store.state.tabs.isEmpty()) {
            createTab("about:blank", store)
        }
    }

    /**
     * Create a new tab with [url], optionally set [parentId], and select it.
     *
     * If [parentId] is provided and matches an existing tab, the new tab is
     * inserted directly after the parent (Chrome-style "next to current")
     * instead of appended to the end of the list.
     */
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
        if (parentId != null) {
            parentIds[tabId] = parentId
            val parentIndex = store.state.tabs.indexOfFirst { it.id == parentId }
            val newIndex = store.state.tabs.indexOfFirst { it.id == tabId }
            // AddTabAction always appends. If the parent is not last, move the new tab next to it.
            if (parentIndex >= 0 && newIndex >= 0 && newIndex != parentIndex + 1) {
                store.dispatch(TabListAction.MoveTabsAction(listOf(tabId), parentId, placeAfter = true))
            }
        }
        return tabId
    }

    /**
     * Remove a tab by id. Before removing, choose the next tab to select by
     * popping the selection stack — falling back to BrowserStore's default
     * behaviour if no prior selection is still alive.
     */
    fun closeTab(tabId: String, store: BrowserStore) {
        val wasSelected = store.state.selectedTabId == tabId
        if (wasSelected) {
            // Drain stale entries off the top of the stack until we find a live tab
            // that isn't the one being closed.
            val liveTabIds = store.state.tabs.map { it.id }.toSet()
            var next: String? = null
            while (selectionStack.isNotEmpty()) {
                val candidate = selectionStack.removeLast()
                if (candidate != tabId && candidate in liveTabIds) {
                    next = candidate
                    break
                }
            }
            if (next != null) {
                store.dispatch(TabListAction.SelectTabAction(next))
            }
        }
        // Clean up per-tab maps eagerly so they don't outlive the tab.
        purgeTabState(tabId)
        store.dispatch(TabListAction.RemoveTabAction(tabId))
    }

    /** Select a tab by id. Maintains the selection stack. */
    fun selectTab(tabId: String, store: BrowserStore) {
        store.dispatch(TabListAction.SelectTabAction(tabId))
        recordSelection(tabId)
    }

    /** Record a tab selection in the selection stack (most recent at end). */
    fun recordSelection(tabId: String) {
        selectionStack.remove(tabId)
        selectionStack.addLast(tabId)
    }

    /** Restore a list of tabs to the store. */
    fun restoreTabs(tabs: List<TabSessionState>, selectedId: String?, store: BrowserStore) {
        tabs.forEach { tab ->
            tab.parentId?.let { pId ->
                parentIds[tab.id] = pId
            }
            store.dispatch(
                TabListAction.AddTabAction(
                    tab = tab.copy(parentId = null),
                    select = (tab.id == selectedId)
                )
            )
        }
    }

    /** Marks a tab as actively playing media, and clears all other tabs from the playing state. */
    fun markTabAsPlaying(tabId: String) {
        val keysToClear = playingTabIds.keys.filter { it != tabId }
        keysToClear.forEach { playingTabIds.remove(it) }
        playingTabIds[tabId] = true
    }

    /** Drop all per-tab state for [tabId]. Called when a tab is closed. */
    fun purgeTabState(tabId: String) {
        savedStates.remove(tabId)
        engineStates.remove(tabId)
        navigationStates.remove(tabId)
        playingTabIds.remove(tabId)
        recentlyActiveTabIds.remove(tabId)
        selectionStack.remove(tabId)
        justRestored.remove(tabId)
        needsReloadOnSelect.remove(tabId)
        parentIds.remove(tabId)
        VideoDetector.clearTab(tabId)
        val session = sessions.remove(tabId)
        if (session != null) {
            try {
                session.stopLoading()
                val gs = getGeckoSession(session)
                gs?.close()
            } catch (e: Exception) {
                Log.e(TAG, "purgeTabState: error closing session for $tabId", e)
            }
        }
    }

    /**
     * Sync per-tab state with the current set of live tabs in BrowserStore.
     * Drops state for any tab id no longer present. Called from a store
     * observer in BrowserActivity so externally-dispatched RemoveTabActions
     * (e.g. Mozilla components, tests) don't leak.
     */
    fun reconcileWithStoreTabs(liveTabIds: Set<String>) {
        val stale = (savedStates.keys + engineStates.keys + navigationStates.keys +
                playingTabIds.keys + sessions.keys)
            .toSet() - liveTabIds
        stale.forEach { purgeTabState(it) }
        selectionStack.retainAll(liveTabIds)
        recentlyActiveTabIds.retainAll(liveTabIds)
    }

    // ── Session synchronisation ──────────────────────────────────────

    /**
     * Create engine sessions for tabs that don't have one yet and
     * clean up sessions whose tabs have been closed or hiberated.
     */
    suspend fun syncSessions(tabs: List<TabSessionState>, selectedTabId: String? = null) {
        if (tabs.isEmpty() && selectedTabId == null) {
            Log.d(TAG, "syncSessions: called with empty tabs + null selectedTabId — skipping to avoid clearing LRU")
            return
        }

        val allValidTabIds = tabs.map { it.id }.toSet()

        Log.d(TAG, "syncSessions START — tabCount=${tabs.size}, selectedTabId=$selectedTabId, recentlyActive=${recentlyActiveTabIds.size}, existingSessions=${sessions.size}")

        // 1. Maintain LRU tracker — done before any suspension point so it is never lost
        // if this coroutine is cancelled (e.g. rapid store updates cancel the previous call).
        if (selectedTabId != null && allValidTabIds.contains(selectedTabId)) {
            // Re-adding at the end of a LinkedHashSet moves it to the most-recently-used position
            recentlyActiveTabIds.remove(selectedTabId)
            recentlyActiveTabIds.add(selectedTabId)
            recordSelection(selectedTabId)
            Log.d(TAG, "syncSessions: added selectedTabId to LRU — recentlyActive=${recentlyActiveTabIds.size}")
        } else {
            Log.d(TAG, "syncSessions: selectedTabId=$selectedTabId not added to LRU (null or not in tabs)")
        }

        // Yield to allow UI to draw first frame
        delay(10)

        // 1b. Background media pausing removed per user request to allow background playback
        // across tab switches.

        // 2. Cull the LRU tracker
        // Remove any tabs that were closed by the user
        recentlyActiveTabIds.retainAll(allValidTabIds)

        // If we exceed our live limit, remove the oldest (least recently used) tabs that are NOT playing media.
        // This ensures playing tabs are always kept alive if possible.
        while (recentlyActiveTabIds.size > maxAliveSessions) {
            val oldestNonPlaying = recentlyActiveTabIds.find { id ->
                id != selectedTabId && (playingTabIds[id] != true)
            }
            if (oldestNonPlaying != null) {
                recentlyActiveTabIds.remove(oldestNonPlaying)
            } else {
                // If all tabs in the LRU are either the selected tab or playing media,
                // we stop culling to satisfy the "always alive" requirement for media.
                break
            }
        }

        // The intersection of our tracking limit and the currently available tabs
        val activeTabIds = recentlyActiveTabIds.toSet()
        Log.d(TAG, "syncSessions: activeTabIds=${activeTabIds.size} (selectedTabId in active=${activeTabIds.contains(selectedTabId)})")

        // 3. Create sessions for tabs that are active but have no EngineSession yet
        var createdCount = 0
        tabs.forEach { tab ->
            if (activeTabIds.contains(tab.id) && !sessions.containsKey(tab.id)) {
                // Give a short breather to the main thread between session creations
                delay(10)

                // Re-check after suspension to prevent double-creation if another coroutine created it during delay
                if (sessions.containsKey(tab.id)) {
                    return@forEach
                }

                val newSession = Components.engine.createSession()
                val url = tab.content.url.ifEmpty { "about:blank" }

                sessions[tab.id] = newSession
                createdCount++

                // Restore saved state if available (history, backstack, etc.)
                val savedBytes = savedStates[tab.id]

                if (savedBytes != null) {
                    Log.d(TAG, "Restoring GeckoSession state for tab ${tab.id} (bytes=${savedBytes.size})")
                    try {
                        val geckoState = bytesToGeckoState(savedBytes)
                        if (geckoState != null) {
                            // Pre-calculate navigation state from history backstack metadata
                            val canGoBack = geckoState.currentIndex > 0
                            val canGoForward = geckoState.currentIndex < geckoState.size - 1
                            navigationStates[tab.id] = TabNavigationState(canGoBack, canGoForward)
                            // Mark as "just restored" so the observer below ignores the
                            // initial (false,false) GeckoView fires before the history
                            // is wired back up internally.
                            justRestored.add(tab.id)
                            Log.d(TAG, "Restored initial nav state for tab ${tab.id}: back=$canGoBack, fwd=$canGoForward (size=${geckoState.size}, index=${geckoState.currentIndex})")

                            val engineState = wrapGeckoState(geckoState)
                            if (engineState != null) {
                                newSession.restoreState(engineState)
                                Log.d(TAG, "Successfully called restoreState for tab ${tab.id}")
                                // Some restores leave the view blank until the next paint;
                                // a reload on the selected tab fixes that without changing
                                // history.
                                if (tab.id == selectedTabId && geckoState.size > 0) {
                                    try {
                                        newSession.reload()
                                        Log.d(TAG, "Reloaded restored tab ${tab.id} immediately as it is selected")
                                    } catch (_: Exception) {}
                                } else if (geckoState.size > 0) {
                                    needsReloadOnSelect.add(tab.id)
                                    Log.d(TAG, "Added tab ${tab.id} to needsReloadOnSelect")
                                }
                            } else {
                                Log.e(TAG, "Failed to wrap GeckoState for tab ${tab.id}")
                                if (url != "about:blank") newSession.loadUrl(url)
                            }
                        } else {
                            Log.e(TAG, "Failed to deserialize GeckoState for tab ${tab.id}")
                            if (url != "about:blank") newSession.loadUrl(url)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore state for tab ${tab.id}", e)
                        if (url != "about:blank") newSession.loadUrl(url)
                    }
                } else {
                    Log.d(TAG, "Created/Restored EngineSession for tab ${tab.id} url=$url (no saved state)")
                    if (url != "about:blank") {
                        newSession.loadUrl(url)
                    }
                }

                // Attach a permanent observer to track navigation state in TabManager.
                // We do this AFTER restoreState. Even so, GeckoView often fires
                // onNavigationStateChange(false,false) once the page actually
                // starts loading after restore — gated by `justRestored`.
                newSession.register(object : EngineSession.Observer {
                    override fun onStateUpdated(state: mozilla.components.concept.engine.EngineSessionState) {
                        engineStates[tab.id] = state
                        onAnyStateUpdated?.invoke(tab.id)
                    }
                    override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
                        val current = navigationStates[tab.id] ?: TabNavigationState()
                        // If the tab was just restored and both values come back false,
                        // assume this is the spurious initial event and keep the
                        // pre-calculated state. Clear the flag the moment we see a
                        // "true" or a non-default state.
                        if (justRestored.contains(tab.id)) {
                            val bothFalse = (canGoBack == false || canGoBack == null) &&
                                    (canGoForward == false || canGoForward == null)
                            if (bothFalse && (current.canGoBack || current.canGoForward)) {
                                Log.d(TAG, "Ignoring spurious (false,false) nav event for just-restored tab ${tab.id}")
                                return
                            }
                            justRestored.remove(tab.id)
                        }
                        navigationStates[tab.id] = current.copy(
                            canGoBack = canGoBack ?: current.canGoBack,
                            canGoForward = canGoForward ?: current.canGoForward
                        )
                        Log.d(TAG, "Navigation state updated for tab ${tab.id}: canGoBack=${canGoBack ?: "null(preserved)"}, canGoForward=${canGoForward ?: "null(preserved)"} -> final=(${navigationStates[tab.id]?.canGoBack}, ${navigationStates[tab.id]?.canGoForward})")
                    }
                })
            }
        }
        Log.d(TAG, "syncSessions: created $createdCount new sessions, total sessions=${sessions.size}")

        // 4. Cleanup sessions for closed or hibernated tabs.
        // NOTE: we only iterate sessions that exist for *live* tabs but are not in
        // the active LRU window — i.e. real hibernation. Sessions whose tab was
        // actually removed are torn down by `purgeTabState` / `reconcileWithStoreTabs`.
        val hibernatedIds = sessions.keys.filter { it !in activeTabIds && it in allValidTabIds }

        hibernatedIds.forEach { id ->
            val session = sessions[id]
            if (session != null) {
                try {
                    session.stopLoading()
                    val geckoEngineSession = session as? GeckoEngineSession
                    if (geckoEngineSession != null) {
                        val bytes = captureSessionState(id)
                        if (bytes != null) {
                            savedStates[id] = bytes
                            Log.d(TAG, "Saved GeckoSession state for hibernated tab $id (bytes=${bytes.size})")
                        }
                        val internalSession = getGeckoSession(geckoEngineSession)
                        internalSession?.close()
                        Log.d(TAG, "Closed/Hibernated GeckoSession for tab $id")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing session for tab $id", e)
                }
            }
            sessions.remove(id)
            playingTabIds.remove(id)
            // navigationStates is intentionally retained so the UI can still
            // show back/forward affordances for hibernated tabs.

            VideoDetector.clearTab(id)
        }

        // If the selected tab needs reload, trigger it now that its session is active
        if (selectedTabId != null && needsReloadOnSelect.contains(selectedTabId)) {
            val session = sessions[selectedTabId]
            if (session != null) {
                needsReloadOnSelect.remove(selectedTabId)
                try {
                    session.reload()
                    Log.d(TAG, "Reloaded restored tab $selectedTabId upon selection to prevent blank screen")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reload restored tab $selectedTabId", e)
                }
            }
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

    // ── Media helpers ────────────────────────────────────────────────

    /**
     * Pause all `<video>` and `<audio>` elements in [session] by executing
     * a javascript: URI via the underlying GeckoSession.
     *
     * This is the GeckoView-public replacement for the removed
     * `EngineSession.evaluateJavascript` API.
     */
    fun pauseMedia(session: EngineSession) {
        val geckoSession = getGeckoSession(session) ?: return
        try {
            geckoSession.loadUri(
                "javascript:document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});void 0;"
            )
        } catch (e: Exception) {
            Log.e(TAG, "pauseMedia: failed to execute JS", e)
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

    /** Capture current state of all active sessions and merge with hibernated states. */
    fun captureAllStates(): Map<String, ByteArray> {
        val allStates = savedStates.toMutableMap()
        sessions.keys.forEach { id ->
            val bytes = captureSessionState(id)
            if (bytes != null) {
                allStates[id] = bytes
            }
        }
        Log.d(TAG, "captureAllStates: returning ${allStates.size} states")
        return allStates
    }

    /**
     * Capture the persisted state of [id] by serialising the most recent
     * `EngineSessionState` we received via `onStateUpdated`. This avoids
     * the race in `GeckoSession.flushSessionState()` (which is async) +
     * reading `mStateCache` immediately, which was returning stale or
     * empty data.
     */
    private fun captureSessionState(id: String): ByteArray? {
        return try {
            val engineState = engineStates[id]
            val geckoState = getGeckoState(engineState)
            val bytes = geckoStateToBytes(geckoState)
            if (bytes != null) {
                Log.d(TAG, "captured state for tab $id from onStateUpdated cache (bytes=${bytes.size})")
                return bytes
            }
            // Last resort: previously-saved bytes (e.g. hibernated tab whose
            // engineStates entry was already purged).
            savedStates[id]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture state for tab $id", e)
            savedStates[id]
        }
    }

    // ── Serialization helpers ────────────────────────────────────────

    private fun getGeckoState(engineState: mozilla.components.concept.engine.EngineSessionState?): GeckoSession.SessionState? {
        if (engineState == null) return null
        return try {
            // Find the getter method dynamically to avoid issues with Kotlin internal name mangling
            // module suffixes (e.g. $browser_engine_gecko_debug vs $browser_engine_gecko_release).
            val method = engineState.javaClass.declaredMethods.firstOrNull {
                it.returnType == GeckoSession.SessionState::class.java || it.name.startsWith("getActualState")
            }
            if (method != null) {
                method.isAccessible = true
                method.invoke(engineState) as? GeckoSession.SessionState
            } else {
                Log.e(TAG, "getGeckoState: could not find getActualState method via reflection")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract GeckoSession.SessionState from EngineSessionState", e)
            null
        }
    }

    private fun wrapGeckoState(geckoState: GeckoSession.SessionState): mozilla.components.concept.engine.EngineSessionState? {
        return try {
            // The GeckoEngineSessionState constructor is internal, so we use reflection to instantiate it.
            val clazz = Class.forName("mozilla.components.browser.engine.gecko.GeckoEngineSessionState")
            val constructor = clazz.getDeclaredConstructor(GeckoSession.SessionState::class.java)
            constructor.isAccessible = true
            constructor.newInstance(geckoState) as? mozilla.components.concept.engine.EngineSessionState
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wrap GeckoSession.SessionState in GeckoEngineSessionState", e)
            null
        }
    }

    private fun geckoStateToBytes(state: GeckoSession.SessionState?): ByteArray? {
        if (state == null) return null
        return try {
            // GeckoSession.SessionState.toString() returns the state as a JSON string
            val json = state.toString()
            if (json.isEmpty() || json == "{}") return null
            json.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize GeckoState to string", e)
            null
        }
    }

    private fun bytesToGeckoState(bytes: ByteArray?): GeckoSession.SessionState? {
        if (bytes == null) return null
        return try {
            val json = String(bytes, Charsets.UTF_8)
            GeckoSession.SessionState.fromString(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize GeckoState from string", e)
            null
        }
    }
}
