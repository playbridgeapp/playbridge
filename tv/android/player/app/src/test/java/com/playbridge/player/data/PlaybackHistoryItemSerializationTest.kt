package com.playbridge.player.data

import com.playbridge.shared.protocol.protocolJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryItemSerializationTest {
    @Test
    fun `history written before playback context remains readable`() {
        val item = protocolJson.decodeFromString<PlaybackHistoryItem>(
            """{"id":"old","payloadJson":"{}","url":"https://example.invalid/video","title":null,"position":1000,"duration":2000}""",
        )

        assertEquals("old", item.id)
        assertNull(item.playbackContext)
    }

    @Test
    fun `playback context survives history serialization`() {
        val original = PlaybackHistoryItem(
            id = "context",
            payloadJson = "{}",
            url = "https://example.invalid/video",
            title = "Video",
            position = 1000,
            duration = 2000,
            playbackContext = PlaybackContext(
                audioTrack = PlaybackTrackPreference("audio-1", "Japanese", "ja"),
                playbackSpeed = 1.25f,
                videoScalingMode = "Zoom",
                isLooping = true,
            ),
        )

        val restored = protocolJson.decodeFromString<PlaybackHistoryItem>(
            protocolJson.encodeToString(original),
        )

        assertEquals(original, restored)
    }

    @Test
    fun `playback context diagnostics redact external subtitle urls`() {
        val context = PlaybackContext(
            audioTrack = PlaybackTrackPreference("audio-1", "Japanese", "ja"),
            externalSubtitleUrl = "https://example.invalid/subtitle?token=secret",
            playbackSpeed = 1.25f,
        )

        val summary = context.toSafeLogString()

        assertTrue(summary.contains("audio-1"))
        assertTrue(summary.contains("externalSubtitle=true"))
        assertFalse(summary.contains("example.invalid"))
        assertFalse(summary.contains("secret"))
    }
}
