package com.playbridge.sender.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EdgeLongPressGuardTest {

    @Before
    fun reset() = EdgeLongPressGuard.resetForTesting()

    @Test
    fun `down inside left back-gesture zone suppresses`() {
        assertTrue(
            isWithinGestureZone(
                downX = 20f, downY = 900f,
                viewWidth = 1080, viewHeight = 2400,
                leftInset = 48, topInset = 0, rightInset = 48, bottomInset = 24,
            ),
        )
    }

    @Test
    fun `down inside right back-gesture zone suppresses`() {
        assertTrue(
            isWithinGestureZone(
                downX = 1050f, downY = 300f,
                viewWidth = 1080, viewHeight = 2400,
                leftInset = 48, topInset = 0, rightInset = 48, bottomInset = 24,
            ),
        )
    }

    @Test
    fun `down inside bottom home-gesture zone suppresses`() {
        assertTrue(
            isWithinGestureZone(
                downX = 540f, downY = 2390f,
                viewWidth = 1080, viewHeight = 2400,
                leftInset = 48, topInset = 0, rightInset = 48, bottomInset = 24,
            ),
        )
    }

    @Test
    fun `down in the middle of the page does not suppress`() {
        assertFalse(
            isWithinGestureZone(
                downX = 540f, downY = 1200f,
                viewWidth = 1080, viewHeight = 2400,
                leftInset = 48, topInset = 0, rightInset = 48, bottomInset = 24,
            ),
        )
    }

    @Test
    fun `zero insets (button navigation) never suppress`() {
        assertFalse(
            isWithinGestureZone(
                downX = 2f, downY = 2399f,
                viewWidth = 1080, viewHeight = 2400,
                leftInset = 0, topInset = 0, rightInset = 0, bottomInset = 0,
            ),
        )
    }

    @Test
    fun `guard suppresses a fresh edge down`() {
        EdgeLongPressGuard.recordDown(x = 10f, y = 900f, widthPx = 1080, heightPx = 2400)
        assertTrue(
            EdgeLongPressGuard.shouldSuppressLongPress(
                leftInset = 48, topInset = 0, rightInset = 48, bottomInset = 24,
            ),
        )
    }

    @Test
    fun `guard does not suppress a fresh mid-screen down`() {
        EdgeLongPressGuard.recordDown(x = 540f, y = 1200f, widthPx = 1080, heightPx = 2400)
        assertFalse(
            EdgeLongPressGuard.shouldSuppressLongPress(
                leftInset = 48, topInset = 0, rightInset = 48, bottomInset = 24,
            ),
        )
    }

    @Test
    fun `guard ignores a stale down`() {
        EdgeLongPressGuard.recordDown(x = 10f, y = 900f, widthPx = 1080, heightPx = 2400)
        val recordedAt = System.currentTimeMillis()
        assertFalse(
            EdgeLongPressGuard.shouldSuppressLongPress(
                leftInset = 48, topInset = 0, rightInset = 48, bottomInset = 24,
                nowMs = recordedAt + 5_000L,
            ),
        )
    }

    @Test
    fun `guard does not suppress before any down is recorded`() {
        assertFalse(
            EdgeLongPressGuard.shouldSuppressLongPress(
                leftInset = 48, topInset = 0, rightInset = 48, bottomInset = 24,
            ),
        )
    }
}
