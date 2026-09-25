package com.playbridge.player.player

import com.playbridge.shared.protocol.encodeSubtitleResourceJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import playbridge.SubtitleResource

class LateSubtitleResourceTest {
    @Test
    fun retainsScopedHeadersAndMetadata() {
        val resource = SubtitleResource(
            url = "https://subs.example/movie.vtt",
            headers = mapOf("Origin" to "https://page.example", "User-Agent" to "PlayBridge"),
            label = "English",
            language = "en",
        )
        assertEquals(resource, decodeLateSubtitleResource(encodeSubtitleResourceJson(resource)))
    }

    @Test
    fun rejectsLocalFilesAndCredentialsInUrl() {
        assertNull(decodeLateSubtitleResource(encodeSubtitleResourceJson(
            SubtitleResource(url = "file:///tmp/movie.vtt"),
        )))
        assertNull(decodeLateSubtitleResource(encodeSubtitleResourceJson(
            SubtitleResource(url = "https://user:secret@subs.example/movie.vtt"),
        )))
    }

    @Test
    fun rejectsHeaderInjection() {
        assertNull(decodeLateSubtitleResource(encodeSubtitleResourceJson(
            SubtitleResource(
                url = "https://subs.example/movie.vtt",
                headers = mapOf("Origin" to "https://page.example\r\nHost: other.example"),
            ),
        )))
    }
}
