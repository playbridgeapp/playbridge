package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFailureNotificationTest {
    @Test fun noNoticeLeavesNotificationUnchanged() {
        assertNull(playbackFailureNotification("Hisense", null))
    }
    @Test fun safeErrorNamesTheReceiver() {
        assertEquals("Playback failed on Hisense: The video stopped loading",
            playbackFailureNotification("Hisense", "The video stopped loading"))
        assertEquals("Playback failed on Main Bedroom: The link was refused (HTTP 403)",
            playbackFailureNotification("Main Bedroom", "The link was refused (HTTP 403)"))
    }
    @Test fun arbitraryWireTextAndUrlShapedNamesNeverReachTheLockScreen() {
        for (raw in listOf("https://private.example/token", "Failed on private.example", "secret-token")) {
            val text = playbackFailureNotification("https://private.example", raw)!!
            assertEquals("Playback failed on TV: The media couldn't be loaded", text)
            assertFalse(text.contains(raw))
        }
    }
}
