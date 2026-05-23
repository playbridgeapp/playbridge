package com.playbridge.player.protocol

import com.playbridge.shared.protocol.createAuthResponseJson
import com.playbridge.shared.protocol.createPairingApprovedJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolJsonTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun pairingApprovedIncludesCertFingerprintWhenPresent() {
        val o = obj(createPairingApprovedJson("tok", "sha256/abc"))
        assertEquals("pairing_approved", o["type"]?.jsonPrimitive?.content)
        assertEquals("tok", o["token"]?.jsonPrimitive?.content)
        assertEquals("sha256/abc", o["certFingerprint"]?.jsonPrimitive?.content)
    }

    @Test
    fun pairingApprovedOmitsCertFingerprintWhenNull() {
        val o = obj(createPairingApprovedJson("tok"))
        assertEquals("tok", o["token"]?.jsonPrimitive?.content)
        assertNull(o["certFingerprint"])
    }

    @Test
    fun authResponseIncludesCertFingerprintWhenPresent() {
        val o = obj(createAuthResponseJson(success = true, certFingerprint = "sha256/xyz"))
        assertEquals("auth_response", o["type"]?.jsonPrimitive?.content)
        assertEquals(true, o["success"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("sha256/xyz", o["certFingerprint"]?.jsonPrimitive?.content)
    }

    @Test
    fun authResponseOmitsCertFingerprintWhenNull() {
        val o = obj(createAuthResponseJson(success = false))
        assertEquals(false, o["success"]?.jsonPrimitive?.content?.toBoolean())
        assertNull(o["certFingerprint"])
    }
}
