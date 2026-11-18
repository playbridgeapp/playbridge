package com.playbridge.sender.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientFrameTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    @Test
    fun `the first frame is used as is`() {
        val frame = intArrayOf(rgb(10, 20, 30), rgb(200, 100, 50))
        assertArrayEquals(frame, AmbientFrame.blend(null, frame))
    }

    @Test
    fun `the glow steps part of the way toward a new frame`() {
        val glow = intArrayOf(rgb(255, 0, 0))
        val next = AmbientFrame.blend(glow, intArrayOf(rgb(0, 0, 255)))[0]
        assertEquals(166, red(next))
        assertEquals(89, blue(next))
        // Never darker than the brighter of the two, the dip a straight crossfade gives.
        assertTrue(AmbientFrame.meanLuma(intArrayOf(next)) > AmbientFrame.meanLuma(intArrayOf(rgb(128, 0, 128))) - 0.05)
    }

    @Test
    fun `a glow of another size restarts from the frame`() {
        val frame = intArrayOf(rgb(1, 2, 3))
        assertArrayEquals(frame, AmbientFrame.blend(intArrayOf(rgb(9, 9, 9), rgb(9, 9, 9)), frame))
    }

    @Test
    fun `brightness runs from black to white`() {
        assertEquals(0.0, AmbientFrame.meanLuma(intArrayOf(rgb(0, 0, 0))), 1e-9)
        assertEquals(1.0, AmbientFrame.meanLuma(intArrayOf(rgb(255, 255, 255))), 1e-9)
        assertEquals(0.0, AmbientFrame.meanLuma(IntArray(0)), 1e-9)
    }

    @Test
    fun `one black grab is ignored while the glow has colour, two are a dark scene`() {
        assertFalse(AmbientFrame.accepts(frameLuma = 0.0, glowLuma = 0.4, previousWasDark = false))
        assertTrue(AmbientFrame.accepts(frameLuma = 0.0, glowLuma = 0.4, previousWasDark = true))
        assertTrue(AmbientFrame.accepts(frameLuma = 0.0, glowLuma = null, previousWasDark = false))
        assertTrue(AmbientFrame.accepts(frameLuma = 0.0, glowLuma = 0.01, previousWasDark = false))
        assertTrue(AmbientFrame.accepts(frameLuma = 0.5, glowLuma = 0.4, previousWasDark = false))
    }

    @Test
    fun `softening spreads a bright pixel and keeps flat colour`() {
        val width = 7
        val height = 7
        val spot = IntArray(width * height) { rgb(0, 0, 0) }.also { it[3 * width + 3] = rgb(255, 255, 255) }
        val soft = AmbientFrame.soften(spot, width, height)
        assertTrue(red(soft[3 * width + 3]) in 1..254)
        assertTrue(red(soft[3 * width + 4]) > 0)
        val flat = IntArray(width * height) { rgb(40, 80, 120) }
        assertArrayEquals(flat, AmbientFrame.soften(flat, width, height))
    }

    @Test
    fun `the grab keeps the picture's shape`() {
        assertEquals(27, AmbientFrame.heightFor(16f / 9f))
        assertEquals(85, AmbientFrame.heightFor(9f / 16f))
        assertTrue(AmbientFrame.heightFor(100f) >= 1)
    }
}
