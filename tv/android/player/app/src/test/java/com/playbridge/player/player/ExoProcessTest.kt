package com.playbridge.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoProcessTest {
    @Test
    fun identifiesOnlyThePrivateExoProcess() {
        assertTrue(ExoProcess.isExoProcessName("com.playbridge.player", "com.playbridge.player:exo"))
        assertFalse(ExoProcess.isExoProcessName("com.playbridge.player", "com.playbridge.player"))
        assertFalse(ExoProcess.isExoProcessName("com.playbridge.player", null))
    }
}
