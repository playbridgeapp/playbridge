package com.playbridge.sender.cast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM unit tests (no Robolectric). Avoid android.util.Log / org.json which are
 * stubbed with "not mocked" RuntimeExceptions in pure unit tests.
 */
class JniUpstreamHttpClientTest {

    @Test
    fun filterHeaders_dropsBrowserAndHopByHop() {
        val filtered = JniUpstreamHttpClient.filterHeaders(
            mapOf(
                "User-Agent" to "UA",
                "Referer" to "https://example.com/",
                "Origin" to "https://evil.example",
                "Sec-Fetch-Mode" to "cors",
                "Host" to "cdn.example",
                "Accept-Encoding" to "gzip",
                "Cookie" to "a=1",
                "Range" to "bytes=0-1",
            ),
        )
        assertEquals("UA", filtered["User-Agent"])
        assertEquals("https://example.com/", filtered["Referer"])
        assertEquals("a=1", filtered["Cookie"])
        assertEquals("bytes=0-1", filtered["Range"])
        // Origin is forwarded (origin-protected same-site CDNs 403 without it), matching Desktop.
        assertEquals("https://evil.example", filtered["Origin"])
        assertFalse(filtered.containsKey("Sec-Fetch-Mode"))
        assertFalse(filtered.containsKey("Host"))
        assertFalse(filtered.containsKey("Accept-Encoding"))
    }
}
