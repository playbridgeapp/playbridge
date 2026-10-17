package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardClassifierTest {

    @Test
    fun `classify returns null for null, empty, or whitespace-only`() {
        assertNull(ClipboardClassifier.classify(null))
        assertNull(ClipboardClassifier.classify(""))
        assertNull(ClipboardClassifier.classify("   "))
        assertNull(ClipboardClassifier.classify("\n\t\r  \n"))
    }

    @Test
    fun `classify returns null for very long inputs`() {
        assertNull(ClipboardClassifier.classify("a".repeat(2001)))
        assertNull(ClipboardClassifier.classify("word ".repeat(500)))
    }

    @Test
    fun `classify recognizes URLs with scheme`() {
        assertEquals(
            ClipboardSuggestion.Link("https://playbridge.org"),
            ClipboardClassifier.classify("https://playbridge.org")
        )
        assertEquals(
            ClipboardSuggestion.Link("http://example.com/foo/bar"),
            ClipboardClassifier.classify("http://example.com/foo/bar")
        )
        assertEquals(
            ClipboardSuggestion.Link("about:blank"),
            ClipboardClassifier.classify("about:blank")
        )
        assertEquals(
            ClipboardSuggestion.Link("about:config"),
            ClipboardClassifier.classify("about:config")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://example.com/path?query=val#section"),
            ClipboardClassifier.classify("https://example.com/path?query=val#section")
        )
    }

    @Test
    fun `classify recognizes URLs without scheme and example dot com path`() {
        assertEquals(
            ClipboardSuggestion.Link("https://example.com"),
            ClipboardClassifier.classify("example.com")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://example.com/path"),
            ClipboardClassifier.classify("example.com/path")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://subdomain.example.co.uk/nested/page?id=123"),
            ClipboardClassifier.classify("subdomain.example.co.uk/nested/page?id=123")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://hello.world"),
            ClipboardClassifier.classify("hello.world")
        )
    }

    @Test
    fun `classify recognizes localhost with and without port and path`() {
        assertEquals(
            ClipboardSuggestion.Link("https://localhost"),
            ClipboardClassifier.classify("localhost")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://localhost:8080"),
            ClipboardClassifier.classify("localhost:8080")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://localhost:3000/api/status"),
            ClipboardClassifier.classify("localhost:3000/api/status")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://localhost/index.html"),
            ClipboardClassifier.classify("localhost/index.html")
        )
    }

    @Test
    fun `classify recognizes IP with and without port and path`() {
        assertEquals(
            ClipboardSuggestion.Link("https://192.168.1.1:8080"),
            ClipboardClassifier.classify("192.168.1.1:8080")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://10.0.0.1"),
            ClipboardClassifier.classify("10.0.0.1")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://127.0.0.1:3000/dashboard"),
            ClipboardClassifier.classify("127.0.0.1:3000/dashboard")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://172.16.0.1:80/stream"),
            ClipboardClassifier.classify("172.16.0.1:80/stream")
        )
    }

    @Test
    fun `classify recognizes magnet links`() {
        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74fed&dn=test"
        assertEquals(
            ClipboardSuggestion.Magnet(magnet),
            ClipboardClassifier.classify(magnet)
        )
        val upperMagnet = "MAGNET:?xt=urn:btih:test"
        assertEquals(
            ClipboardSuggestion.Magnet(upperMagnet),
            ClipboardClassifier.classify(upperMagnet)
        )
    }

    @Test
    fun `classify handles plain words and sentences as text`() {
        assertEquals(
            ClipboardSuggestion.Text("a few words"),
            ClipboardClassifier.classify("a few words")
        )
        assertEquals(
            ClipboardSuggestion.Text("hello.world is fun"),
            ClipboardClassifier.classify("hello.world is fun")
        )
        assertEquals(
            ClipboardSuggestion.Text("this is a full sentence with words"),
            ClipboardClassifier.classify("this is a full sentence with words")
        )
        assertEquals(
            ClipboardSuggestion.Text("singleword"),
            ClipboardClassifier.classify("singleword")
        )
    }

    @Test
    fun `classify strips trailing punctuation from URLs and magnets`() {
        assertEquals(
            ClipboardSuggestion.Link("https://example.com"),
            ClipboardClassifier.classify("https://example.com.")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://example.com/path"),
            ClipboardClassifier.classify("example.com/path,")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://example.com"),
            ClipboardClassifier.classify("example.com;")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://example.com/foo"),
            ClipboardClassifier.classify("https://example.com/foo!")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://localhost:8080"),
            ClipboardClassifier.classify("localhost:8080.")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://192.168.1.1:8080"),
            ClipboardClassifier.classify("192.168.1.1:8080,")
        )
        assertEquals(
            ClipboardSuggestion.Magnet("magnet:?xt=urn:btih:123"),
            ClipboardClassifier.classify("magnet:?xt=urn:btih:123.")
        )
    }

    @Test
    fun `classify collapses multi-line whitespace`() {
        assertEquals(
            ClipboardSuggestion.Text("hello world foo bar"),
            ClipboardClassifier.classify("  hello\nworld\r\nfoo  \t bar  ")
        )
        assertEquals(
            ClipboardSuggestion.Link("https://example.com"),
            ClipboardClassifier.classify("  https://example.com\n  ")
        )
    }

    @Test
    fun `classify caps long text query to 200 chars`() {
        val longWord = "a".repeat(300)
        assertEquals(
            ClipboardSuggestion.Text("a".repeat(200)),
            ClipboardClassifier.classify(longWord)
        )

        val longSentence = "word ".repeat(50) // 250 chars
        val expected = "word ".repeat(50).trim().take(200)
        assertEquals(
            ClipboardSuggestion.Text(expected),
            ClipboardClassifier.classify(longSentence)
        )
    }

    @Test
    fun `classify treats non-domain dotted tokens as text`() {
        assertEquals(
            ClipboardSuggestion.Text("v1.2"),
            ClipboardClassifier.classify("v1.2")
        )
        assertEquals(
            ClipboardSuggestion.Text("file.tar.gz"),
            ClipboardClassifier.classify("file.tar.gz")
        )
        assertEquals(
            ClipboardSuggestion.Text("archive.tar.bz2"),
            ClipboardClassifier.classify("archive.tar.bz2")
        )
        assertEquals(
            ClipboardSuggestion.Text("document.pdf"),
            ClipboardClassifier.classify("document.pdf")
        )
    }

    @Test
    fun `searchUrl builds google search url for queries and dotted tokens`() {
        assertEquals(
            "https://www.google.com/search?q=v1.2",
            searchUrl("v1.2")
        )
        assertEquals(
            "https://www.google.com/search?q=file.tar.gz",
            searchUrl("file.tar.gz")
        )
        assertEquals(
            "https://www.google.com/search?q=a+few+words",
            searchUrl("a few words")
        )
    }

    @Test
    fun `normalizeUrl handles links and text queries`() {
        assertEquals("about:blank", normalizeUrl(""))
        assertEquals("about:blank", normalizeUrl("   "))
        assertEquals("https://example.com", normalizeUrl("https://example.com"))
        assertEquals("http://example.com", normalizeUrl("http://example.com"))
        assertEquals("about:blank", normalizeUrl("about:blank"))
        assertEquals("https://example.com", normalizeUrl("example.com"))
        assertEquals("https://www.google.com/search?q=a+few+words", normalizeUrl("a few words"))
        assertEquals("https://www.google.com/search?q=hello.world+is+fun", normalizeUrl("hello.world is fun"))
    }
}
