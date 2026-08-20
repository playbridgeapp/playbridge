package com.playbridge.shared.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaNetworkPolicyTest {
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
}
