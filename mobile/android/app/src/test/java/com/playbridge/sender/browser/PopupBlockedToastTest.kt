package com.playbridge.sender.browser

import com.playbridge.sender.downloads.PendingPopup
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSessionState
import mozilla.components.concept.engine.Settings
import mozilla.components.concept.engine.translate.TranslationOptions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupBlockedToastTest {

    private open class FakeEngineSession : EngineSession() {
        var isClosed = false
        override fun close() {
            isClosed = true
        }

        override val settings: Settings get() = TODO()
        override fun loadUrl(url: String, parent: EngineSession?, flags: LoadUrlFlags, additionalHeaders: Map<String, String>?, originalUrl: String?, forceReload: Boolean) = TODO()
        override fun loadData(data: String, mimeType: String, encoding: String) = TODO()
        override fun requestPdfToDownload() = TODO()
        override fun requestPrintContent() = TODO()
        override fun stopLoading() = TODO()
        override fun reload(flags: LoadUrlFlags) = TODO()
        override fun goBack(userGesture: Boolean) = TODO()
        override fun goForward(userGesture: Boolean) = TODO()
        override fun goToHistoryIndex(index: Int) = TODO()
        override fun restoreState(state: EngineSessionState): Boolean = TODO()
        override fun flushSessionState() = TODO()
        override fun updateTrackingProtection(policy: TrackingProtectionPolicy) = TODO()
        override fun toggleDesktopMode(enable: Boolean, reload: Boolean) = TODO()
        override fun checkForPdfViewer(onSuccess: (Boolean) -> Unit, onError: (Throwable) -> Unit) = TODO()
        override fun addSignatureToPdf(signature: String, onSuccess: () -> Unit, onError: (Throwable) -> Unit) = TODO()
        override fun sendGleanBrokenSiteReport(details: JSONObject?, description: String?, reason: String, url: String, sendTabSpecificInfo: Boolean, sendBlockedUrls: Boolean, onResult: () -> Unit, onException: (Throwable) -> Unit) = TODO()
        override fun getBrokenSiteReport(onSuccess: (JSONObject) -> Unit, onError: (Throwable) -> Unit) = TODO()
        override fun getWebCompatInfo(onSuccess: (JSONObject) -> Unit, onError: (Throwable) -> Unit) = TODO()
        override fun sendMoreWebCompatInfo(info: JSONObject, onSuccess: () -> Unit, onError: (Throwable) -> Unit) = TODO()
        override fun requestTranslate(fromLanguage: String, toLanguage: String, options: TranslationOptions?) = TODO()
        override fun requestTranslationRestore() = TODO()
        override fun getNeverTranslateSiteSetting(onSuccess: (Boolean) -> Unit, onError: (Throwable) -> Unit) = TODO()
        override fun setNeverTranslateSiteSetting(neverTranslate: Boolean, onSuccess: () -> Unit, onError: (Throwable) -> Unit) = TODO()
        override fun findAll(text: String) = TODO()
        override fun findNext(forward: Boolean) = TODO()
        override fun clearFindMatches() = TODO()
        override fun exitFullScreenMode() = TODO()
        override fun purgeHistory() = TODO()
        override fun processBackPressed(callback: (Boolean) -> Unit) = TODO()
    }

    private fun createPopup(
        openerHost: String = "opener.com",
        popupUrl: String = "https://ads.example.com/landing",
        openerTabId: String? = "tab-1"
    ): Pair<PendingPopup, FakeEngineSession> {
        val session = FakeEngineSession()
        val popup = PendingPopup(
            openerHost = openerHost,
            popupUrl = popupUrl,
            engineSession = session,
            openerTabId = openerTabId
        )
        return Pair(popup, session)
    }

    // ── Destination Formatting Tests ───────────────────────────────────────────

    @Test
    fun `formatPopupDestination handles about blank, empty, and null`() {
        assertEquals("a blank page", formatPopupDestination(null))
        assertEquals("a blank page", formatPopupDestination(""))
        assertEquals("a blank page", formatPopupDestination("   "))
        assertEquals("a blank page", formatPopupDestination("about:blank"))
        assertEquals("a blank page", formatPopupDestination("ABOUT:BLANK"))
    }

    @Test
    fun `formatPopupDestination handles special schemes`() {
        assertEquals("a data: page", formatPopupDestination("data:text/html,<h1>Hello</h1>"))
        assertEquals("a javascript: page", formatPopupDestination("javascript:void(0)"))
        assertEquals("a blob: page", formatPopupDestination("blob:https://example.com/uuid-here"))
        assertEquals("an about: page", formatPopupDestination("about:config"))
    }

    @Test
    fun `formatPopupDestination strips query and fragment and shortens path`() {
        val formatted = formatPopupDestination("https://ads.example.com/landing/promo/offer?token=secret#top")
        assertEquals("ads.example.com/landing…", formatted)

        val singleSegment = formatPopupDestination("https://example.com/page?ref=123#hash")
        assertEquals("example.com/page", singleSegment)

        val rootUrl = formatPopupDestination("https://example.com/?ref=abc")
        assertEquals("example.com", rootUrl)
    }

    @Test
    fun `formatPopupDestination formats long host with middle ellipsis`() {
        val longUrl = "https://subdomain.verylonghostnameexceedingthirtycharacters.example.com/landing"
        val formatted = formatPopupDestination(longUrl)
        assertTrue(formatted.contains("…"))
        assertFalse(formatted.contains("?"))
        assertTrue(formatted.endsWith("/landing"))
    }

    @Test
    fun `formatPopupDestination formats IDN host to unicode`() {
        val idnUrl = "https://xn--e1afmkfd.xn--p1ai/path"
        val formatted = formatPopupDestination(idnUrl)
        assertEquals("пример.рф/path", formatted)
    }

    @Test
    fun `formatPopupDestination preserves port`() {
        assertEquals("example.com:8080/path", formatPopupDestination("https://example.com:8080/path?q=1"))
        assertEquals("localhost:3000", formatPopupDestination("http://localhost:3000"))
    }

    // ── Replace & Count Policy Tests ──────────────────────────────────────────

    @Test
    fun `title shows Pop-up blocked for single and count for multiple`() {
        assertEquals("Pop-up blocked", popupBlockedTitle(0))
        assertEquals("Pop-up blocked", popupBlockedTitle(1))
        assertEquals("2 pop-ups blocked", popupBlockedTitle(2))
        assertEquals("5 pop-ups blocked", popupBlockedTitle(5))
    }

    @Test
    fun `onNewBlockedPopup initializes state for first popup`() {
        val (popup1, _) = createPopup(popupUrl = "https://example.com/1")
        val (state, superseded) = onNewBlockedPopup(
            current = null,
            incoming = popup1,
            timeoutMs = 6000L
        )

        assertEquals(1, state.blockedCount)
        assertSame(popup1, state.latestPopup)
        assertEquals(6000L, state.totalTimeoutMs)
        assertEquals(6000L, state.remainingMs)
        assertFalse(state.isPaused)
        assertNull(superseded)
    }

    @Test
    fun `onNewBlockedPopup replaces previous popup, increments count, restarts timer and returns superseded`() {
        val (popup1, _) = createPopup(popupUrl = "https://example.com/1")
        val (popup2, _) = createPopup(popupUrl = "https://example.com/2")
        val (popup3, _) = createPopup(popupUrl = "https://example.com/3")

        val (state1, _) = onNewBlockedPopup(null, popup1, 6000L)
        val partiallyTicked = tickTimeout(state1, 3000L)
        assertEquals(3000L, partiallyTicked.remainingMs)

        // Incoming popup 2 replaces popup 1
        val (state2, superseded1) = onNewBlockedPopup(partiallyTicked, popup2, 6000L)
        assertEquals(2, state2.blockedCount)
        assertSame(popup2, state2.latestPopup)
        assertSame(popup1, superseded1)
        assertEquals(6000L, state2.remainingMs) // timer restarted!

        // Incoming popup 3 replaces popup 2
        val (state3, superseded2) = onNewBlockedPopup(state2, popup3, 6000L)
        assertEquals(3, state3.blockedCount)
        assertSame(popup3, state3.latestPopup)
        assertSame(popup2, superseded2)
        assertEquals(6000L, state3.remainingMs)
    }

    // ── Timeout & Pause Policy Tests ──────────────────────────────────────────

    @Test
    fun `tickTimeout counts down remaining time and respects pause`() {
        val (popup, _) = createPopup()
        val (initialState, _) = onNewBlockedPopup(null, popup, timeoutMs = 6000L)

        val ticked = tickTimeout(initialState, 1000L)
        assertEquals(5000L, ticked.remainingMs)
        assertFalse(isTimeoutExpired(ticked))

        val paused = setPaused(ticked, true)
        val tickedWhilePaused = tickTimeout(paused, 1000L)
        assertEquals(5000L, tickedWhilePaused.remainingMs) // did not tick while paused

        val resumed = setPaused(tickedWhilePaused, false)
        val tickedAfterResume = tickTimeout(resumed, 5000L)
        assertEquals(0L, tickedAfterResume.remainingMs)
        assertTrue(isTimeoutExpired(tickedAfterResume))

        // Clamps at zero, never negative
        val overTicked = tickTimeout(tickedAfterResume, 1000L)
        assertEquals(0L, overTicked.remainingMs)
    }

    @Test
    fun `timeoutProgress computes valid ratio`() {
        val (popup, _) = createPopup()
        val (state, _) = onNewBlockedPopup(null, popup, timeoutMs = 6000L)

        assertEquals(1.0f, timeoutProgress(state), 0.001f)
        assertEquals(0.5f, timeoutProgress(tickTimeout(state, 3000L)), 0.001f)
        assertEquals(0.0f, timeoutProgress(tickTimeout(state, 6000L)), 0.001f)
    }

    @Test
    fun `formatAlwaysAllowLabel shortens long hosts`() {
        assertEquals("Always allow example.com", formatAlwaysAllowLabel("example.com"))
        assertEquals("Always allow", formatAlwaysAllowLabel("verylongsubdomainhostname.example.com"))
        assertEquals("Always allow", formatAlwaysAllowLabel(""))
    }
}
