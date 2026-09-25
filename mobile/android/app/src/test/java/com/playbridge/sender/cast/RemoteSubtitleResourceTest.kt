package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteSubtitleResourceTest {
    @Test
    fun detectedSubtitleKeepsItsOwnHeadersAndLabel() {
        val subtitle = DetectedVideo(
            url = "https://subs.example/movie.vtt",
            contentType = "text/vtt",
            headers = mapOf("Origin" to "https://page.example", "Referer" to "https://page.example/watch"),
            originUrl = "https://other.example/watch",
            title = "English captions",
        )
        subtitle.subtitleLanguage = "English"
        val resource = subtitleResourceForDetected(subtitle)
        assertEquals(subtitle.url, resource.url)
        assertEquals(subtitle.headers, resource.headers)
        assertEquals("English captions", resource.label)
        assertEquals("English", resource.language)
    }

    @Test
    fun pageUrlSuppliesMissingRefererButNotOtherHeaders() {
        val subtitle = DetectedVideo(
            url = "https://subs.example/movie.vtt",
            contentType = "text/vtt",
            originUrl = "https://page.example/watch",
        )
        val resource = subtitleResourceForDetected(subtitle)
        assertEquals("https://page.example/watch", resource.headers["Referer"])
        assertFalse(resource.headers.containsKey("Cookie"))
    }
}
