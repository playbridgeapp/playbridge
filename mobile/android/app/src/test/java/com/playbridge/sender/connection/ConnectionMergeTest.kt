package com.playbridge.sender.connection

import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.model.CastProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionMergeTest {
    @Test
    fun `same advertised id from different protocols remains separate`() {
        val native = dev("192.168.1.10", 8765, uuid = "shared-id")
            .copy(protocol = CastProtocol.PLAYBRIDGE)
        val dlna = dev("192.168.1.10", 1400, uuid = "shared-id").copy(
            protocol = CastProtocol.DLNA,
            isDlna = true,
        )

        val history = ConnectionMerge.upsertHistory(listOf(native), dlna)

        assertEquals(2, history.size)
        // PlayBridge stays first-class; external shortcuts follow.
        assertEquals(CastProtocol.PLAYBRIDGE, history[0].resolvedProtocol)
        assertEquals(CastProtocol.DLNA, history[1].resolvedProtocol)
    }

    @Test
    fun externalHistoryIsCappedAndDoesNotEvictPlayBridge() {
        val pb = dev("1.1.1.1", 8765, uuid = "pb1", token = "t")
            .copy(protocol = CastProtocol.PLAYBRIDGE)
        val externals = (1..5).map { i ->
            dev("2.2.2.$i", 1400 + i, uuid = "dlna$i").copy(
                protocol = CastProtocol.DLNA,
                isDlna = true,
            )
        }
        var history = listOf(pb)
        externals.forEach { history = ConnectionMerge.upsertHistory(history, it) }

        assertEquals(1, ConnectionMerge.playBridgeHistory(history).size)
        assertEquals(ConnectionMerge.MAX_EXTERNAL_HISTORY, ConnectionMerge.recentExternalHistory(history).size)
        assertEquals(pb.uuid, ConnectionMerge.playBridgeHistory(history).first().uuid)
    }

    private fun dev(
        ip: String,
        port: Int,
        uuid: String = "",
        wssPort: Int? = null,
        logsPort: Int? = null,
        token: String = "",
        cert: String? = null,
    ) = TvDevice(
        ip = ip, port = port, token = token, name = "TV",
        uuid = uuid, wssPort = wssPort, logsPort = logsPort, certFingerprint = cert,
    )

    @Test
    fun takesCompleteDiscoveredEndpointByUuidAndKeepsCredentials() {
        val device = dev("1.1.1.1", 8765, uuid = "u1", token = "t", cert = "sha256/x")
        val discovered = listOf(
            dev("9.9.9.9", 9020, uuid = "u1", wssPort = 9020, logsPort = 9021)
        )
        val merged = ConnectionMerge.withDiscoveredEndpoint(device, discovered)
        assertEquals("9.9.9.9", merged.ip)
        assertEquals(9020, merged.port)
        assertEquals(9020, merged.wssPort)
        assertEquals(9021, merged.logsPort)
        assertEquals("t", merged.token)               // token preserved
        assertEquals("sha256/x", merged.certFingerprint) // pin preserved
    }

    @Test
    fun `DLNA endpoint healing refreshes description transport and volume URLs`() {
        val saved = dev("192.168.1.20", 0, uuid = "renderer-1").copy(
            protocol = CastProtocol.DLNA,
            descriptionUrl = "http://192.168.1.20/old.xml",
            controlUrl = "http://192.168.1.20/old-av",
        )
        val discovered = saved.copy(
            descriptionUrl = "http://192.168.1.21/device.xml",
            controlUrl = "http://192.168.1.21/avtransport",
            renderingControlUrl = "http://192.168.1.21/rendering",
        )

        val merged = ConnectionMerge.withDiscoveredEndpoint(saved, listOf(discovered))

        assertEquals(discovered.descriptionUrl, merged.descriptionUrl)
        assertEquals(discovered.controlUrl, merged.controlUrl)
        assertEquals(discovered.renderingControlUrl, merged.renderingControlUrl)
    }

    @Test
    fun fallsBackToIpPortMatchWhenNoUuid() {
        val device = dev("1.1.1.1", 8765, uuid = "")
        val discovered = listOf(dev("1.1.1.1", 8765, uuid = "other", wssPort = 8766))
        assertEquals(8766, ConnectionMerge.withDiscoveredEndpoint(device, discovered).wssPort)
    }

    @Test
    fun keepsDeviceWssPortWhenNoMatch() {
        val device = dev("1.1.1.1", 8765, uuid = "u1", wssPort = 9000)
        assertEquals(9000, ConnectionMerge.withDiscoveredEndpoint(device, emptyList()).wssPort)
    }

    @Test
    fun nullWhenNoMatchAndNoDeviceWssPort() {
        val device = dev("1.1.1.1", 8765, uuid = "u1")
        assertNull(ConnectionMerge.withDiscoveredEndpoint(device, emptyList()).wssPort)
    }

    // ── connection history identity ────────────────────────────────────────

    @Test
    fun newPortReplacesHistoryEntryWithSameUuid() {
        val oldEndpoint = dev("1.1.1.1", 8765, uuid = "u1", token = "old")
        val newEndpoint = dev("1.1.1.1", 8766, uuid = "u1", token = "current")

        val history = ConnectionMerge.upsertHistory(listOf(oldEndpoint), newEndpoint)

        assertEquals(listOf(newEndpoint), history)
    }

    @Test
    fun normalizesDuplicatesAlreadyStoredAtDifferentPorts() {
        val current = dev("1.1.1.1", 8766, uuid = "u1", token = "current")
        val stale = dev("1.1.1.1", 8765, uuid = "u1", token = "old")
        val other = dev("2.2.2.2", 8765, uuid = "u2", token = "other")

        val history = ConnectionMerge.normalizeHistory(listOf(current, stale, other))

        assertEquals(listOf(current, other), history)
    }

    @Test
    fun legacyEntriesWithoutUuidStillUseIpAndPortIdentity() {
        val first = dev("1.1.1.1", 8765)
        val sameEndpoint = dev("1.1.1.1", 8765, token = "new")
        val otherPort = dev("1.1.1.1", 8766)

        val history = ConnectionMerge.upsertHistory(
            listOf(first, otherPort),
            sameEndpoint,
        )

        assertEquals(listOf(sameEndpoint, otherPort), history)
    }

    @Test
    fun removingByUuidClearsEveryStaleEndpoint() {
        val current = dev("1.1.1.1", 8766, uuid = "u1")
        val stale = dev("1.1.1.1", 8765, uuid = "u1")
        val other = dev("2.2.2.2", 8765, uuid = "u2")

        val history = ConnectionMerge.removeHistoryDevice(
            listOf(current, stale, other),
            current,
        )

        assertEquals(listOf(other), history)
    }

    // ── resolveAuthFailure ──────────────────────────────────────────────────
    // The regression this guards: pairing with TV B being denied must NOT wipe
    // the token of a different, already-paired TV A (the stored device) — that
    // made tapping the saved TV ask for the pairing code again.

    @Test
    fun failedPairingWithDifferentTvLeavesSavedDeviceAlone() {
        val savedA = dev("1.1.1.1", 8765, uuid = "uA", token = "tokenA")
        val newB = dev("2.2.2.2", 8765, uuid = "uB", token = "")
        val (target, action) = ConnectionMerge.resolveAuthFailure(failed = newB, saved = savedA)!!
        assertEquals("uB", target.uuid)
        assertEquals(ConnectionMerge.AuthFailureAction.WIPE_FAILED_HISTORY_ONLY, action)
    }

    @Test
    fun staleTokenOnSavedDeviceWipesJustItsToken() {
        val savedA = dev("1.1.1.1", 8765, uuid = "uA", token = "tokenA")
        // Startup auto-connect: no in-flight device, the stored one failed.
        val (target, action) = ConnectionMerge.resolveAuthFailure(failed = null, saved = savedA)!!
        assertEquals("uA", target.uuid)
        assertEquals(ConnectionMerge.AuthFailureAction.WIPE_SAVED_TOKEN, action)
    }

    @Test
    fun deliberateReconnectToSavedDeviceStillWipesItsToken() {
        val savedA = dev("1.1.1.1", 8765, uuid = "uA", token = "tokenA")
        // Same TV rediscovered at a new IP — uuid identifies it as the saved one.
        val failedA = dev("1.1.1.50", 8765, uuid = "uA", token = "tokenA")
        val (_, action) = ConnectionMerge.resolveAuthFailure(failed = failedA, saved = savedA)!!
        assertEquals(ConnectionMerge.AuthFailureAction.WIPE_SAVED_TOKEN, action)
    }

    @Test
    fun firstEverPairingFailureClearsTheHalfSavedDevice() {
        val saved = dev("1.1.1.1", 8765, uuid = "uA", token = "")
        val (_, action) = ConnectionMerge.resolveAuthFailure(failed = saved, saved = saved)!!
        assertEquals(ConnectionMerge.AuthFailureAction.CLEAR_SAVED_DEVICE, action)
    }

    @Test
    fun nothingKnownReturnsNull() {
        assertNull(ConnectionMerge.resolveAuthFailure(failed = null, saved = null))
    }

    @Test
    fun ipPortMatchUsedWhenUuidMissing() {
        val savedA = dev("1.1.1.1", 8765, uuid = "", token = "tokenA")
        val failedSameIp = dev("1.1.1.1", 8765, uuid = "", token = "tokenA")
        val (_, action) = ConnectionMerge.resolveAuthFailure(failed = failedSameIp, saved = savedA)!!
        assertEquals(ConnectionMerge.AuthFailureAction.WIPE_SAVED_TOKEN, action)
    }
}
