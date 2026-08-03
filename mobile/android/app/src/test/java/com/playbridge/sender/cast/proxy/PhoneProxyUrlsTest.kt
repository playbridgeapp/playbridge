package com.playbridge.sender.cast.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneProxyUrlsTest {
    @Test
    fun detectsRustEmbeddedProxyUrls() {
        assertTrue(
            PhoneProxyUrls.isRustEmbeddedProxyUrl(
                "http://192.168.1.49:43541/s/bhljq8B7WDNsYxIRI1NOOrb--1pxQUOH/playlist.m3u8",
            ),
        )
        assertTrue(
            PhoneProxyUrls.isRustEmbeddedProxyUrl(
                "http://192.168.1.49:43541/media/token/file.mp4",
            ),
        )
    }

    @Test
    fun rejectsPublicOrigins() {
        assertFalse(
            PhoneProxyUrls.isRustEmbeddedProxyUrl(
                "https://cdn.example.com/s/session/playlist.m3u8",
            ),
        )
    }
}
