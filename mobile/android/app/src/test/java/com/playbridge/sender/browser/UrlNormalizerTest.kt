package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun `addresses use matching local or public schemes`() {
        val cases = mapOf(
            "localhost:8080" to "http://localhost:8080",
            "localhost/path" to "http://localhost/path",
            "192.168.1.22:8080/x" to "http://192.168.1.22:8080/x",
            "HTTP://Example.com" to "HTTP://Example.com",
            "example.com" to "https://example.com",
            "nas:5000" to "http://nas:5000",
            "about:blank" to "about:blank",
            "10.0.0.1" to "http://10.0.0.1",
            "[::1]:8080" to "http://[::1]:8080",
            "[fe80::1]/x" to "http://[fe80::1]/x",
            "[fc00::1]" to "http://[fc00::1]",
            "[fd00::1]" to "http://[fd00::1]",
            "[2001:db8::1]" to "https://[2001:db8::1]",
            "myhost.lan:8096" to "http://myhost.lan:8096",
            "NAS.LOCAL" to "http://NAS.LOCAL",
            "8.8.8.8" to "http://8.8.8.8",
            "172.16.0.1" to "http://172.16.0.1",
            "169.254.1.2" to "http://169.254.1.2",
            "example.com:8443/path" to "https://example.com:8443/path",
            "  localhost  " to "http://localhost",
            "[fc::1]" to "https://[fc::1]",
            "[fe8::1]" to "https://[fe8::1]",
            "[0:0:0:0:0:0:0:1]" to "http://[0:0:0:0:0:0:0:1]",
            "mailto:test@example.test" to "mailto:test@example.test",
            "file:///tmp/test" to "file:///tmp/test",
            "ABOUT:blank" to "ABOUT:blank"
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, normalizeUrl(input)) }
    }

    @Test
    fun `empty input and searches retain existing behavior`() {
        assertEquals("about:blank", normalizeUrl("  "))
        assertEquals(searchUrl("hello world"), normalizeUrl("hello world"))
        assertEquals(searchUrl("hello\tworld"), normalizeUrl("hello\tworld"))
        assertEquals(searchUrl("hello.world is fun"), normalizeUrl("hello.world is fun"))
        assertEquals(searchUrl("JavaScript:alert(document.domain)"), normalizeUrl("JavaScript:alert(document.domain)"))
    }
}
