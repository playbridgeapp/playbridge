package com.playbridge.sender.browser
import com.playbridge.sender.cast.*

import android.util.JsonWriter
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.engine.gecko.GeckoEngineSessionState
import mozilla.components.browser.state.action.CrashAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.EngineState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSessionState
import mozilla.components.lib.state.ext.flow
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.io.StringWriter
import java.util.UUID

/** Data class representing the navigation capabilities of a tab. */
data class TabNavigationState(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

/**
 * Manages browser tab UI state on top of Mozilla Components' EngineMiddleware.
 *
 * Engine session ownership lives in [BrowserStore] (via `EngineMiddleware`,
 * see [Components.store]): the middleware creates sessions on
 * [EngineAction.CreateEngineSessionAction] (restoring `engineSessionState`
 * automatically, or loading the tab URL when there is no state), suspends them
 * on [EngineAction.SuspendEngineSessionAction] (state preserved in the store),
 * closes them when tabs are removed, and marks tabs crashed on content-process
 * death.
 *
 * TabManager's responsibilities are now limited to:
 * - mirroring `tab.engineState.engineSession` into [sessions] and
 *   back/forward state into [navigationStates] for Compose consumers
 *   (via [start]),
 * - ensuring the selected tab always has a live engine session (recreating it
 *   after hibernation or a content-process crash/kill),
 * - the session LRU ("max alive tabs") which hibernates background tabs,
 * - tab list operations (create/close/select/reopen/duplicate) and the
 *   closed-tabs / selection stacks,
 * - serializing engine session state for Room persistence via public AC APIs.
 */
class TabManager {

    companion object {
        private const val TAG = "TabManager"
        private const val GECKO_STATE_KEY = "GECKO_STATE"

        /** Crash-loop guard: max automatic restores per tab within [CRASH_WINDOW_MS]. */
        private const val MAX_CRASH_RESTORES = 3
        private const val CRASH_WINDOW_MS = 30_000L

        /**
         * Serialize an [EngineSessionState] using the public
         * `EngineSessionState.writeTo(JsonWriter)` API.
         * Produces `{"GECKO_STATE": "<gecko session JSON>"}`.
         */
        fun engineStateToBytes(state: EngineSessionState?): ByteArray? {
            if (state == null) return null
            return try {
                val stringWriter = StringWriter()
                JsonWriter(stringWriter).use { state.writeTo(it) }
                val json = stringWriter.toString()
                if (json.isEmpty() || json == "{}") null else json.toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize EngineSessionState", e)
                null
            }
        }

        /**
         * Deserialize an [EngineSessionState] from persisted bytes.
         * Supports both the AC format (`{"GECKO_STATE": ...}`) and the legacy
         * PlayBridge format (raw `GeckoSession.SessionState` JSON) so existing
         * user databases keep restoring after the EngineMiddleware migration.
         */
        fun bytesToEngineState(bytes: ByteArray?): EngineSessionState? {
            if (bytes == null) return null
            return try {
                val json = String(bytes, Charsets.UTF_8)
                val obj = JSONObject(json)
                if (obj.has(GECKO_STATE_KEY)) {
                    GeckoEngineSessionState.fromJSON(obj)
                } else {
                    // Legacy rows: raw gecko state JSON — wrap it in the AC envelope.
                    GeckoEngineSessionState.fromJSON(JSONObject().put(GECKO_STATE_KEY, json))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize EngineSessionState", e)
                null
            }
        }
    }

    /**
     * Mirror of `tab.engineState.engineSession` for all tabs, kept in sync by
     * [start]. Observable by Compose. Do NOT insert sessions here directly —
     * link them in the store with [EngineAction.LinkEngineSessionAction].
     */
    val sessions = mutableStateMapOf<String, EngineSession>()

    /** The maximum number of live EngineSessions; others are suspended (hibernated). */
    var maxAliveSessions = 5

    /** LRU tracker for recently active tab IDs (engine-session lifetime). */
    private val recentlyActiveTabIds = linkedSetOf<String>()

    private data class ClosedTab(
        val url: String,
        val parentId: String?,
        val engineSessionState: EngineSessionState?,
        val title: String
    )

    private val closedTabsStack = ArrayDeque<ClosedTab>()

    /**
     * Stack of recently *selected* tab IDs. Used to pick the next tab when the
     * current one is closed. Most-recent at the end.
     */
    private val selectionStack = ArrayDeque<String>()

    /** Set of tab IDs currently playing media (audio/video). Observable by Compose. */
    val playingTabIds = mutableStateMapOf<String, Boolean>()

    /** Navigation capabilities (back/forward) per tab, mirrored from store content state. */
    val navigationStates = mutableStateMapOf<String, TabNavigationState>()

    /** Map of tab-id -> parent-id. Decouples parent-child relationships from the store state. */
    val parentIds = mutableMapOf<String, String>()

    /**
     * Optional callback invoked whenever a tab's engine session state changes
     * in the store. BrowserActivity wires this to a debounced DB persistence
     * trigger so navigation state isn't lost if the process is killed.
     */
    var onAnyStateUpdated: ((tabId: String) -> Unit)? = null

    /** Serializes [syncSessions]. */
    private val syncMutex = Mutex()

    /** Tracks last-seen engineSessionState per tab to detect changes for [onAnyStateUpdated]. */
    private val lastSessionStates = HashMap<String, EngineSessionState?>()

    /** Timestamps of recent automatic crash-restores per tab (crash-loop guard). */
    private val crashRestoreTimes = HashMap<String, ArrayDeque<Long>>()

    /**
     * Tabs where auto-recovery gave up because the page crashed the content
     * process repeatedly. Re-armed when the user selects a different tab
     * (returning to the tab is a deliberate retry).
     */
    private val crashGivenUp = mutableSetOf<String>()

    private var mirrorScope: CoroutineScope? = null

    // ── Store mirroring ──────────────────────────────────────────────

    /**
     * Start mirroring [store] state. Idempotent; called once from
     * [Components.initialize]. Keeps [sessions]/[navigationStates] in sync,
     * fires [onAnyStateUpdated] on engine-state changes, and makes sure the
     * selected tab always has a live engine session — including automatic
     * recovery after content-process crashes/kills (the store marks the tab
     * crashed / suspends it; we restore + recreate, which EngineMiddleware
     * does from the last saved state).
     */
    fun start(store: BrowserStore) {
        if (mirrorScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        mirrorScope = scope
        scope.launch {
            store.flow().collect { state ->
                val liveTabIds = mutableSetOf<String>()
                state.tabs.forEach { tab ->
                    liveTabIds.add(tab.id)

                    // 1. Mirror engine sessions for Compose consumers. When a NEW
                    // session appears for a tab, register the media bridge on it —
                    // covers every tab, incl. background playback. (The session
                    // unregisters all observers when closed.)
                    val engineSession = tab.engineState.engineSession
                    if (engineSession != null) {
                        if (sessions[tab.id] !== engineSession) {
                            sessions[tab.id] = engineSession
                            engineSession.register(
                                MediaSessionObserver(
                                    context = Components.applicationContext,
                                    tabId = tab.id,
                                    tabManager = this@TabManager,
                                    engineSession = engineSession,
                                )
                            )
                        }
                    } else {
                        sessions.remove(tab.id)
                    }

                    // 2. Mirror back/forward state (EngineObserver keeps content state updated).
                    val nav = TabNavigationState(tab.content.canGoBack, tab.content.canGoForward)
                    if (navigationStates[tab.id] != nav) navigationStates[tab.id] = nav

                    // 3. Detect engine-session-state updates for debounced persistence.
                    val sessionState = tab.engineState.engineSessionState
                    if (lastSessionStates[tab.id] !== sessionState) {
                        lastSessionStates[tab.id] = sessionState
                        if (sessionState != null) onAnyStateUpdated?.invoke(tab.id)
                    }
                }
                // Drop mirror entries for tabs that no longer exist.
                sessions.keys.toList().forEach { if (it !in liveTabIds) sessions.remove(it) }
                lastSessionStates.keys.retainAll(liveTabIds)

                // Re-arm crash recovery for tabs the user has switched away from.
                val selectedId = state.selectedTabId
                if (selectedId != null) crashGivenUp.retainAll(setOf(selectedId)) else crashGivenUp.clear()

                // 4. Ensure the selected tab has a live engine session.
                val selected = selectedId?.let { id -> state.tabs.find { it.id == id } }
                if (selected != null) {
                    when {
                        selected.engineState.crashed -> {
                            // Clear the crashed flag; the next emission recreates the
                            // session from the pre-crash state (CrashMiddleware suspended it).
                            // Guarded against crash storms (page that kills the content
                            // process on every load would otherwise loop forever).
                            if (shouldAutoRestoreCrashed(selected.id)) {
                                store.dispatch(CrashAction.RestoreCrashedSessionAction(selected.id))
                            }
                        }
                        selected.engineState.engineSession == null &&
                            !selected.engineState.initializing -> {
                            // Hibernated / restored-from-DB / killed-in-background tab:
                            // EngineMiddleware creates the session, restores its state,
                            // or loads the tab URL if there is no state.
                            store.dispatch(EngineAction.CreateEngineSessionAction(selected.id))
                        }
                    }
                }
            }
        }
    }

    /**
     * Crash-loop guard: allow up to [MAX_CRASH_RESTORES] automatic restores
     * per tab within [CRASH_WINDOW_MS]; beyond that, give up until the user
     * selects another tab and comes back.
     */
    private fun shouldAutoRestoreCrashed(tabId: String): Boolean {
        if (tabId in crashGivenUp) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val times = crashRestoreTimes.getOrPut(tabId) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > CRASH_WINDOW_MS) times.removeFirst()
        return if (times.size >= MAX_CRASH_RESTORES) {
            crashGivenUp.add(tabId)
            Log.e(
                TAG,
                "Tab $tabId crashed $MAX_CRASH_RESTORES times in ${CRASH_WINDOW_MS / 1000}s — " +
                    "pausing auto-recovery until the tab is reselected"
            )
            false
        } else {
            times.addLast(now)
            Log.w(TAG, "Selected tab $tabId crashed — restoring (attempt ${times.size}/$MAX_CRASH_RESTORES)")
            true
        }
    }

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
     * If [engineSessionState] is provided (reopen/duplicate), the engine
     * session is restored from it instead of loading [url] from scratch.
     *
     * If [parentId] is provided and matches an existing tab, the new tab is
     * inserted directly after the parent (Chrome-style "next to current")
     * instead of appended to the end of the list.
     */
    fun createTab(
        url: String,
        store: BrowserStore,
        parentId: String? = null,
        select: Boolean = true,
        engineSessionState: EngineSessionState? = null
    ): String {
        val tabId = UUID.randomUUID().toString()
        store.dispatch(
            TabListAction.AddTabAction(
                tab = TabSessionState(
                    id = tabId,
                    content = ContentState(url = url),
                    parentId = parentId,
                    engineState = EngineState(engineSessionState = engineSessionState)
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
     * behaviour if no prior selection is still alive. The engine session is
     * closed by EngineMiddleware (TabsRemovedMiddleware) on removal.
     */
    fun closeTab(tabId: String, store: BrowserStore) {
        val wasSelected = store.state.selectedTabId == tabId

        // Save to closed tabs stack before removal (state comes from the store).
        val sourceTab = store.state.tabs.find { it.id == tabId }
        if (sourceTab != null) {
            closedTabsStack.addLast(
                ClosedTab(
                    url = sourceTab.content.url,
                    parentId = parentIds[tabId] ?: sourceTab.parentId,
                    engineSessionState = sourceTab.engineState.engineSessionState,
                    title = sourceTab.content.title
                )
            )
            while (closedTabsStack.size > 10) {
                closedTabsStack.removeFirst()
            }
        }

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

    /**
     * Reopen the last closed tab, fully restoring its session state, history, and URL.
     */
    fun reopenClosedTab(store: BrowserStore): String? {
        if (closedTabsStack.isEmpty()) return null
        val closedTab = closedTabsStack.removeLast()
        return createTab(
            url = closedTab.url,
            store = store,
            parentId = closedTab.parentId,
            select = true,
            engineSessionState = closedTab.engineSessionState
        )
    }

    /**
     * Returns true if there are recently closed tabs that can be reopened.
     */
    fun canReopenClosedTab(): Boolean {
        return closedTabsStack.isNotEmpty()
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

    /**
     * Restore a list of tabs to the store. Tabs may carry
     * `engineState.engineSessionState` (set by BrowserViewModel from the DB);
     * EngineMiddleware restores from it when each tab needs a session.
     */
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

    /**
     * Suspend every live engine session, preserving state in the store.
     * Called when the Activity is actually finishing (NOT on configuration
     * change). A relaunch within the same process restores tabs from the
     * store; a new process restores them from the DB.
     */
    fun closeAllSessions() {
        Components.store.state.tabs.forEach { tab ->
            if (tab.engineState.engineSession != null) {
                Components.store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
            }
        }
        recentlyActiveTabIds.clear()
    }

    /** Drop all per-tab UI state for [tabId]. Called when a tab is closed. */
    fun purgeTabState(tabId: String) {
        navigationStates.remove(tabId)
        playingTabIds.remove(tabId)
        recentlyActiveTabIds.remove(tabId)
        selectionStack.remove(tabId)
        parentIds.remove(tabId)
        lastSessionStates.remove(tabId)
        crashRestoreTimes.remove(tabId)
        crashGivenUp.remove(tabId)
        sessions.remove(tabId)
        VideoDetector.clearTab(tabId)
        // NOTE: the engine session itself is closed by EngineMiddleware
        // (TabsRemovedMiddleware) when the tab is removed from the store.
    }

    /**
     * Sync per-tab UI state with the current set of live tabs in BrowserStore.
     * Drops state for any tab id no longer present.
     */
    fun reconcileWithStoreTabs(liveTabIds: Set<String>) {
        val stale = (navigationStates.keys + playingTabIds.keys + sessions.keys)
            .toSet() - liveTabIds
        stale.forEach { purgeTabState(it) }
        selectionStack.retainAll(liveTabIds)
        recentlyActiveTabIds.retainAll(liveTabIds)
    }

    // ── Session LRU (hibernation) ────────────────────────────────────

    /**
     * Maintain the LRU of live engine sessions and hibernate tabs that fall
     * outside the window by dispatching [EngineAction.SuspendEngineSessionAction]
     * — EngineMiddleware preserves their state in the store and they are
     * recreated automatically (with history) on next selection by [start].
     *
     * Session *creation* is no longer done here; the store mirror in [start]
     * dispatches [EngineAction.CreateEngineSessionAction] for the selected tab.
     */
    suspend fun syncSessions(tabs: List<TabSessionState>, selectedTabId: String? = null) = syncMutex.withLock {
        if (tabs.isEmpty() && selectedTabId == null) {
            return@withLock
        }

        val allValidTabIds = tabs.map { it.id }.toSet()

        // 1. Maintain LRU tracker.
        if (selectedTabId != null && allValidTabIds.contains(selectedTabId)) {
            // Re-adding at the end of a LinkedHashSet moves it to the most-recently-used position
            recentlyActiveTabIds.remove(selectedTabId)
            recentlyActiveTabIds.add(selectedTabId)
            recordSelection(selectedTabId)
        }
        recentlyActiveTabIds.retainAll(allValidTabIds)

        // 2. Cull the LRU: evict the oldest non-playing, non-selected tabs.
        while (recentlyActiveTabIds.size > maxAliveSessions) {
            val oldestNonPlaying = recentlyActiveTabIds.find { id ->
                id != selectedTabId && (playingTabIds[id] != true)
            }
            if (oldestNonPlaying != null) {
                recentlyActiveTabIds.remove(oldestNonPlaying)
            } else {
                // All remaining tabs are selected or playing media — stop culling.
                break
            }
        }
        val activeTabIds = recentlyActiveTabIds.toSet()

        // 3. Hibernate live sessions outside the active window (read fresh store state).
        Components.store.state.tabs.forEach { tab ->
            if (tab.id !in activeTabIds &&
                tab.engineState.engineSession != null &&
                tab.id != selectedTabId &&
                playingTabIds[tab.id] != true
            ) {
                Log.d(TAG, "Hibernating tab ${tab.id} (suspend via EngineMiddleware)")
                Components.store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
                VideoDetector.clearTab(tab.id)
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
     * Trigger a find-in-page operation using the public EngineSession API.
     * @param text  the query text; empty repeats the last search
     * @param direction  0 = forward, 1 = backwards
     */
    fun findInPage(session: EngineSession?, text: String, direction: Int = 0) {
        if (session == null) return
        try {
            when {
                text.isNotEmpty() -> session.findAll(text)
                else -> session.findNext(forward = direction == 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding in page", e)
        }
    }

    /** Clear find-in-page highlights. */
    fun clearFind(session: EngineSession?) {
        try {
            session?.clearFindMatches()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing find matches", e)
        }
    }

    // ── State capture (persistence) ──────────────────────────────────

    /**
     * Serialize the engine session state of every tab from the store.
     * Live tabs' states are continuously updated by EngineObserver
     * (`onStateUpdated` → `UpdateEngineSessionStateAction`); suspended tabs
     * retain their last state.
     */
    fun captureAllStates(): Map<String, ByteArray> {
        val allStates = mutableMapOf<String, ByteArray>()
        Components.store.state.tabs.forEach { tab ->
            engineStateToBytes(tab.engineState.engineSessionState)?.let {
                allStates[tab.id] = it
            }
        }
        Log.d(TAG, "captureAllStates: returning ${allStates.size} states")
        return allStates
    }

    /**
     * Duplicate a tab: create a new tab next to it restoring the source tab's
     * current engine session state (history included).
     */
    fun duplicateTab(sourceTabId: String, store: BrowserStore): String? {
        val sourceTab = store.state.tabs.find { it.id == sourceTabId } ?: return null
        return createTab(
            url = sourceTab.content.url,
            store = store,
            parentId = sourceTabId,
            select = false,
            engineSessionState = sourceTab.engineState.engineSessionState
        )
    }
}
