package com.playbridge.sender.connection

import com.playbridge.sender.connection.WebSocketClient.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketFailureStateTest {
    @Test
    fun rejectionSurvivesTheAbruptCloseThatFollowsIt() {
        assertEquals(
            ConnectionState.AuthFailed,
            webSocketStateAfterFailure(ConnectionState.AuthFailed, "EOF"),
        )
        val denied = ConnectionState.PairingDenied("TV")
        assertEquals(denied, webSocketStateAfterFailure(denied, null))
        val mismatch = ConnectionState.PinMismatch("TV")
        assertEquals(mismatch, webSocketStateAfterFailure(mismatch, null))
    }

    @Test
    fun otherFailuresBecomeAnError() {
        val connected = webSocketStateAfterFailure(ConnectionState.Connected("TV", true), "reset")
        assertEquals(ConnectionState.Error("reset"), connected)
        val unknown = webSocketStateAfterFailure(ConnectionState.Disconnected, null)
        assertTrue(unknown is ConnectionState.Error)
        assertEquals("Unknown error", (unknown as ConnectionState.Error).message)
    }
}
