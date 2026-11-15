package com.playbridge.sender.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastHistoryMediaTest {
    private val single = CastReplaySource("https://cdn.example/v.mp4", "T", "video/mp4", "video", mapOf("Referer" to "r"), listOf("https://cdn.example/s.vtt"))
    private val playlist = CastReplaySource(
        "https://cdn.example/1.mp4", "P", null, null, emptyMap(), emptyList(),
        playlistPayloadJson = """{"items":[{"url":"https://cdn.example/1.mp4","start_position_ms":99},{"url":"https://cdn.example/2.mp4"},{"url":"https://cdn.example/3.mp4"}],"start_index":0}""",
    )

    @Test fun aResumeCarriesThePositionOnASingleItem() {
        val m = CastHistoryReplay(single, 178_000, 0).toReplayMedia("a", 5L)
        assertEquals("https://cdn.example/v.mp4", m.video.url)
        assertEquals(178_000L, m.video.startPositionMs)
        assertEquals(0, m.video.startIndex)
        assertEquals(listOf("https://cdn.example/s.vtt"), m.subtitleUrls)
        assertEquals(1, m.subtitleVideos.size)
    }

    @Test fun aPlaylistResumeCarriesItemAndPositionAndClearsStalePositions() {
        val m = CastHistoryReplay(playlist, 521_000, 2).toReplayMedia("p", 5L)
        assertEquals("playlist://history-p", m.video.url)
        assertEquals(2, m.video.startIndex)
        assertEquals(521_000L, m.video.startPositionMs)
        assertEquals(3, m.video.playlistPayload!!.size)
        assertNull(m.video.playlistPayload!![0].start_position_ms)
        assertEquals(emptyList<String>(), m.subtitleUrls)
    }

    @Test fun aTargetThatCannotSeekKeepsTheItemButDropsThePosition() {
        val m = CastHistoryReplay(playlist, 521_000, 1).toReplayMedia("p", 5L, seek = false)
        assertEquals(1, m.video.startIndex)
        assertEquals(0L, m.video.startPositionMs)
    }

    @Test fun anOutOfRangeIndexIsClamped() {
        assertEquals(2, CastHistoryReplay(playlist, 0, 9).toReplayMedia("p", 5L).video.startIndex)
    }
}
