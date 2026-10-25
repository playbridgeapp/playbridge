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
    fun `connected receiver with native route is connected to the receiver`() {
        val status = status(CastSessionManager.Route.NativeTv, socketConnected = true, socketSecure = true)
        assertEquals(PlayOnStatus.State.CONNECTED, status.state)
        assertTrue(status.tvChosen)
        assertTrue(status.isConnected)
        assertEquals("PlayBridge · Connected", status.statusLine)
        assertEquals("Living-Room-Computer", status.deviceName)
    }

    @Test
    fun `this phone route is this phone even while an idle native link is open`() {
        for (socketConnected in listOf(false, true)) {
            val status = status(CastSessionManager.Route.ThisDevice, socketConnected = socketConnected, socketSecure = true)
            assertEquals(PlayOnStatus(), status)
            assertFalse(status.tvChosen)
            assertEquals("This phone", status.deviceName)
            assertEquals("Plays here · Tap to pick a TV", status.statusLine)
        }
    }

    @Test
    fun `plain native playback keeps its encryption warning`() {
        val status = status(CastSessionManager.Route.NativeTv, socketConnected = true, socketSecure = false)
        assertTrue(status.isConnected)
        assertTrue(status.unencrypted)
        assertEquals("PlayBridge · Not encrypted", status.statusLine)
    }

    @Test
    fun `a chosen TV without a socket shows its link state, never this phone`() {
        val route = CastSessionManager.Route.NativeTv
        assertEquals(PlayOnStatus.State.RECONNECTING, status(route, false, reconnecting = true, connecting = true).state)
        assertEquals(PlayOnStatus.State.CONNECTING, status(route, false, connecting = true).state)
        val offline = status(route, false)
        assertEquals(PlayOnStatus.State.OFFLINE, offline.state)
        assertTrue(offline.tvChosen)
        assertFalse(offline.isConnected)
        assertEquals("Living-Room-Computer", offline.deviceName)
        assertEquals("Not connected", offline.statusLine)
        assertEquals("Reconnecting…", status(route, false, reconnecting = true).statusLine)
        assertEquals("Connecting…", status(route, false, connecting = true).statusLine)
    }

    @Test
    fun `ready external routes are connected with their receiver label regardless of the native socket`() {
        for (protocol in listOf(CastProtocol.GOOGLE_CAST, CastProtocol.DLNA, CastProtocol.ROKU, CastProtocol.WEB_BROWSER)) {
            val external = receiver.copy(name = "Bedroom", protocol = protocol)
            for ((socketConnected, socketSecure) in listOf(false to false, true to false, true to true)) {
                val status = playOnStatus(
                    CastSessionManager.Route.External(external.endpointKey, external.name),
                    socketConnected, receiver, external, externalReady = true,
                    socketSecure = socketSecure,
                )
                assertEquals(PlayOnStatus.State.CONNECTED, status.state)
                assertFalse(status.unencrypted)
                assertEquals("${protocol.displayName} · Connected", status.statusLine)
                assertEquals("Bedroom", status.deviceName)
            }
        }
    }

    @Test
    fun `external route that is not ready ignores a connected native socket`() {
        val external = receiver.copy(protocol = CastProtocol.GOOGLE_CAST)
        val route = CastSessionManager.Route.External(external.endpointKey, external.name)
        val offline = playOnStatus(route, socketConnected = true, receiver, external, externalReady = false)
        assertEquals(PlayOnStatus.State.OFFLINE, offline.state)
        assertEquals("Google Cast", offline.receiver)
        val connecting = playOnStatus(route, socketConnected = true, receiver, external, externalReady = false, connecting = true)
        assertEquals(PlayOnStatus.State.CONNECTING, connecting.state)
    }

    @Test
    fun `missing device metadata has a sensible receiver name fallback`() {
        assertEquals("TV", playOnStatus(CastSessionManager.Route.NativeTv, true, null, null, false).deviceName)
        val externalRoute = CastSessionManager.Route.External(receiver.endpointKey, receiver.name)
        val external = playOnStatus(externalRoute, false, null, null, true)
        assertEquals("Living-Room-Computer", external.deviceName)
        assertEquals("TV · Connected", external.statusLine)
    }

    private fun status(
        route: CastSessionManager.Route,
        socketConnected: Boolean,
        socketSecure: Boolean = false,
        connecting: Boolean = false,
        reconnecting: Boolean = false,
    ) = playOnStatus(
        route, socketConnected, receiver, externalDevice = null, externalReady = false,
        socketSecure = socketSecure, connecting = connecting, reconnecting = reconnecting,
    )
}
