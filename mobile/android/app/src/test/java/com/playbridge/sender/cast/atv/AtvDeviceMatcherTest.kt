package com.playbridge.sender.cast.atv

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtvDeviceMatcherTest {
    private val endpoint = EndpointKey(CastProtocol.GOOGLE_CAST, "cast-1")

    @Test fun sameIpMatches() {
        val match = AtvDeviceMatcher.match(receiver(listOf("[192.168.1.22]")), listOf(device("192.168.1.22")), emptyList())
        assertNotNull(match.device)
        assertEquals("192.168.1.22", match.host)
        assertTrue(match.available)
    }

    @Test fun differentIpDoesNotMatch() {
        val match = AtvDeviceMatcher.match(receiver(listOf("10.0.0.9")), listOf(device("192.168.1.22")), emptyList())
        assertNull(match.device)
        assertNull(match.record)
        assertTrue(!match.available)
    }

    @Test fun storedMacMatchesDiscoveredBluetoothAddressAfterIpChange() {
        val record = record(lastIp = "192.168.1.22", receiverIds = emptySet())
        val match = AtvDeviceMatcher.match(
            receiver(listOf("10.0.0.8")),
            listOf(device(host = "10.0.0.8", mac = "c0:e5:da:d8:c3:5f")),
            listOf(record),
        )
        assertEquals(record.fingerprint, match.record?.fingerprint)
        assertEquals("10.0.0.8", match.device?.host)
        assertEquals("C0:E5:DA:D8:C3:5F", match.record?.mac)
    }

    @Test fun storedReceiverIdMatchesWithoutDiscovery() {
        val record = record(lastIp = "192.168.1.22", receiverIds = setOf(endpoint.toString()))
        val match = AtvDeviceMatcher.match(receiver(listOf("10.0.0.8")), emptyList(), listOf(record))
        assertEquals(record.fingerprint, match.record?.fingerprint)
        assertNull(match.device)
        assertTrue(match.available)
    }

    @Test fun lastIpMatchesWhenDiscoveryIsEmpty() {
        val record = record(lastIp = "10.0.0.4", receiverIds = emptySet())
        val match = AtvDeviceMatcher.match(receiver(listOf("10.0.0.4")), emptyList(), listOf(record))
        assertEquals(record.fingerprint, match.record?.fingerprint)
        assertEquals("10.0.0.4", match.host)
    }

    private fun receiver(addresses: List<String>) = AtvReceiverTarget(endpoint, "TV", addresses, androidTv = false, googleCast = true)

    private fun device(host: String, mac: String = "C0:E5:DA:D8:C3:5F") = AtvDiscoveredDevice(
        name = "Living Room",
        host = host,
        port = 6466,
        addresses = listOf(host),
        txt = listOf("bt" to mac),
    )

    private fun record(lastIp: String, receiverIds: Set<String>) = AtvTvRecord(
        fingerprint = "ab".repeat(32),
        mac = "C0:E5:DA:D8:C3:5F",
        serverName = "Hisense",
        lastIp = lastIp,
        receiverIds = receiverIds,
    )
}
