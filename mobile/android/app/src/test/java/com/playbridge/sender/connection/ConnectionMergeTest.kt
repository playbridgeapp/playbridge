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
