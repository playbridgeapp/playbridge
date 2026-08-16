package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageCastConsentStoreTest {
    @Test
    fun `normalizes a web origin for persisted consent`() {
        assertEquals(
            "https://video.example.com",
            PageCastConsentStore.normalizeOrigin("HTTPS://video.example.com:443/"),
        )
        assertEquals(
            "http://video.example.com:8080",
            PageCastConsentStore.normalizeOrigin("http://video.example.com:8080"),
        )
    }

    @Test
    fun `rejects paths credentials and non web origins`() {
        assertNull(PageCastConsentStore.normalizeOrigin("https://video.example.com/watch"))
        assertNull(PageCastConsentStore.normalizeOrigin("https://user@video.example.com"))
        assertNull(PageCastConsentStore.normalizeOrigin("file:///sdcard/video.mp4"))
    }

    @Test
    fun `display name omits the scheme`() {
        assertEquals("video.example.com", PageCastConsentStore.displayName("https://video.example.com"))
        assertEquals("video.example.com:8080", PageCastConsentStore.displayName("http://video.example.com:8080"))
    }

    @Test
    fun `display name reveals unicode and canonical punycode`() {
        assertEquals(
            "bücher.example (xn--bcher-kva.example)",
            PageCastConsentStore.displayName("https://xn--bcher-kva.example"),
        )
    }

    @Test
    fun `connection label does not expose a scheme`() {
        assertEquals("Secure site", PageCastConsentStore.connectionLabel("https://video.example.com"))
        assertEquals("Not secure", PageCastConsentStore.connectionLabel("http://video.example.com"))
    }
}
