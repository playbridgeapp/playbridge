package com.playbridge.shared.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaNetworkPolicyTest {
    @Test
    fun dnsFreeValidationAcceptsPublicHostnamesAndRejectsLocalNames() {
        assertTrue(MediaNetworkPolicy.isAllowedUrlSyntax("https://cdn.example/video.m3u8", false))
        assertFalse(MediaNetworkPolicy.isAllowedUrlSyntax("http://media.local/video", false))
        assertFalse(MediaNetworkPolicy.isAllowedUrlSyntax("https://user:secret@cdn.example/video", false))
    }

    @Test
    fun rejectsPrivateAndLocalDestinationsWithoutGrant() {
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://127.0.0.1/video", false))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://192.168.1.20/video", false))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://[::1]/video", false))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://media.local/video", false))
    }

    @Test
    fun permitsPrivateDestinationWithGrant() {
        assertTrue(MediaNetworkPolicy.isAllowedDestination("http://192.168.1.20/video", true))
    }

    @Test
    fun dangerousDestinationsRemainBlockedWithGrant() {
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://127.0.0.1/video", true))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://169.254.169.254/metadata", true))
        assertFalse(MediaNetworkPolicy.isAllowedDestination("http://[::1]/video", true))
        assertFalse(MediaNetworkPolicy.isAllowedUrlSyntax("http://localhost/video", true))
    }

    @Test
    fun rejectsMixedDnsAnswersBeforeTheHttpClientUsesThem() {
        val addresses = listOf(
            InetAddress.getByName("93.184.216.34"),
            InetAddress.getByName("127.0.0.1"),
        )
        assertFalse(MediaNetworkPolicy.areAllowedAddresses("media.example", addresses, false))
        assertFalse(MediaNetworkPolicy.areAllowedAddresses("media.example", addresses, true))
    }

    @Test
    fun comparesNormalizedOrigins() {
        assertTrue(MediaNetworkPolicy.sameOrigin("https://example.com/a", "https://example.com:443/b"))
        assertFalse(MediaNetworkPolicy.sameOrigin("https://example.com/a", "https://cdn.example.com/a"))
    }
}
