package com.playbridge.sender.connection

import com.playbridge.sender.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionMergeTest {

    private fun dev(
        ip: String,
        port: Int,
        uuid: String = "",
        wssPort: Int? = null,
        token: String = "",
        cert: String? = null,
    ) = TvDevice(
        ip = ip, port = port, token = token, name = "TV",
        uuid = uuid, wssPort = wssPort, certFingerprint = cert,
    )

    @Test
    fun takesDiscoveredWssPortByUuidAndKeepsCredentials() {
        val device = dev("1.1.1.1", 8765, uuid = "u1", token = "t", cert = "sha256/x")
        val discovered = listOf(dev("9.9.9.9", 8765, uuid = "u1", wssPort = 8766))
        val merged = ConnectionMerge.withDiscoveredWssPort(device, discovered)
        assertEquals(8766, merged.wssPort)
        assertEquals("t", merged.token)               // token preserved
        assertEquals("sha256/x", merged.certFingerprint) // pin preserved
    }

    @Test
    fun fallsBackToIpPortMatchWhenNoUuid() {
        val device = dev("1.1.1.1", 8765, uuid = "")
        val discovered = listOf(dev("1.1.1.1", 8765, uuid = "other", wssPort = 8766))
        assertEquals(8766, ConnectionMerge.withDiscoveredWssPort(device, discovered).wssPort)
    }

    @Test
    fun keepsDeviceWssPortWhenNoMatch() {
        val device = dev("1.1.1.1", 8765, uuid = "u1", wssPort = 9000)
        assertEquals(9000, ConnectionMerge.withDiscoveredWssPort(device, emptyList()).wssPort)
    }

    @Test
    fun nullWhenNoMatchAndNoDeviceWssPort() {
        val device = dev("1.1.1.1", 8765, uuid = "u1")
        assertNull(ConnectionMerge.withDiscoveredWssPort(device, emptyList()).wssPort)
    }
}
