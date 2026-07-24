package com.playbridge.sender.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.playbridge.shared.protocol.protocolJson

class ReceiverEndpointTest {
    @Test
    fun `endpoint identity includes protocol`() {
        val native = EndpointKey(CastProtocol.PLAYBRIDGE, "tv-1")
        val dlna = EndpointKey(CastProtocol.DLNA, "tv-1")

        assertEquals(false, native == dlna)
    }

    @Test
    fun `legacy flags migrate to one resolved protocol`() {
        val legacy = TvDevice(
            ip = "192.168.1.10",
            port = 8060,
            token = "",
            name = "Living room",
            uuid = "roku-1",
            isRoku = true,
        )

        assertEquals(CastProtocol.ROKU, legacy.resolvedProtocol)
        assertEquals(CastProtocol.ROKU, legacy.toReceiverEndpoint().protocol)
    }

    @Test
    fun `credentials cannot attach to a third party endpoint`() {
        val endpoint = ReceiverEndpoint(
            key = EndpointKey(CastProtocol.GOOGLE_CAST, "cast-1"),
            name = "TV",
            addresses = listOf("192.168.1.11"),
            port = 8009,
        )

        assertThrows(IllegalArgumentException::class.java) {
            SavedReceiverEndpoint(endpoint, PlayBridgeCredentials(token = "secret"))
        }
    }

    @Test
    fun `saved endpoint round trip preserves protocol and every address`() {
        val saved = SavedReceiverEndpoint(
            endpoint = ReceiverEndpoint(
                key = EndpointKey(CastProtocol.PLAYBRIDGE, "native-1"),
                name = "Living room",
                addresses = listOf("192.168.1.20", "fe80::20%wlan0"),
                port = 8765,
                wssPort = 8766,
                descriptionUrl = "http://192.168.1.20/device.xml",
                controlUrl = "http://192.168.1.20/avtransport",
            ),
            playBridgeCredentials = PlayBridgeCredentials(token = "encrypted-token"),
        )

        val decoded = protocolJson.decodeFromString<SavedReceiverEndpoint>(
            protocolJson.encodeToString(SavedReceiverEndpoint.serializer(), saved),
        )

        assertEquals(saved, decoded)
    }
}
