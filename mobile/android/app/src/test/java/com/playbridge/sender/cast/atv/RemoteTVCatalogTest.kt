package com.playbridge.sender.cast.atv

import com.playbridge.sender.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTVCatalogTest {
    private val a = TvDevice("192.0.2.1",8765,"fixture","Living Room",uuid = "a")
    private val b = TvDevice("192.0.2.2",8765,"","Bedroom",uuid = "b")
    private val record = AtvTvRecord("a".repeat(64),"AA",a.name,a.ip,setOf("playbridge:a"))
    private val second = AtvTvRecord("b".repeat(64),"BB",b.name,b.ip,setOf("playbridge:b"))
    private val live = AtvDiscoveredDevice(a.name,"192.0.2.3",6466,listOf("192.0.2.3"),listOf("bt" to "AA"))
    private fun rows(saved: List<TvDevice>, selected: TvDevice? = null, records: List<AtvTvRecord> = listOf(record),
                     tv: List<AtvDiscoveredDevice> = emptyList(), connected: String? = null) =
        RemoteTVCatalog.rows(saved, selected, records, emptyList(), tv, emptySet(), connected)

    @Test fun savedAndAtvOnlyRecordsStayListedWithoutAPlayBridgeAdvertisement() {
        assertEquals(listOf("Offline","Offline"), rows(listOf(a,b)).map { it.status })
        val moved = rows(listOf(a), tv = listOf(live)).single()
        assertEquals("TV remote only", moved.status)
        assertEquals(live.host, moved.device.ip)
        assertEquals(a.token, moved.device.token)
        val synthetic = rows(emptyList(), tv = listOf(live)).single()
        assertEquals("playbridge:a", synthetic.endpoint)
        assertEquals("TV remote only", synthetic.status)
        assertEquals(8765, synthetic.device.port)
        assertTrue(rows(listOf(a), connected = "playbridge:a").single().tvReachable)
        assertFalse(rows(listOf(a), connected = "playbridge:other").single().tvReachable)
    }
    @Test fun aliasesDeduplicateButConflictingAssociationsStayRefused() {
        val aliases = record.copy(receiverIds = setOf("playbridge:a","playbridge:old"))
        val result = rows(listOf(a.copy(uuid = "old"),a), selected = a, records = listOf(aliases), tv = listOf(live))
        assertEquals(1, result.size)
        assertEquals("playbridge:a", result.single().endpoint)
        val ambiguous = rows(listOf(a), records = listOf(record, second.copy(receiverIds = setOf("playbridge:a"))), tv = listOf(live)).single()
        assertTrue(ambiguous.ambiguous)
        assertFalse(ambiguous.paired || ambiguous.tvReachable)
        assertEquals(2, rows(emptyList(), records = listOf(record,second), tv = listOf(live)).size)
    }
    @Test fun discoveryByUuidOverridesAFailedProbeOfTheSavedAddress() {
        val moved = a.copy(ip = "192.0.2.30", port = 8766, wssPort = 8766, token = "")
        val rows = RemoteTVCatalog.rows(listOf(a),a,listOf(record),listOf(moved),emptyList(),emptySet(),null,setOf("playbridge:a"))
        assertTrue(rows.single().receiverReachable)
        assertEquals(moved.ip, rows.single().device.ip)
        assertEquals(moved.port, rows.single().device.port)
        assertEquals(a.token, rows.single().device.token)
        assertFalse(RemoteTVCatalog.rows(listOf(a),a,listOf(record),emptyList(),emptyList(),emptySet(),null,setOf("playbridge:a"))
            .single().receiverReachable)
    }
}
