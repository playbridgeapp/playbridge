package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvProcessTest {
    @Test
    fun identifiesOnlyThePrivateMpvProcess() {
        assertTrue(MpvProcess.isMpvProcessName("com.playbridge.player", "com.playbridge.player:mpv"))
        assertFalse(MpvProcess.isMpvProcessName("com.playbridge.player", "com.playbridge.player"))
        assertFalse(MpvProcess.isMpvProcessName("com.playbridge.player", null))
    }

    @Test
    fun identifiesTheTrackAddedByMpvSubAdd() {
        val tracks = listOf("1" to "English", "2" to "Spanish", "4" to "request-marker")
        assertEquals(
            "4",
            findAddedSubtitleTrackId(tracks, setOf("1", "2"), "request-marker"),
        )
        assertNull(findAddedSubtitleTrackId(tracks, setOf("1", "2"), "other-marker"))
    }
}
