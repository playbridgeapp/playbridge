package com.playbridge.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UserScriptFormatTest {
    @Test
    fun `sizes read naturally`() {
        assertEquals("1 byte", formatScriptSize(1))
        assertEquals("812 bytes", formatScriptSize(812))
        assertEquals("4 KB", formatScriptSize(4096))
        assertEquals("4.5 KB", formatScriptSize(4608))
        assertEquals("1.5 MB", formatScriptSize(1_572_864))
    }

    @Test
    fun `fingerprint is grouped in fours`() {
        assertEquals("9f2c 41ab 07de", groupFingerprint("9f2c41ab07de"))
        assertEquals("9f2c 4", groupFingerprint("9f2c4"))
        assertEquals("", groupFingerprint(""))
    }

    @Test
    fun `seconds left rounds up and clamps`() {
        assertEquals(60, secondsLeft(60_000))
        assertEquals(1, secondsLeft(1))
        assertEquals(0, secondsLeft(-5))
    }
}
