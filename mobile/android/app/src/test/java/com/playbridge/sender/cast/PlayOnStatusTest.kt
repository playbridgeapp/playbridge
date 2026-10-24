package com.playbridge.sender.cast

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayOnStatusTest {
    private val receiver = TvDevice(
        ip = "192.0.2.1", port = 8765, token = "", name = "Living-Room-Computer.local",
    )

    @Test
    fun `connected receiver with native route is playing on the receiver`() {
        val status = status(CastSessionManager.Route.NativeTv, socketConnected = true)
        assertEquals(PlayOnStatus.State.PLAYING_ON, status.state)
        assertTrue(status.isConnected)
        assertTrue(status.isPlayingOn)
        assertEquals("Connected", status.tileSubtitle)
        assertEquals("Living-Room-Computer", status.deviceName)
    }

    @Test
    fun `connected receiver with this phone route is standing by`() {
        for (secure in listOf(false, true)) {
            val status = status(CastSessionManager.Route.ThisDevice, socketConnected = true, socketSecure = secure)
            assertEquals(PlayOnStatus.State.STANDING_BY, status.state)
            assertTrue(status.isConnected)
            assertFalse(status.isPlayingOn)
            assertFalse(status.secure)
            assertEquals(" · Standing by", status.pillSuffix)
            assertEquals("Standing by", status.tileSubtitle)
            assertEquals("Living-Room-Computer", status.deviceName)
        }
    }

    @Test
    fun `secure native playback keeps the green presentation without an encryption warning`() {
        val status = status(CastSessionManager.Route.NativeTv, socketConnected = true, socketSecure = true)
        assertTrue(status.isPlayingOn)
        assertTrue(status.secure)
        assertEquals("", status.pillSuffix)
    }

    @Test
    fun `plain native playback carries the amber presentation and encryption warning`() {
        val status = status(CastSessionManager.Route.NativeTv, socketConnected = true, socketSecure = false)
        assertTrue(status.isPlayingOn)
        assertFalse(status.secure)
        assertEquals(" · not encrypted", status.pillSuffix)
    }

    @Test
    fun `saved receiver without a socket is not connected for either base route`() {
        for (route in listOf(CastSessionManager.Route.ThisDevice, CastSessionManager.Route.NativeTv)) {
            val status = status(route, socketConnected = false)
            assertEquals(PlayOnStatus.State.NOT_CONNECTED, status.state)
            assertFalse(status.isConnected)
            assertFalse(status.isPlayingOn)
            assertEquals("", status.pillSuffix)
            assertEquals("Not connected", status.tileSubtitle)
        }
    }

    @Test
    fun `ready external routes use green without an encryption warning regardless of the native socket`() {
        for (protocol in listOf(CastProtocol.GOOGLE_CAST, CastProtocol.DLNA, CastProtocol.ROKU, CastProtocol.WEB_BROWSER)) {
            val external = receiver.copy(name = "Bedroom", protocol = protocol)
            for ((socketConnected, socketSecure) in listOf(false to false, true to false, true to true)) {
                val status = playOnStatus(
                    CastSessionManager.Route.External(external.endpointKey, external.name),
                    socketConnected, receiver, external, externalReady = true,
                    socketSecure = socketSecure,
                )
                assertEquals(PlayOnStatus.State.PLAYING_ON, status.state)
                assertTrue(status.isPlayingOn)
                assertTrue(status.secure)
                assertEquals("", status.pillSuffix)
                assertEquals("Connected", status.tileSubtitle)
                assertEquals("Bedroom", status.deviceName)
            }
        }
    }

    @Test
    fun `external route that is not ready stays disconnected even with a connected native socket`() {
        val external = receiver.copy(protocol = CastProtocol.GOOGLE_CAST)
        val status = playOnStatus(
            CastSessionManager.Route.External(external.endpointKey, external.name),
            socketConnected = true, receiver, external, externalReady = false,
        )
        assertEquals(PlayOnStatus(), status)
    }

    @Test
    fun `missing device metadata has a sensible receiver name fallback`() {
        assertEquals("TV", playOnStatus(CastSessionManager.Route.NativeTv, true, null, null, false).deviceName)
        val externalRoute = CastSessionManager.Route.External(receiver.endpointKey, receiver.name)
        assertEquals("Living-Room-Computer", playOnStatus(externalRoute, false, null, null, true).deviceName)
    }

    private fun status(route: CastSessionManager.Route, socketConnected: Boolean, socketSecure: Boolean = false) = playOnStatus(
        route, socketConnected, receiver, externalDevice = null, externalReady = false,
        socketSecure = socketSecure,
    )
}
