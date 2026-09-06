package com.playbridge.sender.cast

import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.protocol.createQueueAddCommandJson
import com.playbridge.shared.protocol.parseIncomingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload
import playbridge.PlaylistPayload

class CastHistoryCommandTest {
    @Test
    fun playlistRetainsMediaAndMarksEveryItemThroughWireRoundTrip() {
        val items = listOf(
            PlayPayload(url = "https://example.com/a", title = "First"),
            PlayPayload(url = "https://example.com/b", start_position_ms = 5000),
        )
        val message = createPlaylistCommandJson(PlaylistPayload(items = items, start_index = 1))
        val decoded = parseIncomingMessage(applyCastHistoryPreference(message, true)) as IncomingMessage.Playlist
        assertEquals(1, decoded.payload.start_index)
        assertEquals(items.map { it.url }, decoded.payload.items.map { it.url })
        assertEquals("First", decoded.payload.items[0].title)
        assertEquals(5000L, decoded.payload.items[1].start_position_ms)
        assertTrue(decoded.payload.items.all { it.skip_history == true })
    }

    @Test
    fun queueAddCarriesPrivacyFlag() {
        val message = createQueueAddCommandJson(PlayPayload(url = "https://example.com/a"))
        val decoded = parseIncomingMessage(applyCastHistoryPreference(message, true)) as IncomingMessage.QueueAdd
        assertEquals(true, decoded.payload.item?.skip_history)
    }

    @Test
    fun disabledSettingAndNonMediaCommandsAreUnchanged() {
        val message = createPlaylistCommandJson(PlaylistPayload(items = listOf(PlayPayload(url = "https://example.com/a"))))
        assertEquals(message, applyCastHistoryPreference(message, false))
        val control = """{"type":"command","action":"control","payload":{"command":"stop"}}"""
        assertEquals(control, applyCastHistoryPreference(control, true))
    }
}
