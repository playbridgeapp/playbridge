package com.playbridge.player.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionSenderNamesTest {
    @Test
    fun `pairing approval keeps the handshake name on the command connection id`() {
        val names = ConnectionSenderNames()
        val socket = Any()
        val commandConnectionId = names.open(socket)

        // The phone stays on this socket after pairing_approved. There is no auth frame,
        // so the only name available is the one from pairing_commit.
        names.remember(
            socket,
            authenticatedSenderName(handshakeDeviceName = "SM-S936W", tokenOwnerName = null),
        )

        assertEquals("SM-S936W", names.nameFor(commandConnectionId))
    }

    @Test
    fun `skipping the handshake name is what left the prompt sender null`() {
        val names = ConnectionSenderNames()
        val commandConnectionId = names.open(Any())

        // registerAuthed() on pairing success used to stop here. The store has the
        // phone name, but this socket never went through handleAuth, so the prompt saw null.
        assertNull(names.nameFor(commandConnectionId))
        assertNull(authenticatedSenderName(handshakeDeviceName = "  ", tokenOwnerName = null))
    }

    @Test
    fun `token auth records the paired name on the same id commands use`() {
        val names = ConnectionSenderNames()
        val socket = Any()
        val commandConnectionId = names.open(socket)

        names.remember(
            socket,
            authenticatedSenderName(handshakeDeviceName = null, tokenOwnerName = "SM-S936W"),
        )

        assertEquals("SM-S936W", names.nameFor(commandConnectionId))
    }

    @Test
    fun `re-registration drops the previous name until the new socket authenticates`() {
        val names = ConnectionSenderNames()
        val socket = Any()
        val firstId = names.open(socket)
        names.remember(socket, "SM-S936W")

        val secondId = names.open(socket)

        assertNull(names.nameFor(firstId))
        assertNull(names.nameFor(secondId))
        names.remember(socket, "SM-S936W")
        assertEquals("SM-S936W", names.nameFor(secondId))
    }
}