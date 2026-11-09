package com.playbridge.sender.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortraitFillTest {
    @Test
    fun `a small pinch does nothing`() {
        assertNull(pinchFill(1f))
        assertNull(pinchFill(1.1f))
        assertNull(pinchFill(0.9f))
    }

    @Test
    fun `pinching open fills and pinching closed fits`() {
        assertEquals(true, pinchFill(PINCH_OPEN))
        assertEquals(true, pinchFill(2f))
        assertEquals(false, pinchFill(PINCH_CLOSE))
        assertEquals(false, pinchFill(0.5f))
    }
}
