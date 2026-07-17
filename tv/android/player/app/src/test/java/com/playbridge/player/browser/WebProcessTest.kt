package com.playbridge.player.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebProcessTest {
    @Test
    fun identifiesOnlyThePrivateWebProcess() {
        assertTrue(WebProcess.isWebProcessName("com.playbridge.player", "com.playbridge.player:web"))
        assertFalse(WebProcess.isWebProcessName("com.playbridge.player", "com.playbridge.player"))
        assertFalse(WebProcess.isWebProcessName("com.playbridge.player", null))
    }
}
