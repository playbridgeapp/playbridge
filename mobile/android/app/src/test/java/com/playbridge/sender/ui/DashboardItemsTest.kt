package com.playbridge.sender.ui

import androidx.compose.runtime.saveable.SaverScope
import com.playbridge.sender.browser.BridgedApp
import com.playbridge.sender.browser.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardItemsTest {
    private val builtInIds = linkedMapOf(
        Screen.Browser to "browser",
        Screen.PhoneFiles to "phone-files",
        Screen.ScreenMirror to "screen-mirror",
        Screen.Iptv to "iptv",
        Screen.Collections to "collections",
        Screen.CastHistory to "cast-history",
    )

    @Test
    fun `every production built-in constructs with its stable ID in both flavors`() {
        run {
            val items = dashboardItems()
            val builtIns = items.filterNot { it.isAddPlaceholder }
            val expected = builtInIds
            assertEquals(expected, builtIns.associate { it.screen to it.id })
            assertEquals(expected.size + 1, items.size)
            assertEquals(items.size, items.map { it.id }.distinct().size)
        }
    }

    @Test
    fun `Library, Play on and Remote are no longer tiles`() {
        val ids = dashboardItems().map { it.id }
        for (removed in listOf("library", "debrid", "connection", "remote")) assertFalse(removed in ids)
        assertFalse(dashboardItems().any { it.screen == Screen.Remote })
    }

    @Test
    fun `every tile has its own colour`() {
        val gradients = dashboardItems().filterNot { it.isAddPlaceholder }.map { it.gradientColors }
        assertEquals(gradients.size, gradients.distinct().size)
        assertTrue(dashboardItems().single { it.isAddPlaceholder }.gradientColors.isEmpty())
    }

    @Test
    fun `unknown state shows the neutral in-use subtitles`() {
        val subtitles = dashboardItems().associate { it.id to it.subtitle }
        assertEquals("Videos, music, photos", subtitles["phone-files"])
        assertEquals("Show this phone", subtitles["screen-mirror"])
        assertEquals("Live channels", subtitles["iptv"])
        assertEquals("Your playlists", subtitles["collections"])
        assertEquals("Recent casts", subtitles["cast-history"])
    }

    @Test
    fun `not set up subtitles tell people what to do first`() {
        val states = DashboardTileStates(
            mediaAccess = false, mirror = MirrorTileState.NeedsTv, iptvPlaylists = 0, collections = 0,
            castHistoryEmpty = true,
        )
        val subtitles = dashboardItems(states).associate { it.id to it.subtitle }
        assertEquals("Allow access", subtitles["phone-files"])
        assertEquals("Pick a TV first", subtitles["screen-mirror"])
        assertEquals("Add a playlist", subtitles["iptv"])
        assertEquals("Create your first", subtitles["collections"])
        assertEquals("Nothing yet", subtitles["cast-history"])
    }

    @Test
    fun `in use subtitles carry counts, the provider and the last cast`() {
        val states = DashboardTileStates(
            mediaAccess = true, iptvPlaylists = 3, collections = 1,
            castHistoryEmpty = false, lastCastTitle = "Big Buck Bunny",
        )
        val subtitles = dashboardItems(states).associate { it.id to it.subtitle }
        assertEquals("3 playlists", subtitles["iptv"])
        assertEquals("1 collection", subtitles["collections"])
        assertEquals("Last: Big Buck Bunny", subtitles["cast-history"])
    }

    @Test
    fun `screen mirror covers every TV situation and marks live sharing`() {
        fun mirror(state: MirrorTileState) = dashboardItems(DashboardTileStates(mirror = state)).single { it.id == "screen-mirror" }
        assertEquals("Not on this TV", mirror(MirrorTileState.Unsupported).subtitle)
        assertEquals("TV needs an update", mirror(MirrorTileState.NeedsUpdate).subtitle)
        val live = mirror(MirrorTileState.Live("Living Room TV"))
        assertEquals("Living Room TV", live.subtitle)
        assertTrue(live.live)
        assertFalse(mirror(MirrorTileState.Ready).live)
    }

    @Test
    fun `mirror tile state follows the chosen TV like the Screen mirror screen`() {
        fun state(
            mirroring: Boolean = false, tvChosen: Boolean = true, external: Boolean = false,
            externalSupported: Boolean = false, connected: Boolean = true, nativeSupported: Boolean = true,
        ) = mirrorTileState(mirroring, tvChosen, "Living Room TV", external, externalSupported, connected, nativeSupported)
        assertEquals(MirrorTileState.Live("Living Room TV"), state(mirroring = true, tvChosen = false))
        assertEquals(MirrorTileState.NeedsTv, state(tvChosen = false))
        assertEquals(MirrorTileState.Unsupported, state(external = true))
        assertEquals(MirrorTileState.Ready, state(external = true, externalSupported = true))
        assertEquals(MirrorTileState.NeedsUpdate, state(nativeSupported = false))
        // Capabilities aren't known until the TV answers, so an unconnected TV isn't blamed.
        assertEquals(MirrorTileState.Ready, state(connected = false, nativeSupported = false))
        assertEquals(MirrorTileState.Ready, state())
    }

    @Test
    fun `installed apps replace the Add web app placeholder with matching neutral colours`() {
        val placeholder = dashboardItems().single { it.isAddPlaceholder }
        assertEquals(Screen.Browser, placeholder.screen)
        assertEquals(DashboardTileOrder.ADD_WEB_APP_ID, placeholder.id)
        val apps = listOf(app("https://video.example"), app("https://jellyfin.example"))
        val items = dashboardItems(bridgedApps = apps)
        assertFalse(items.any { it.isAddPlaceholder })
        assertEquals(apps.map { "app:${it.origin}" }, items.filter { it.app != null }.map { it.id })
        assertEquals(items.size, items.map { it.id }.distinct().size)
        val (video, other) = items.filter { it.app != null }
        assertEquals(video.gradientColors, other.gradientColors)
        assertTrue(items.any { it.id == "browser" && it.screen == Screen.Browser && it.app == null })
    }

    @Test
    fun `saved orders drop removed tiles and keep the user's order`() {
        run {
            val available = dashboardItems().map { it.id }
            val oldOrder = listOf("remote", "library") + available.reversed() + "connection"
            val reconciled = DashboardTileOrder.reconcile(oldOrder, available)
            assertEquals(available.reversed(), reconciled)
            assertEquals(reconciled, DashboardTileOrder.reconcile(reconciled, available))
        }
    }

    @Test
    fun `Browser can't be hidden but every other tile can`() {
        val order = dashboardItems().map { it.id }
        val visible = DashboardTileOrder.visible(order, order.toSet())
        assertEquals(listOf("browser"), visible)
        assertEquals(order.filterNot { it == "iptv" }, DashboardTileOrder.visible(order, setOf("iptv", "unknown")))
    }

    @Test
    fun `every built-in survives screen saving and restoration`() {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        for (item in dashboardItems().filter { it.app == null && it.screen != null }) {
            val saved = with(Screen.Saver) { scope.save(requireNotNull(item.screen)) }
            assertTrue("Missing saved destination for ${item.id}", saved != null)
            assertEquals(item.screen, Screen.Saver.restore(requireNotNull(saved)))
        }
    }

    private fun app(origin: String) = BridgedApp(
        origin = origin,
        name = "App",
        startUrl = "$origin/",
        iconUrl = null,
    )
}
