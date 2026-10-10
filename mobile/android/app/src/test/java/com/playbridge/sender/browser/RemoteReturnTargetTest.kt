package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteReturnTargetTest {
    private val app = BridgedApp(
        origin = "https://streams.example",
        name = "Streams",
        startUrl = "https://streams.example/",
        iconUrl = null,
        tabId = "app-tab",
    )
    private val tabs = mapOf("app-tab" to "https://streams.example/watch/123")

    @Test
    fun `returns to the app tab that opened Remote`() {
        assertEquals(
            RemoteReturnTarget(Screen.Browser, "app-tab"),
            resolveRemoteReturnTarget(Screen.Browser, "app-tab", listOf(app), tabs),
        )
    }

    @Test
    fun `normal browser return does not enter an installed app`() {
        assertEquals(
            RemoteReturnTarget(Screen.Browser),
            resolveRemoteReturnTarget(Screen.Browser, null, listOf(app), tabs),
        )
    }

    @Test
    fun `removed app or closed session returns to dashboard`() {
        assertEquals(
            RemoteReturnTarget(Screen.Dashboard),
            resolveRemoteReturnTarget(Screen.Browser, "app-tab", emptyList(), tabs),
        )
        assertEquals(
            RemoteReturnTarget(Screen.Dashboard),
            resolveRemoteReturnTarget(Screen.Browser, "app-tab", listOf(app), emptyMap()),
        )
        assertEquals(
            RemoteReturnTarget(Screen.Dashboard),
            resolveRemoteReturnTarget(Screen.Browser, "app-tab", listOf(app.copy(tabId = "replacement")), tabs),
        )
    }

    @Test
    fun `tab that left app origin is not restored as a bridged app`() {
        assertEquals(
            RemoteReturnTarget(Screen.Dashboard),
            resolveRemoteReturnTarget(
                Screen.Browser, "app-tab", listOf(app),
                mapOf("app-tab" to "https://other.example/watch/123"),
            ),
        )
    }

    @Test
    fun `native screen return preserves its detail destination`() {
        val detail = Screen.LibraryDetail("123", "series", "addon")
        assertEquals(
            RemoteReturnTarget(detail),
            resolveRemoteReturnTarget(detail, "app-tab", listOf(app), tabs),
        )
    }

    @Test
    fun `invalid remote origin cannot loop back into Remote`() {
        assertEquals(
            RemoteReturnTarget(Screen.Browser),
            resolveRemoteReturnTarget(Screen.Remote, null, emptyList(), emptyMap()),
        )
    }

    @Test
    fun `dashboard close returns to main places but not to screens opened from it`() {
        listOf(Screen.Browser, Screen.Library, Screen.DebridLibrary, Screen.LibraryDetail("tt1", "movie"))
            .forEach { assertEquals(true, isDashboardReturnPlace(it)) }
        listOf(Screen.Settings, Screen.PhoneFiles, Screen.Connection, Screen.Remote, Screen.Dashboard)
            .forEach { assertEquals(false, isDashboardReturnPlace(it)) }
    }
}
