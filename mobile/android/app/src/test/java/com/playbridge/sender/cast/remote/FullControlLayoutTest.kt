package com.playbridge.sender.cast.remote

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullControlLayoutTest {
    private val endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "tv")

    @Test fun wholeDeviceShowsPadInIdleAndPlayerAndBothModesInBrowser() {
        val idle = projected(RemoteContext.Idle)
        val idleLayout = resolveRemoteLayout(idle, RemoteViewport())
        assertEquals(listOf(RemoteInputMode.Pad), idleLayout.modes)
        assertEquals(RemoteInputMode.Pad, idleLayout.selectedMode)
        assertTrue(idleLayout.nav.home.enabled)
        assertTrue(idleLayout.nav.keys.enabled)
        assertTrue(idleLayout.nav.back.enabled)
        assertEquals(ControlScope.WholeDevice, idle.capabilities.keyScope)

        val player = projected(RemoteContext.Player)
        val playerLayout = resolveRemoteLayout(player, RemoteViewport())
        assertEquals(listOf(RemoteInputMode.Pad), playerLayout.modes)
        assertFalse(playerLayout.modes.contains(RemoteInputMode.Pointer))
        assertEquals(RemoteInputPresentation.Full, playerLayout.inputPresentation)

        val browser = projected(RemoteContext.Browser)
        val browserLayout = resolveRemoteLayout(browser, RemoteViewport())
        assertEquals(listOf(RemoteInputMode.Pad, RemoteInputMode.Pointer), browserLayout.modes)
        assertEquals(RemoteInputMode.Pointer, browserLayout.selectedMode)
        assertTrue(browserLayout.showModeRow)
    }

    @Test fun disconnectRestoresTheReceiverCapabilities() {
        val base = nativeRemoteCapabilities(RemoteProfile.AndroidTv, RemoteContext.Idle, RemoteMediaKind.Video)
        val reducer = RemoteStateReducer { 0L }
        reducer.observe(RemoteReceiverSnapshot(projected(RemoteContext.Idle, baseOverride = base, connected = false).copy(capabilities = base, fullControl = null)))
        reducer.applyChannel(channel(connected = true))
        assertEquals(ControlScope.WholeDevice, reducer.state.capabilities.keyScope)
        assertEquals(3, reducer.state.capabilities.volume.level)
        reducer.applyChannel(channel(connected = false))
        assertEquals(base.keyScope, reducer.state.capabilities.keyScope)
        assertEquals(null, reducer.state.capabilities.volume.level)
        assertEquals(false, reducer.state.fullControl?.connected)
    }

    private fun projected(context: RemoteContext, baseOverride: RemoteCapabilities? = null, connected: Boolean = true): RemoteUiState {
        val base = baseOverride ?: nativeRemoteCapabilities(RemoteProfile.AndroidTv, context, RemoteMediaKind.Video)
        return projectFullControl(
            RemoteUiState(
                sessionId = RemoteSessionId(endpoint, 1),
                receiver = RemoteReceiver(endpoint, "TV", RemoteProfile.AndroidTv),
                connection = RemoteConnection.Connected("TV"),
                context = context,
                capabilities = base,
                playback = if (context == RemoteContext.Idle) null else RemotePlayback(mediaKind = RemoteMediaKind.Video),
            ),
            base,
            channel(connected),
        )
    }

    private fun channel(connected: Boolean) = TvRemoteChannelState(
        matchedEndpoint = endpoint,
        physicalDeviceId = "C0:E5:DA:D8:C3:5F",
        paired = true,
        connected = connected,
        keys = setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok, RemoteKey.Back, RemoteKey.Home, RemoteKey.Enter, RemoteKey.Delete, RemoteKey.Menu),
        volume = VolumeCapabilities(adjust = Support.Supported, level = 3, maximum = 100, muted = false),
        text = TextCapabilities(Support.Supported, Support.Supported, Support.Supported),
        powerOn = true,
        fullControlAvailable = true,
    )
}
