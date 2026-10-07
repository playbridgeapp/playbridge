package com.playbridge.player.userscript

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPatternTest {
    @Test
    fun `scheme star matches http and https only`() {
        val pattern = MatchPattern.parse("*://example.com/*")!!

        assertTrue(pattern.matches("https://example.com/"))
        assertTrue(pattern.matches("http://example.com/foo"))
        assertFalse(pattern.matches("ftp://example.com/foo"))
        assertFalse(pattern.matches("file://example.com/foo"))
    }

    @Test
    fun `explicit scheme does not match the other`() {
        val pattern = MatchPattern.parse("https://example.com/*")!!

        assertTrue(pattern.matches("https://example.com/a"))
        assertFalse(pattern.matches("http://example.com/a"))
    }

    @Test
    fun `wildcard subdomain matches the domain and its subdomains only`() {
        val pattern = MatchPattern.parse("*://*.example.com/*")!!

        assertTrue(pattern.matches("https://example.com/"))
        assertTrue(pattern.matches("https://www.example.com/foo"))
        assertTrue(pattern.matches("http://a.b.example.com/x"))
        assertFalse(pattern.matches("https://notexample.com/"))
        assertFalse(pattern.matches("https://example.org/"))
        assertFalse(pattern.matches("https://example.com.evil.com/"))
        assertFalse(pattern.matches("https://evil.com/example.com"))
        assertFalse(pattern.matches("https://www.notexample.com/"))
    }

    @Test
    fun `exact host does not match a subdomain`() {
        val pattern = MatchPattern.parse("https://example.com/*")!!

        assertTrue(pattern.matches("https://example.com/foo"))
        assertTrue(pattern.matches("https://EXAMPLE.com/foo"))
        assertFalse(pattern.matches("https://www.example.com/foo"))
    }

    @Test
    fun `path glob matches across segments and ignores query and port`() {
        val prefix = MatchPattern.parse("https://example.com/foo*")!!
        val nested = MatchPattern.parse("*://example.com/*/bar")!!
        val dir = MatchPattern.parse("https://example.com/foo/*")!!

        assertTrue(prefix.matches("https://example.com/foo"))
        assertTrue(prefix.matches("https://example.com/foobar"))
        assertTrue(prefix.matches("https://example.com/foo/bar"))
        assertFalse(prefix.matches("https://example.com/bar"))

        assertTrue(nested.matches("https://example.com/a/bar"))
        assertTrue(nested.matches("http://example.com/a/b/bar"))
        assertFalse(nested.matches("https://example.com/a/bar/extra"))

        assertTrue(dir.matches("https://example.com/foo/bar"))
        assertTrue(dir.matches("https://example.com/foo/"))
        assertFalse(dir.matches("https://example.com/foo"))
        assertFalse(dir.matches("https://example.com/bar/foo/"))

        assertTrue(prefix.matches("https://example.com:8443/foo?x=1#frag"))
        assertTrue(MatchPattern.parse("*://example.com/path*")!!.matches("https://user:pass@example.com/path?q=1"))
    }

    @Test
    fun `star host matches any host including an ip`() {
        val pattern = MatchPattern.parse("*://*/*")!!

        assertTrue(pattern.matches("https://example.com/a/b"))
        assertTrue(pattern.matches("http://192.168.1.1/foo"))
        assertFalse(pattern.matches("ftp://192.168.1.1/foo"))
    }

    @Test
    fun `all_urls matches http and https pages`() {
        val pattern = MatchPattern.parse("<all_urls>")!!

        assertTrue(pattern.matches("https://example.com/anything"))
        assertTrue(pattern.matches("http://192.168.0.2/"))
        assertFalse(pattern.matches("ftp://example.com/file"))
    }

    @Test
    fun `rejects patterns that are not chrome match patterns`() {
        assertNull(MatchPattern.parse("example.com/*"))
        assertNull(MatchPattern.parse("http://*example.com/*"))
        assertNull(MatchPattern.parse("http://foo.*.bar/*"))
        assertNull(MatchPattern.parse("*://*.com/*"))
        assertNull(MatchPattern.parse("*://example.com"))
        assertNull(MatchPattern.parse("ftp://example.com/*"))
        assertNull(MatchPattern.parse(""))
    }
}
