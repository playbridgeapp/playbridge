package com.playbridge.sender.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPipTest {
    @Test
    fun `the window shows play or pause, next when queued, then background`() {
        assertEquals(listOf(PipControl.PAUSE, PipControl.BACKGROUND), pipControls(playing = true, hasNext = false))
        assertEquals(listOf(PipControl.PLAY, PipControl.NEXT, PipControl.BACKGROUND), pipControls(playing = false, hasNext = true))
    }

    @Test
    fun `fewer allowed buttons drop background first, then next`() {
        assertEquals(listOf(PipControl.PAUSE, PipControl.NEXT), pipControls(playing = true, hasNext = true, max = 2))
        assertEquals(listOf(PipControl.PAUSE), pipControls(playing = true, hasNext = true, max = 1))
        assertEquals(listOf(PipControl.PAUSE), pipControls(playing = true, hasNext = true, max = 0))
    }

    @Test
    fun `button ids round-trip and unknown ids are ignored`() {
        PipControl.entries.forEach { assertEquals(it, PipControl.of(it.id)) }
        assertNull(PipControl.of(0))
    }

    @Test
    fun `window shape follows the video and stays within Android's limits`() {
        assertEquals(PipAspect(16, 9), pipAspect(1920, 1080))
        assertEquals(PipAspect(16, 9), pipAspect(0, 0))
        assertEquals(PipAspect(9, 16), pipAspect(1080, 1920))
        // A rotated phone recording plays upright.
        assertEquals(PipAspect(9, 16), pipAspect(1920, 1080, rotationDegrees = 90))
        // Anamorphic: 720×576 with wide pixels shows at 16:9.
        // Anamorphic PAL: 720×576 with 64:45 pixels shows at 16:9.
        val anamorphic = pipAspect(720, 576, pixelRatio = 64f / 45f)
        assertEquals(16.0 / 9, anamorphic.width.toDouble() / anamorphic.height, 0.001)
        assertEquals(PipAspect(239, 100), pipAspect(4000, 1000))
        assertEquals(PipAspect(100, 239), pipAspect(1000, 4000))
    }

    @Test
    fun `the window animates out of where the video is fitted on screen`() {
        // Landscape screen, 16:9 video: full width and height on a 16:9 screen.
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), fittedVideoBounds(1920, 1080, PipAspect(16, 9)))
        // Wider screen: pillarboxed.
        assertArrayEquals(intArrayOf(120, 0, 2040, 1080), fittedVideoBounds(2160, 1080, PipAspect(16, 9)))
        // Portrait screen: letterboxed.
        assertArrayEquals(intArrayOf(0, 656, 1080, 1263), fittedVideoBounds(1080, 1920, PipAspect(16, 9)))
        assertNull(fittedVideoBounds(0, 0, PipAspect(16, 9)))
    }
}
