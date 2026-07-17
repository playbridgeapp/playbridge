package com.playbridge.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvProcessTest {
    @Test
    fun identifiesOnlyThePrivateMpvProcess() {
        assertTrue(MpvProcess.isMpvProcessName("com.playbridge.player", "com.playbridge.player:mpv"))
        assertFalse(MpvProcess.isMpvProcessName("com.playbridge.player", "com.playbridge.player"))
        assertFalse(MpvProcess.isMpvProcessName("com.playbridge.player", null))
    }
}
