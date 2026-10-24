package com.playbridge.sender.ui

import androidx.compose.runtime.saveable.SaverScope
import com.playbridge.sender.FlavorConfig
import com.playbridge.sender.browser.BridgedApp
import com.playbridge.sender.browser.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardItemsTest {
    private val builtInIds = linkedMapOf(
        Screen.Remote to "remote",
        Screen.Browser to "browser",
        Screen.Library to "library",
        null to "connection",
        Screen.ScreenMirror to "screen-mirror",
        Screen.PhoneFiles to "phone-files",
        Screen.DebridLibrary to "debrid",
        Screen.Iptv to "iptv",
        Screen.Collections to "collections",
        Screen.CastHistory to "cast-history",
    )

    @Test
    fun `every production built-in constructs with its stable ID in both connection states and flavors`() {
        for (debridSupported in listOf(false, true)) {
            for (connected in listOf(false, true)) {
                // Exercise the real DashboardItem constructors used on launch, not a parallel fixture.
                val items = dashboardItems(isConnected = connected, debridSupported = debridSupported)
                val builtIns = items.filter { it.id != "bridged-apps" }
                val expected = builtInIds.filterKeys { debridSupported || it != Screen.DebridLibrary }
                assertEquals(expected, builtIns.associate { it.screen to it.id })
                assertEquals(expected.size + 1, items.size)
                assertEquals(items.size, items.map { it.id }.distinct().size)
            }
        }
    }

    @Test
    fun `Remote stays visible while disconnected and uses the selected TV subtitle`() {
        for (debridSupported in listOf(false, true)) {
            val items = dashboardItems(
                isConnected = false,
                remoteSubtitle = "Living Room · TV remote only",
                debridSupported = debridSupported,
            )
            val remote = items.single { it.screen == Screen.Remote }
            assertEquals("remote", remote.id)
            assertEquals("Remote", remote.title)
            assertEquals("Living Room · TV remote only", remote.subtitle)
            assertEquals("Not connected", items.single { it.id == "connection" }.subtitle)
            assertEquals(debridSupported, items.any { it.screen == Screen.DebridLibrary })
        }
        assertEquals("Control your TV", dashboardItems().single { it.id == "remote" }.subtitle)
    }

    @Test
    fun `installed apps replace the placeholder without colliding with Remote or Browser`() {
        assertEquals(Screen.Browser, dashboardItems().single { it.id == "bridged-apps" }.screen)
        val apps = listOf(app("https://streams.playbridge.app"), app("https://jellyfin.example"))
        val items = dashboardItems(bridgedApps = apps)
        assertFalse(items.any { it.id == "bridged-apps" })
        assertEquals(apps.map { "app:${it.origin}" }, items.filter { it.app != null }.map { it.id })
        assertEquals(apps, items.mapNotNull { it.app })
        assertEquals(items.size, items.map { it.id }.distinct().size)
        assertTrue(items.any { it.id == "remote" && it.screen == Screen.Remote })
        assertTrue(items.any { it.id == "browser" && it.screen == Screen.Browser && it.app == null })
    }

    @Test
    fun `old saved orders append Remote once and preserve the user's order on later launches`() {
        for (debridSupported in listOf(false, true)) {
            val available = dashboardItems(debridSupported = debridSupported).map { it.id }
            val oldOrder = available.filterNot { it == "remote" }.reversed()
            val reconciled = DashboardTileOrder.reconcile(oldOrder + listOf("removed", oldOrder.first()), available)
            assertEquals(oldOrder + "remote", reconciled)
            assertEquals(reconciled, DashboardTileOrder.reconcile(reconciled, available))
            assertEquals(available.toSet(), reconciled.toSet())
        }
    }

    @Test
    fun `first launch and legacy migration keep Remote alongside Browser Streams and Library`() {
        val apps = listOf(app("https://streams.playbridge.app"))
        val available = dashboardItems(bridgedApps = apps).map { it.id }
        for (saved in listOf(emptyList(), available.filterNot { it == "remote" }.reversed())) {
            val migrated = DashboardTileOrder.migrateOrder(saved, available)
            assertEquals(listOf("browser", "app:https://streams.playbridge.app", "library"), migrated.take(3))
            assertEquals(1, migrated.count { it == "remote" })
            assertEquals(available.size, migrated.size)
            assertEquals(available.toSet(), migrated.toSet())
        }
    }

    @Test
    fun `Remote reorders across pages and survives flavor and app visibility changes`() {
        val apps = listOf(app("https://streams.playbridge.app"))
        val foss = dashboardItems(bridgedApps = apps, debridSupported = true).map { it.id }
        for (position in listOf(0, foss.lastIndex)) {
            val moved = DashboardTileOrder.move(foss, "remote", position)
            assertEquals("remote", moved[position])
            assertEquals(moved, DashboardTileOrder.reconcile(moved, foss))
            val play = dashboardItems(debridSupported = false).map { it.id }
            val visible = DashboardTileOrder.reconcile(moved, play)
            assertEquals(moved.filter { it in play } + "bridged-apps", visible)
            assertEquals(1, visible.count { it == "remote" })
            assertFalse("debrid" in visible)
            assertFalse(visible.any { it.startsWith("app:") })
            assertEquals(visible, visible.chunked(DashboardTileOrder.TILES_PER_PAGE).flatten())
        }
    }

    @Test
    fun `pager reaches every tile including the new Remote at installed app page boundaries`() {
        // The old hard-coded count missed page three at 7 apps (Foss) or 8 apps (Play).
        val builtInCount = if (FlavorConfig.DEBRID_SUPPORTED) 10 else 9
        for (appCount in 0..20) {
            val apps = (0 until appCount).map { app("https://app$it.example") }
            val items = dashboardItems(bridgedApps = apps)
            assertEquals(builtInCount + appCount.coerceAtLeast(1), items.size)
            assertEquals(items.chunked(DashboardTileOrder.TILES_PER_PAGE).size, dashboardPageCount(apps))
        }
    }

    @Test
    fun `every visible built-in including Remote survives screen saving and restoration`() {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        for (item in dashboardItems().filter { it.app == null }) {
            val saved = with(Screen.Saver) { scope.save(item.screen) }
            assertTrue("Missing saved destination for ${item.id}", saved != null)
            assertEquals(item.screen, Screen.Saver.restore(requireNotNull(saved)))
        }
    }

    private fun app(origin: String) = BridgedApp(
        origin = origin,
        name = "Library",
        startUrl = "$origin/",
        iconUrl = null,
    )
}
