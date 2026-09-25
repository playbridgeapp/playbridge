package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSubtitleOffPolicyTest {
    @Test
    fun usesReceiverOffIdentifierWhenPresent() {
        for (id in listOf("off", "none", "no", "-1")) {
            assertEquals(id, subtitleOffTrack(listOf(
                MediaTrack(id = id, name = "Off", selected = false),
                MediaTrack(id = "2", name = "English", selected = true),
            )).id)
        }
    }

    @Test
    fun addsAppleCompatibleOffActionWhenReceiverOmitsIt() {
        val playing = subtitleOffTrack(listOf(MediaTrack("2", "English", selected = true)))
        assertEquals("none", playing.id)
        assertFalse(playing.selected)

        val alreadyOff = subtitleOffTrack(listOf(MediaTrack("2", "English", selected = false)))
        assertTrue(alreadyOff.selected)
    }
}
