package com.playbridge.sender.connection

import com.playbridge.shared.protocol.NsdConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NsdHelperParseTest {

    private fun b(s: String) = s.toByteArray()

    @Test
    fun wssPortParsedWhenPresent() {
        val d = NsdHelper.parseDevice(
            "TV", "192.168.1.5", 8765,
            mapOf("uuid" to b("u1"), NsdConstants.KEY_WSS_PORT to b("8766")),
        )
        assertEquals(8766, d.wssPort)
        assertEquals("u1", d.uuid)
        assertEquals("192.168.1.5", d.ip)
        assertEquals(8765, d.port)
    }

    @Test
    fun wssPortNullWhenAbsent() {
        val d = NsdHelper.parseDevice("TV", "192.168.1.5", 8765, mapOf("uuid" to b("u1")))
        assertNull(d.wssPort)
    }

    @Test
    fun wssPortNullWhenNonNumeric() {
        val d = NsdHelper.parseDevice(
            "TV", "192.168.1.5", 8765,
            mapOf(NsdConstants.KEY_WSS_PORT to b("not-a-port")),
        )
        assertNull(d.wssPort)
    }

    @Test
    fun customIpOverridesResolvedIp() {
        val d = NsdHelper.parseDevice(
            "TV", "192.168.1.5", 8765,
            mapOf("custom_ip" to b("10.0.0.9")),
        )
        assertEquals("10.0.0.9", d.ip)
    }

    @Test
    fun customIpAutoIsIgnored() {
        val d = NsdHelper.parseDevice(
            "TV", "192.168.1.5", 8765,
            mapOf("custom_ip" to b("auto")),
        )
        assertEquals("192.168.1.5", d.ip)
    }
}
