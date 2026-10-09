package com.playbridge.sender.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalProxyHeaderPolicyTest {
    private val original = "https://media.example/master.m3u8"
    private val headers = mapOf(
        "Cookie" to "test-cookie", "Authorization" to "test-auth", "X-Custom" to "test-custom",
        "User-Agent" to "test-agent", "Accept" to "*/*", "Accept-Language" to "en",
        "Referer" to "https://user:pass@page.example:8443/watch?id=private#fragment",
        "Origin" to "https://page.example:8443/watch?id=private",
    )

    @Test fun sameOriginChildKeepsCredentialsAndFullBrowserContext() {
        assertEquals(headers, LocalProxyHeaderPolicy.forTarget(headers, "https://MEDIA.example:443/seg.ts", original))
    }

    @Test fun crossOriginChildKeepsOnlyRustSafeHeadersAndOriginContext() {
        assertEquals(mapOf("User-Agent" to "test-agent", "Accept" to "*/*", "Accept-Language" to "en",
            "Referer" to "https://page.example:8443/", "Origin" to "https://page.example:8443"),
            LocalProxyHeaderPolicy.forTarget(headers, "https://cdn.example/seg.ts", original))
    }

    @Test fun schemeAndPortChangesAreCrossOriginAndNestedChildrenKeepOriginalScope() {
        listOf("http://media.example/seg.ts", "https://media.example:8443/seg.ts",
            "https://cdn.example/nested/seg.ts").forEach { child ->
            val forwarded = LocalProxyHeaderPolicy.forTarget(headers, child, original)
            assertFalse(forwarded.containsKey("Cookie"))
            assertFalse(forwarded.containsKey("Authorization"))
            assertFalse(forwarded.containsKey("X-Custom"))
        }
        assertEquals(headers, LocalProxyHeaderPolicy.forTarget(headers, "https://media.example/return.ts", original))
    }

    @Test fun crossOriginInvalidContextIsDroppedAndIpv6OriginIsSerialized() {
        assertEquals(emptyMap<String, String>(), LocalProxyHeaderPolicy.forTarget(
            mapOf("Referer" to "not a URL", "Origin" to "file:///private"), "https://cdn.example", original))
        assertEquals(mapOf("Referer" to "http://[::1]/"), LocalProxyHeaderPolicy.forTarget(
            mapOf("Referer" to "http://[::1]:80/private?query=private"), "https://cdn.example", original))
    }

    @Test fun hopHeadersAreSkippedEvenAtOriginalOrigin() {
        val hopHeaders = mapOf("Host" to "test", "Connection" to "close", "Content-Length" to "1",
            "Accept-Encoding" to "gzip", "Range" to "bytes=0-1", ":authority" to "test")
        assertEquals(headers, LocalProxyHeaderPolicy.forTarget(headers + hopHeaders, original, original))
    }

    @Test fun minimalRetryRetainsOriginAndStillAppliesOriginScope() {
        val minimal = LocalProxyHeaderPolicy.minimalRetryHeaders(headers)
        assertEquals(headers["Origin"], minimal["Origin"])
        val forwarded = LocalProxyHeaderPolicy.forTarget(minimal, "https://cdn.example", original)
        assertEquals("https://page.example:8443", forwarded["Origin"])
        assertFalse(forwarded.containsKey("Cookie"))
    }
}
