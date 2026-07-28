package com.playbridge.sender.cast.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneProxyUrlsTest {
    @Test
    fun detectsOkHttpLocalProxyUrls() {
        assertTrue(
            PhoneProxyUrls.isOkHttpLocalProxyUrl(
                "http://192.168.1.49:38112/a1b2c3d4e5f67890.m3u8",
            ),
        )
        assertTrue(
            PhoneProxyUrls.isOkHttpLocalProxyUrl(
                "http://10.0.0.5:9000/deadbeefdeadbeef.mp4",
            ),
        )
    }

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
        assertTrue(
            PhoneProxyUrls.isAnyPhoneProxyUrl(
                "http://192.168.1.49:43541/s/bhljq8B7WDNsYxIRI1NOOrb--1pxQUOH/playlist.m3u8",
            ),
        )
    }

    @Test
    fun rejectsPublicOrigins() {
        assertFalse(
            PhoneProxyUrls.isOkHttpLocalProxyUrl(
                "https://cdn.example.com/a1b2c3d4e5f67890.m3u8",
            ),
        )
        assertFalse(
            PhoneProxyUrls.isRustEmbeddedProxyUrl(
                "https://cdn.example.com/s/session/playlist.m3u8",
            ),
        )
        assertFalse(
            PhoneProxyUrls.isAnyPhoneProxyUrl(
                "https://cdn.example.com/live/master.m3u8",
            ),
        )
    }
}
