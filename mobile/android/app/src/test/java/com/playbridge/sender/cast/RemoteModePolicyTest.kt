package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteModePolicyTest {

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
