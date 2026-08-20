package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload
import playbridge.SubtitleResource

class SubtitleResourcePolicyTest {
    @Test
    fun `page subtitle headers are scoped to the subtitle resource`() {
        val payload = PlayPayload(
            url = "https://video.example/master.m3u8",
            headers = mapOf("Authorization" to "video-secret"),
            subtitles = listOf(
                "https://video.example/same.vtt",
                "https://other.example/legacy.vtt",
            ),
            subtitle_resources = listOf(
                SubtitleResource(
                    url = "https://subs.example/explicit.vtt",
                    headers = mapOf("Authorization" to "subtitle-secret"),
                ),
            ),
            detected_by = "linked_page",
        )

        assertEquals(
            mapOf("Authorization" to "video-secret"),
            payload.headersForSubtitle("https://video.example/same.vtt"),
        )
        assertTrue(payload.headersForSubtitle("https://other.example/legacy.vtt").isEmpty())
        assertEquals(
            mapOf("Authorization" to "subtitle-secret"),
            payload.headersForSubtitle("https://subs.example/explicit.vtt"),
        )
    }

    @Test
    fun `non-page playback preserves legacy subtitle header behavior`() {
        val payload = PlayPayload(
            url = "https://video.example/movie.mkv",
            headers = mapOf("Referer" to "https://provider.example/"),
            subtitles = listOf("https://subs.example/movie.vtt"),
        )

        assertEquals(payload.headers, payload.headersForSubtitle(payload.subtitles.single()))
    }
}
