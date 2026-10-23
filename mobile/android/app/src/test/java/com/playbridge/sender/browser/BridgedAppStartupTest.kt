package com.playbridge.sender.browser

import android.util.JsonWriter
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.EngineState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.HistoryState
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSessionState
import mozilla.components.concept.engine.Settings
import mozilla.components.concept.engine.history.HistoryItem
import mozilla.components.concept.engine.translate.TranslationOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BridgedAppStartupTest {
    private val app = BridgedApp("https://streams.example", "Streams", "https://streams.example/home#movies", null, "app-tab")
    private val oldPageHistory = object : EngineSessionState {
        override fun writeTo(writer: JsonWriter) = Unit
    }

    private class RecordingEngineSession : EngineSession() {
        val historyIndices = mutableListOf<Int>()
        val loadedUrls = mutableListOf<String>()

        override fun goToHistoryIndex(index: Int) {
            historyIndices += index
        }

        override fun loadUrl(
            url: String,
            parent: EngineSession?,
            flags: LoadUrlFlags,
            additionalHeaders: Map<String, String>?,
            originalInput: String?,
            textDirectiveUserActivation: Boolean,
        ) {
            loadedUrls += url
        }

        override val settings: Settings get() = TODO()
        override fun loadData(data: String, mimeType: String, encoding: String) = TODO()
        override fun requestPdfToDownload() = TODO()
        override fun requestPrintContent() = TODO()
        override fun stopLoading() = TODO()
        override fun reload(flags: LoadUrlFlags) = TODO()
        override fun goBack(userInteraction: Boolean) = TODO()
        override fun goForward(userInteraction: Boolean) = TODO()
        override fun restoreState(state: EngineSessionState): Boolean = TODO()
        override fun flushSessionState() = TODO()
        override fun updateTrackingProtection(policy: TrackingProtectionPolicy) = TODO()
        override fun toggleDesktopMode(enable: Boolean, reload: Boolean) = TODO()
        override fun checkForPdfViewer(onResult: (Boolean) -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun addSignatureToPdf(text: String, onResult: () -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun sendGleanBrokenSiteReport(details: JSONObject?, description: String?, reason: String, url: String, sendTabSpecificInfo: Boolean, sendBlockedUrls: Boolean, onResult: () -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun getBrokenSiteReport(onResult: (JSONObject) -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun getWebCompatInfo(onResult: (JSONObject) -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun sendMoreWebCompatInfo(info: JSONObject, onResult: () -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun requestTranslate(fromLanguage: String, toLanguage: String, options: TranslationOptions?) = TODO()
        override fun requestTranslationRestore() = TODO()
        override fun getNeverTranslateSiteSetting(onResult: (Boolean) -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun setNeverTranslateSiteSetting(setting: Boolean, onResult: () -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun findAll(text: String) = TODO()
        override fun findNext(forward: Boolean) = TODO()
        override fun clearFindMatches() = TODO()
        override fun exitFullScreenMode() = TODO()
        override fun purgeHistory() = TODO()
        override fun processBackPressed(onResult: (Boolean) -> Unit) = TODO()
    }

    @Test
    fun `fresh app tab starts at configured home with no previous history`() {
        val tab = TabSessionState(id = "app-tab", content = ContentState(url = "https://streams.example/watch/123", title = "Episode"), parentId = "old-parent",
            engineState = EngineState(engineSessionState = oldPageHistory))
        val restored = tab.atBridgedAppHome(listOf(app))
        assertEquals(tab.id, restored.id)
        assertEquals(app.startUrl, restored.content.url)
        assertEquals(app.name, restored.content.title)
        assertNull(restored.parentId)
        assertNull(restored.engineState.engineSessionState)
        assertNull(restored.engineState.engineSession)
    }

    @Test
    fun `ordinary browser tabs keep their session and deep link`() {
        val tab = TabSessionState(id = "normal-tab", content = ContentState(url = "https://streams.example/watch/123"), parentId = "parent",
            engineState = EngineState(engineSessionState = oldPageHistory))
        assertSame(tab, tab.atBridgedAppHome(listOf(app)))
        assertSame(tab, tab.atBridgedAppHome(emptyList()))
    }

    @Test
    fun `home edit updates a restored tab before its first open`() {
        val lazyTab = TabSessionState(id = "app-tab", content = ContentState(url = app.startUrl, title = app.name))
        val edited = app.editing("Edited", "https://streams.example/edited-home")!!
        val updated = lazyTab.atEditedBridgedAppHome(listOf(edited))
        assertEquals(edited.startUrl, updated.content.url)
        assertEquals(edited.name, updated.content.title)
        assertEquals(lazyTab.id, updated.id)
    }

    @Test
    fun `home edit keeps hibernated app history and ordinary tabs intact`() {
        val hibernatedTab = TabSessionState(id = "app-tab", content = ContentState(url = "https://streams.example/watch/123"),
            engineState = EngineState(engineSessionState = oldPageHistory))
        val edited = app.editing("Edited", "https://streams.example/edited-home")!!
        assertSame(hibernatedTab, hibernatedTab.atEditedBridgedAppHome(listOf(edited)))
        val normalTab = hibernatedTab.copy(id = "normal-tab", engineState = EngineState())
        assertSame(normalTab, normalTab.atEditedBridgedAppHome(listOf(edited)))
    }

    @Test
    fun `edited home is used even when saved page has left the app origin`() {
        val tab = TabSessionState(id = "app-tab", content = ContentState(url = "https://external.example/"))
        val edited = app.editing("Edited", "https://streams.example/new-home")!!
        assertEquals(edited.startUrl, tab.atBridgedAppHome(listOf(edited)).content.url)
    }

    @Test
    fun `reopening at start entry is a no-op`() {
        val session = RecordingEngineSession()
        val tab = TabSessionState(
            id = "app-tab",
            content = ContentState(
                url = app.startUrl,
                canGoBack = false,
                history = HistoryState(
                    items = listOf(HistoryItem("Streams", app.startUrl)),
                    currentIndex = 0,
                ),
            ),
            engineState = EngineState(engineSession = session),
        )
        assertEquals(BridgedAppResetAction.NONE, tab.resetBridgedAppToStart(app.startUrl))
        assertEquals(emptyList<Int>(), session.historyIndices)
        assertEquals(emptyList<String>(), session.loadedUrls)
    }

    @Test
    fun `reopening after in-app navigation traverses to first history entry without reload`() {
        val session = RecordingEngineSession()
        val tab = TabSessionState(
            id = "app-tab",
            content = ContentState(
                url = "https://streams.example/home#/player/42",
                canGoBack = true,
                history = HistoryState(
                    items = listOf(
                        HistoryItem("Streams", "https://streams.example/home?sort=recent#/"),
                        HistoryItem("Catalog", "https://streams.example/home#/catalog"),
                        HistoryItem("Player", "https://streams.example/home#/player/42"),
                    ),
                    currentIndex = 2,
                ),
            ),
            engineState = EngineState(engineSession = session),
        )
        assertEquals(BridgedAppResetAction.GO_TO_FIRST_HISTORY_ITEM, tab.resetBridgedAppToStart(app.startUrl))
        assertEquals(listOf(0), session.historyIndices)
        assertEquals(emptyList<String>(), session.loadedUrls)
        // Root start URLs without or with trailing slash match root history items.
        assertEquals(
            BridgedAppResetAction.GO_TO_FIRST_HISTORY_ITEM,
            bridgedAppResetAction(
                startUrl = "https://streams.example",
                canGoBack = true,
                firstHistoryUrl = "https://streams.example/#/discover",
            ),
        )
    }

    @Test
    fun `reopening falls back to loading startUrl when first history entry has a different path or is missing`() {
        val session = RecordingEngineSession()
        val differentFirstPathTab = TabSessionState(
            id = "app-tab",
            content = ContentState(
                url = "https://streams.example/watch/99",
                canGoBack = true,
                history = HistoryState(
                    items = listOf(
                        HistoryItem("Login", "https://streams.example/login"),
                        HistoryItem("Watch", "https://streams.example/watch/99"),
                    ),
                    currentIndex = 1,
                ),
            ),
            engineState = EngineState(engineSession = session),
        )
        assertEquals(BridgedAppResetAction.LOAD_START_URL, differentFirstPathTab.resetBridgedAppToStart(app.startUrl))
        assertEquals(emptyList<Int>(), session.historyIndices)
        assertEquals(listOf(app.startUrl), session.loadedUrls)

        val missingHistoryItemsTab = TabSessionState(
            id = "app-tab",
            content = ContentState(
                url = "https://streams.example/watch/99",
                canGoBack = true,
                history = HistoryState(items = emptyList(), currentIndex = 0),
            ),
            engineState = EngineState(engineSession = session),
        )
        assertEquals(BridgedAppResetAction.LOAD_START_URL, missingHistoryItemsTab.resetBridgedAppToStart(app.startUrl))
        assertEquals(listOf(app.startUrl, app.startUrl), session.loadedUrls)
    }
}
