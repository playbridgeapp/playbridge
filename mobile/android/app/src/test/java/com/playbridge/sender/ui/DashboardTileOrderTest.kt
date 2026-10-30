package com.playbridge.sender.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTileOrderTest {
    private val defaults = listOf("browser", "connection", "screen-mirror", "phone-files", "iptv", "collections", "cast-history", "app:https://streams.example")

    @Test
    fun `a user-added app can be second across pages`() {
        val order = DashboardTileOrder.move(defaults, defaults.last(), 1)
        assertEquals(listOf("browser", "app:https://streams.example", "connection"), order.take(3))
        assertEquals(defaults.toSet(), order.toSet())
        assertEquals(order, DashboardTileOrder.reconcile(order, defaults.reversed()))
    }

    @Test
    fun `unavailable and duplicate IDs are removed while new tiles append`() {
        val saved = listOf("app:https://streams.example", "browser", "browser", "removed")
        val available = listOf("browser", "app:https://streams.example", "app:https://jellyfin.example")
        assertEquals(
            listOf("app:https://streams.example", "browser", "app:https://jellyfin.example"),
            DashboardTileOrder.reconcile(saved, available),
        )
        assertEquals(available, DashboardTileOrder.reconcile(emptyList(), available))
    }

    @Test
    fun `moving in either direction has an exact final position`() {
        assertEquals(listOf("b", "c", "a"), DashboardTileOrder.move(listOf("a", "b", "c"), "a", 2))
        assertEquals(listOf("c", "a", "b"), DashboardTileOrder.move(listOf("a", "b", "c"), "c", 0))
        assertEquals(defaults, DashboardTileOrder.move(defaults, "missing", 0))
        assertEquals(defaults, DashboardTileOrder.move(defaults, "browser", -1))
        assertEquals(defaults, DashboardTileOrder.move(defaults, "browser", defaults.size))
        assertEquals(defaults, DashboardTileOrder.move(defaults, "browser", 0))
    }

    @Test
    fun `fresh dashboard uses ordinary feature order and no installed apps`() {
        val available = listOf("browser", "connection", DashboardTileOrder.ADD_WEB_APP_ID)
        assertEquals(available, DashboardTileOrder.reconcile(emptyList(), available))
    }

    @Test
    fun `existing Streams entry keeps its saved position and new apps append`() {
        val installed = "app:https://" + "streams" + ".playbridge.app"
        val saved = listOf("phone-files", installed, "browser")
        val available = listOf("browser", "connection", "phone-files", installed, "app:https://new.example")
        assertEquals(saved + listOf("connection", "app:https://new.example"), DashboardTileOrder.reconcile(saved, available))
    }
    @Test
    fun `saved retired tile ids are ignored without reordering retained apps`() {
        assertEquals(listOf("phone-files", "browser", "iptv"), DashboardTileOrder.reconcile(
            listOf("library", "phone-files", "debrid", "browser"), listOf("browser", "phone-files", "iptv")))
    }

}
