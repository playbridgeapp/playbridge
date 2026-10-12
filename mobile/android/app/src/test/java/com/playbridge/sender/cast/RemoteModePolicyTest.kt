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
                    assertEquals(if (context == RemoteContext.IDLE) listOf(RemoteMode.CONTEXT) else emptyList<RemoteMode>(), availableRemoteModes(context, false, input.remote, input.touchpad))
                }
                val desktop = nativeRemoteInputAvailability(listOf("internal_mpv"), setOf("subtitle_resource_add_v1"), image)
                val expected = when {
                    context == RemoteContext.IDLE -> listOf(RemoteMode.CONTEXT)
                    image -> listOf(RemoteMode.TOUCHPAD)
                    else -> emptyList()
                }
                assertEquals(expected, availableRemoteModes(context, false, desktop.remote, desktop.touchpad))
            }
        }
    }

    @Test fun `idle uses an available pad route or idle content`() {
        assertEquals(listOf(RemoteMode.CONTEXT), availableRemoteModes(RemoteContext.IDLE, false, true))
        assertEquals(listOf(RemoteMode.CONTEXT), availableRemoteModes(RemoteContext.IDLE, false, false))
    }
    @Test fun `Roku idle keeps its working pad`() {
        assertEquals(listOf(RemoteMode.DPAD), availableRemoteModes(RemoteContext.IDLE, true, true))
        assertEquals(listOf(RemoteMode.CONTEXT), availableRemoteModes(RemoteContext.IDLE, true, false))
    }


    @Test
    fun `native browser is pointer only until whole-device control`() {
        assertEquals(
            listOf(RemoteMode.TOUCHPAD),
            availableRemoteModes(
                remoteContext = RemoteContext.BROWSER,
                externalMode = false,
                supportsRemote = true,
            ),
        )
    }

    @Test
    fun `native player video has no input until whole-device control`() {
        assertEquals(
            emptyList<RemoteMode>(),
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
