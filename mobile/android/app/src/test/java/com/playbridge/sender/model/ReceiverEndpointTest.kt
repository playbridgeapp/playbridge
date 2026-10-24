package com.playbridge.sender.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.playbridge.shared.protocol.protocolJson

class ReceiverEndpointTest {
    @Test
    fun `legacy UPnP endpoint keys match normalized discovery and map lookups`() {
        for (protocol in listOf(CastProtocol.DLNA, CastProtocol.ROKU)) {
            val current = EndpointKey(protocol, "uuid:Device-X")
            for (id in listOf("uuid:Device-X::upnp:rootdevice",
                "${protocol.name.lowercase()}:uuid:Device-X::urn:schemas-upnp-org:device:MediaRenderer:1")) {
                val legacy = protocolJson.decodeFromString<EndpointKey>(
                    """{"protocol":"${protocol.name}","stableId":"$id"}""",
                )
                assertEquals(current, legacy)
                assertEquals(current.hashCode(), legacy.hashCode())
                assertEquals(current.toString(), legacy.toString())
                assertEquals("saved", mapOf(legacy to "saved")[current])
            }
            assertEquals(false, current == EndpointKey(protocol, "uuid:Other"))
        }
    }

    @Test
    fun `UPnP compatibility leaves other protocols and IPv6 location identities intact`() {
        for (protocol in listOf(CastProtocol.PLAYBRIDGE, CastProtocol.GOOGLE_CAST, CastProtocol.WEB_BROWSER)) {
            assertEquals(false, EndpointKey(protocol, "id::suffix") == EndpointKey(protocol, "id"))
        }
        val location = "dlna:http://[2001:db8::1]/device.xml"
        assertEquals("dlna:$location", EndpointKey(CastProtocol.DLNA, location).toString())
    }

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
            ),
            playBridgeCredentials = PlayBridgeCredentials(token = "encrypted-token"),
        )

        val decoded = protocolJson.decodeFromString<SavedReceiverEndpoint>(
            protocolJson.encodeToString(SavedReceiverEndpoint.serializer(), saved),
        )

        assertEquals(saved, decoded)
    }
}
