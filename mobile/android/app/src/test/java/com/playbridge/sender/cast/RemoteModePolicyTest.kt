package com.playbridge.sender.cast

import com.playbridge.sender.cast.remote.nativeRemoteInputAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteModePolicyTest {
    @Test fun `non Android receivers expose only implemented pointer routes`() {
        for (context in RemoteContext.entries) {
            for (image in listOf(false, true)) {
                for (players in listOf(listOf("avplayer", "mpv"), listOf("internal_mpv"), emptyList())) {
                    val input = nativeRemoteInputAvailability(players, emptySet(), image)
                    assertEquals(listOf(RemoteMode.CONTEXT), availableRemoteModes(context, false, input.remote, input.touchpad))
                }
                val desktop = nativeRemoteInputAvailability(listOf("internal_mpv"), setOf("subtitle_resource_add_v1"), image)
                val expected = if (image && context != RemoteContext.IDLE) listOf(RemoteMode.CONTEXT, RemoteMode.TOUCHPAD) else listOf(RemoteMode.CONTEXT)
                assertEquals(expected, availableRemoteModes(context, false, desktop.remote, desktop.touchpad))
            }
        }
    }

    @Test fun `native idle exposes no dead pad or pointer`() {
        assertEquals(listOf(RemoteMode.CONTEXT), availableRemoteModes(RemoteContext.IDLE, false, true))
    }
    @Test fun `Roku idle keeps its working pad`() {
        assertEquals(listOf(RemoteMode.CONTEXT, RemoteMode.DPAD), availableRemoteModes(RemoteContext.IDLE, true, true))
        assertEquals(listOf(RemoteMode.CONTEXT), availableRemoteModes(RemoteContext.IDLE, true, false))
    }


    @Test
    fun `native browser includes keyboard mode`() {
        assertEquals(
            listOf(
                RemoteMode.CONTEXT,
                RemoteMode.DPAD,
                RemoteMode.TOUCHPAD,
                RemoteMode.KEYBOARD,
            ),
            availableRemoteModes(
                remoteContext = RemoteContext.BROWSER,
                externalMode = false,
                supportsRemote = true,
            ),
        )
    }

    @Test
    fun `native player excludes keyboard mode`() {
        assertEquals(
            listOf(
                RemoteMode.CONTEXT,
                RemoteMode.DPAD,
                RemoteMode.TOUCHPAD,
            ),
            availableRemoteModes(
                remoteContext = RemoteContext.PLAYER,
                externalMode = false,
                supportsRemote = true,
            ),
        )
    }

    @Test
    fun `external receiver excludes native touch and keyboard modes`() {
        assertEquals(
            listOf(
                RemoteMode.CONTEXT,
                RemoteMode.DPAD,
            ),
            availableRemoteModes(
                remoteContext = RemoteContext.BROWSER,
                externalMode = true,
                supportsRemote = true,
            ),
        )
    }
}
