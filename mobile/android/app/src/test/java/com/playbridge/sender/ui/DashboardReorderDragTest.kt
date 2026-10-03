package com.playbridge.sender.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardReorderDragTest {
    private val ids = listOf("browser", "library", "streams", "connection")
    private val rows = listOf(
        DashboardReorderRowBounds("browser", 0, 0, 80),
        DashboardReorderRowBounds("library", 1, 80, 100),
        DashboardReorderRowBounds("streams", 2, 180, 60),
        DashboardReorderRowBounds("connection", 3, 240, 80),
    )

    @Test
    fun `drag follows measured centers rather than a fixed row height`() {
        assertNull(DashboardReorderDrag.targetIndex("browser", ids, rows, 129f))
        assertEquals(1, DashboardReorderDrag.targetIndex("browser", ids, rows, 130f))
        assertEquals(2, DashboardReorderDrag.targetIndex("browser", ids, rows, 210f))
        assertEquals(3, DashboardReorderDrag.targetIndex("browser", ids, rows, 400f))
        assertEquals(0, DashboardReorderDrag.targetIndex("connection", ids, rows, 40f))
        assertEquals(1, DashboardReorderDrag.targetIndex("connection", ids, rows, 130f))
        assertNull(DashboardReorderDrag.targetIndex("connection", ids, rows, 211f))
    }

    @Test
    fun `old layout cannot bounce a moved tile back before the next frame`() {
        val moved = DashboardTileOrder.move(ids, "browser", 2)
        assertNull(DashboardReorderDrag.targetIndex("browser", moved, rows, 210f))
        val updated = listOf(
            DashboardReorderRowBounds("library", 0, 0, 100),
            DashboardReorderRowBounds("streams", 1, 100, 60),
            DashboardReorderRowBounds("browser", 2, 160, 80),
            DashboardReorderRowBounds("connection", 3, 240, 80),
        )
        assertNull(DashboardReorderDrag.targetIndex("browser", moved, updated, 210f))
        assertEquals(1, DashboardReorderDrag.targetIndex("browser", moved, updated, 130f))
    }

    @Test
    fun `missing and helper rows do not participate in reordering`() {
        assertNull(DashboardReorderDrag.targetIndex("removed", ids, rows, 100f))
        val withFooter = rows + DashboardReorderRowBounds("reorder-help", 4, 320, 80)
        assertEquals(3, DashboardReorderDrag.targetIndex("browser", ids, withFooter, 400f))
        assertNull(DashboardReorderDrag.targetIndex("connection", ids, withFooter, 400f))
    }

    @Test
    fun `edge scrolling is bounded and accelerates only near edges`() {
        fun speed(center: Float) = DashboardReorderDrag.edgeScrollSpeed(center, 0, 400, 80f, 600f)
        assertEquals(0f, speed(200f), 0f)
        assertEquals(0f, speed(80f), 0f)
        assertEquals(0f, speed(320f), 0f)
        assertEquals(-300f, speed(40f), 0f)
        assertEquals(300f, speed(360f), 0f)
        assertEquals(-600f, speed(-100f), 0f)
        assertEquals(600f, speed(1000f), 0f)
        assertEquals(0f, DashboardReorderDrag.edgeScrollSpeed(0f, 0, 0, 80f, 600f), 0f)
        assertEquals(0f, DashboardReorderDrag.edgeScrollSpeed(0f, 0, 400, 0f, 600f), 0f)
    }
}
