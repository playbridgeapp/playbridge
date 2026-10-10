package com.playbridge.sender.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTileOrderTest {
    private val defaults = listOf("browser", "library", "connection", "screen-mirror", "phone-files", "debrid", "iptv", "collections", "cast-history", "app:https://streams.example")

    @Test
    fun `Streams can be second and Legacy Library third across pages`() {
        val order = DashboardTileOrder.move(defaults, defaults.last(), 1)
        assertEquals(listOf("browser", "app:https://streams.example", "library"), order.take(3))
        assertEquals(defaults.toSet(), order.toSet())
        assertEquals(order, DashboardTileOrder.reconcile(order, defaults.reversed()))
    }

    @Test
    fun `unavailable and duplicate IDs are removed while new tiles append`() {
        val saved = listOf("app:https://streams.example", "browser", "browser", "removed", "debrid")
        val available = listOf("browser", "library", "app:https://streams.example", "app:https://jellyfin.example")
        assertEquals(
            listOf("app:https://streams.example", "browser", "library", "app:https://jellyfin.example"),
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
    fun `pages have eight slots and grow or shrink with installed apps`() {
        assertEquals(listOf(1, 1, 1, 2, 2, 3), listOf(0, 1, 8, 9, 16, 17).map(DashboardTileOrder::pageCount))
        val pages = defaults.chunked(DashboardTileOrder.TILES_PER_PAGE)
        assertEquals(listOf(8, 2), pages.map { it.size })
        assertEquals(defaults, pages.flatten())
    }

    @Test
    fun `new install default order places Browser first, Streams second, and Library legacy third`() {
        val available = listOf(
            "browser", "library", "connection", "screen-mirror", "phone-files",
            "debrid", "iptv", "collections", "cast-history", "app:https://streams.playbridge.app"
        )
        val migrated = DashboardTileOrder.migrateOrder(emptyList(), available)
        assertEquals(
            listOf(
                "browser",
                "app:https://streams.playbridge.app",
                "library",
                "connection",
                "screen-mirror",
                "phone-files",
                "debrid",
                "iptv",
                "collections",
                "cast-history",
            ),
            migrated
        )
    }

    @Test
    fun `existing saved order is migrated to place Streams second and Library legacy third while preserving other tiles order`() {
        val saved = listOf("browser", "library", "phone-files", "connection", "screen-mirror")
        val available = listOf(
            "browser", "library", "connection", "screen-mirror", "phone-files",
            "debrid", "iptv", "collections", "cast-history", "app:https://streams.playbridge.app"
        )
        val migrated = DashboardTileOrder.migrateOrder(saved, available)
        assertEquals(
            listOf(
                "browser",
                "app:https://streams.playbridge.app",
                "library",
                "phone-files",
                "connection",
                "screen-mirror",
                "debrid",
                "iptv",
                "collections",
                "cast-history",
            ),
            migrated
        )
    }

    @Test
    fun `existing saved order with Streams already installed moves Streams to position two`() {
        val saved = listOf("browser", "connection", "app:https://streams.playbridge.app", "library", "debrid")
        val available = listOf("browser", "library", "connection", "debrid", "app:https://streams.playbridge.app")
        val migrated = DashboardTileOrder.migrateOrder(saved, available)
        assertEquals(
            listOf(
                "browser",
                "app:https://streams.playbridge.app",
                "library",
                "connection",
                "debrid",
            ),
            migrated
        )
    }

    @Test
    fun `user reorder after migration is preserved on subsequent launches`() {
        val available = listOf(
            "browser", "library", "connection", "screen-mirror", "phone-files",
            "app:https://streams.playbridge.app"
        )
        val initialMigrated = DashboardTileOrder.migrateOrder(emptyList(), available)
        // User moves Streams to the very first position (position 0)
        val userReordered = DashboardTileOrder.move(initialMigrated, "app:https://streams.playbridge.app", 0)
        assertEquals(listOf("app:https://streams.playbridge.app", "browser", "library"), userReordered.take(3))

        // Subsequent launch: migration does NOT rerun; reconcile is used directly with saved order
        val subsequentOrder = DashboardTileOrder.reconcile(userReordered, available)
        assertEquals(userReordered, subsequentOrder)
        assertEquals("app:https://streams.playbridge.app", subsequentOrder.first())
    }
}
