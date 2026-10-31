package com.playbridge.shared.network

import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaNetworkPolicyTest {
    @Test
    fun sharedDestinationVectors() {
        // Preserve stricter existing checks until the shared policy replaces this copy.
        val stricterCases = mapOf(
            "IDNA unicode" to "java.net.URI does not expose a Unicode hostname",
            "long URL" to "Kotlin caps URLs at 8192 characters",
        )
        val seenDifferences = mutableSetOf<String>()
        val resource = checkNotNull(javaClass.getResourceAsStream("/vectors.json"))
        val vectors = resource.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        assertTrue(vectors.isNotEmpty())
        for (element in vectors) {
            val vector = element.jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            val url = vector.getValue("url").jsonPrimitive.content
            val grants = vector.getValue("allowed_private_origins").jsonArray.map { it.jsonPrimitive.content }
            val rustAllows = vector.getValue("expected").jsonPrimitive.content == "allow"
            val syntaxAllowed = MediaNetworkPolicy.isAllowedUrlSyntax(url, grants)
            val addresses = vector["dns_answers"]?.jsonArray?.map {
                InetAddress.getByName(it.jsonPrimitive.content)
            } ?: if (syntaxAllowed) {
                // Absent answers mean an IP-literal URL; never resolve a fixture hostname.
                val host = URI(url).host.trim('[', ']')
                check(host.contains(':') || host.all { it.isDigit() || it == '.' })
                listOf(InetAddress.getByName(host))
            } else emptyList()
            val addressesAllowed = MediaNetworkPolicy.areAllowedAddresses(url, addresses, grants)
            val allowed = syntaxAllowed && addressesAllowed
            val difference = stricterCases[name]
            if (difference != null) {
                assertTrue(rustAllows, "$name must be an allow-to-deny difference")
                seenDifferences += name
                assertFalse(allowed, "$name: $difference")
            } else {
                assertEquals(rustAllows, allowed, "$name: ${vector.getValue("note").jsonPrimitive.content}")
            }
        }
        assertEquals(stricterCases.keys, seenDifferences)
    }

    @Test
    fun dnsFreeValidationAcceptsPublicHostnamesAndRequiresExactLocalOrigin() {
        assertTrue(MediaNetworkPolicy.isAllowedUrlSyntax("https://cdn.example/video.m3u8", emptySet()))
        assertFalse(MediaNetworkPolicy.isAllowedUrlSyntax("http://media.local/video", emptySet()))
        assertTrue(
            MediaNetworkPolicy.isAllowedUrlSyntax(
                "http://media.local/video",
                setOf("http://media.local"),
            ),
        )
        assertFalse(
            MediaNetworkPolicy.isAllowedUrlSyntax(
                "https://user:secret@cdn.example/video",
                emptySet(),
            ),
        )
    }

    @Test
    fun rejectsPrivateAndLocalDestinationsWithoutGrant() {
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://127.0.0.1/video", emptySet()))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://192.168.1.20/video", emptySet()))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://[::1]/video", emptySet()))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://media.local/video", emptySet()))
    }

    @Test
    fun permitsOnlyTheExactPrivateOrigin() {
        val grants = setOf("http://192.168.1.20")
        assertTrue(MediaNetworkPolicy.isAllowedDestination("http://192.168.1.20/video", grants))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://192.168.1.20:8080/video", grants))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("https://192.168.1.20/video", grants))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://192.168.1.21/video", grants))
    }

    @Test
    fun dangerousDestinationsRemainBlockedEvenIfListed() {
        assertNull(MediaNetworkPolicy.privateOrigin("http://169.254.169.254/metadata"))
        assertNull(MediaNetworkPolicy.privateOrigin("http://127.0.0.1/video"))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://127.0.0.1/video", setOf("http://127.0.0.1")))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://169.254.169.254/metadata", setOf("http://169.254.169.254")))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://[::1]/video", setOf("http://[::1]")))
        assertFalse(MediaNetworkPolicy.isAllowedUrlSyntax("http://localhost/video", setOf("http://localhost")))
    }

    @Test
    fun cgnatTailscaleRangeIsPrivateLanRequiringAnExactGrant() {
        // Matches Rust classify_address: 100.64.0.0/10 is PrivateLan, not forbidden.
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://100.64.0.5/video", emptySet()))
        assertTrue(MediaNetworkPolicy.isAllowedDestination("http://100.64.0.5/video", setOf("http://100.64.0.5")))
        assertTrue(MediaNetworkPolicy.isAllowedDestination("http://100.127.255.254/v", setOf("http://100.127.255.254")))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://100.64.0.6/video", setOf("http://100.64.0.5")))
        assertEquals("http://100.64.0.5:80", MediaNetworkPolicy.privateOrigin("http://100.64.0.5/video"))
        assertTrue(MediaNetworkPolicy.targetsPrivateNetwork("http://100.100.1.1/video"))
        // Range edges are public.
        assertTrue(MediaNetworkPolicy.isAllowedDestination("http://100.63.255.255/video", emptySet()))
        assertTrue(MediaNetworkPolicy.isAllowedDestination("http://100.128.0.1/video", emptySet()))
        assertNull(MediaNetworkPolicy.privateOrigin("http://100.128.0.1/video"))
    }

    @Test
    fun rejectsMixedDnsAnswersBeforeTheHttpClientUsesThem() {
        val addresses = listOf(
            InetAddress.getByName("93.184.216.34"),
            InetAddress.getByName("127.0.0.1"),
        )
        assertFalse(MediaNetworkPolicy.areAllowedAddresses("https://media.example/video", addresses, emptySet()))
        assertFalse(
            MediaNetworkPolicy.areAllowedAddresses(
                "https://media.example/video",
                addresses,
                setOf("https://media.example"),
            ),
        )
    }

    @Test
    fun normalizesAndBoundsPrivateOriginLists() {
        assertEquals(
            setOf("http://media.local:80", "https://192.168.1.20:8443"),
            MediaNetworkPolicy.normalizePrivateOrigins(
                listOf("http://media.local", "https://192.168.1.20:8443/"),
            ),
        )
        assertNull(MediaNetworkPolicy.normalizePrivateOrigins(listOf("http://*.local")))
        assertNull(
            MediaNetworkPolicy.normalizePrivateOrigins(
                (1..17).map { "http://192.168.1.$it" },
            ),
        )
    }

    @Test
    fun comparesNormalizedOrigins() {
        assertTrue(MediaNetworkPolicy.sameOrigin("https://example.com/a", "https://example.com:443/b"))
        assertFalse(MediaNetworkPolicy.sameOrigin("https://example.com/a", "https://cdn.example.com/a"))
    }

    @Test
    fun permitsOnlyTrustedLocalProxyStreamUrlsOnLoopback() {
        val proxyOrigin = "http://127.0.0.1:41920"
        val loopback = InetAddress.getByName("127.0.0.1")

        assertEquals(proxyOrigin, MediaNetworkPolicy.normalizeTrustedLocalProxyOrigin(proxyOrigin))
        assertNull(MediaNetworkPolicy.normalizeTrustedLocalProxyOrigin("https://127.0.0.1:41920"))
        assertNull(MediaNetworkPolicy.normalizeTrustedLocalProxyOrigin("http://localhost:41920"))
        assertNull(MediaNetworkPolicy.normalizeTrustedLocalProxyOrigin("http://127.0.0.1"))

        val validStreamUrl = "$proxyOrigin/s/session-1/index.m3u8"
        assertTrue(MediaNetworkPolicy.isTrustedLocalProxyUrl(validStreamUrl, proxyOrigin))
        assertTrue(
            MediaNetworkPolicy.isAllowedUrlSyntaxOrTrustedProxy(
                validStreamUrl,
                emptySet(),
                proxyOrigin,
            ),
        )
        assertTrue(
            MediaNetworkPolicy.areAllowedAddressesForHostOrTrustedProxy(
                "127.0.0.1",
                listOf(loopback),
                emptySet(),
                proxyOrigin,
            ),
        )
        assertTrue(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                validStreamUrl,
                loopback,
                emptySet(),
                proxyOrigin,
            ),
        )

        // Untrusted ports, routes, or missing proxy origin remain blocked.
        val wrongPortUrl = "http://127.0.0.1:8080/s/session-1/index.m3u8"
        val adminRouteUrl = "$proxyOrigin/register"
        assertFalse(MediaNetworkPolicy.isTrustedLocalProxyUrl(wrongPortUrl, proxyOrigin))
        assertFalse(MediaNetworkPolicy.isTrustedLocalProxyUrl(adminRouteUrl, proxyOrigin))
        assertFalse(
            MediaNetworkPolicy.isAllowedUrlSyntaxOrTrustedProxy(
                wrongPortUrl,
                emptySet(),
                proxyOrigin,
            ),
        )
        assertFalse(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                wrongPortUrl,
                loopback,
                emptySet(),
                proxyOrigin,
            ),
        )
        assertFalse(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                adminRouteUrl,
                loopback,
                emptySet(),
                proxyOrigin,
            ),
        )
        assertFalse(
            MediaNetworkPolicy.areAllowedAddressesForHostOrTrustedProxy(
                "127.0.0.1",
                listOf(loopback),
                emptySet(),
                null,
            ),
        )
    }
}
