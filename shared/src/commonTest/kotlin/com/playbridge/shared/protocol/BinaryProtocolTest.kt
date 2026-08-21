package com.playbridge.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class BinaryProtocolTest {
    @Test
    fun resetMousePacketRoundTrips() {
        val decoded = MousePacket.unpack(MousePacket.pack("reset", 0f, 0f))

        assertEquals("reset", decoded?.event)
        assertEquals(0f, decoded?.dx)
        assertEquals(0f, decoded?.dy)

        val rotation = MousePacket.unpack(MousePacket.pack("rotate", 12.5f, 0f))
        assertEquals("rotate", rotation?.event)
        assertEquals(12.5f, rotation?.dx)
    }
}
