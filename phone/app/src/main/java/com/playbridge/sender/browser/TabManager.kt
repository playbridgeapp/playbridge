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

    /** LRU tracker for recently active tab IDs. */
    private val recentlyActiveTabIds = linkedSetOf<String>()

    /** Set of tab IDs currently playing media (audio/video). Observable by Compose. */
    val playingTabIds = mutableStateMapOf<String, Boolean>()

    /** Serialized GeckoSession state for hibernated tabs (or tabs loaded from DB). */
    val savedStates = mutableMapOf<String, ByteArray>()

    /** Latest EngineSessionState received from observers for each tab. */
    internal val engineStates = mutableMapOf<String, mozilla.components.concept.engine.EngineSessionState>()

    // ── Tab operations ───────────────────────────────────────────────

    /** Ensure [store] always has at least one tab. */
    fun ensureAtLeastOneTab(store: BrowserStore) {
        if (store.state.tabs.isEmpty()) {
            createTab("about:blank", store)
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

    /** Restore a list of tabs to the store. */
    fun restoreTabs(tabs: List<TabSessionState>, selectedId: String?, store: BrowserStore) {
        tabs.forEach { tab ->
            store.dispatch(
                TabListAction.AddTabAction(
                    tab = tab,
                    select = (tab.id == selectedId)
                )
            )
        }
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
                            val engineState = wrapGeckoState(geckoState)
                            if (engineState != null) {
                                newSession.restoreState(engineState)
                            }
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
            }
        }
        Log.d(TAG, "syncSessions: created $createdCount new sessions, total sessions=${sessions.size}")

        // 4. Cleanup sessions for closed or hibernated tabs
        val hibernatedIds = sessions.keys.filter { it !in activeTabIds }

        hibernatedIds.forEach { id ->
            val session = sessions[id]
            if (session != null) {
                try {
                    session.stopLoading()
                    // Close the internal GeckoSession via reflection so media stops immediately and memory is freed
                    val geckoEngineSession = session as? GeckoEngineSession
                    if (geckoEngineSession != null) {
                        // Capture latest state from engineStates map (populated via onStateUpdated in SessionObserverSetup)
                        val engineState = engineStates[id]
                        val geckoState = getGeckoState(engineState)
                        val bytes = geckoStateToBytes(geckoState)
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
            // Clean up detected videos for this tab
            VideoDetector.clearTab(id)
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
        sessions.forEach { (id, _) ->
            val engineState = engineStates[id]
            val geckoState = getGeckoState(engineState)
            val bytes = geckoStateToBytes(geckoState)
            if (bytes != null) {
                allStates[id] = bytes
            }
        }
        return allStates
    }

    // ── Serialization helpers ────────────────────────────────────────

    private fun getGeckoState(engineState: mozilla.components.concept.engine.EngineSessionState?): GeckoSession.SessionState? {
        if (engineState == null) return null
        return try {
            // GeckoEngineSessionState wraps GeckoSession.SessionState. We use reflection to get the actual state
            // since the getter is internal to the browser-engine-gecko module.
            val method = engineState.javaClass.getDeclaredMethod("getActualState\$browser_engine_gecko_release")
            method.isAccessible = true
            method.invoke(engineState) as? GeckoSession.SessionState
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
        val parcel = android.os.Parcel.obtain()
        return try {
            state.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    private fun bytesToGeckoState(bytes: ByteArray?): GeckoSession.SessionState? {
        if (bytes == null) return null
        val parcel = android.os.Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            GeckoSession.SessionState.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
