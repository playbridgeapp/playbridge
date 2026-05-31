package com.playbridge.player.protocol

import com.playbridge.shared.protocol.createAuthResponseJson
import com.playbridge.shared.protocol.createPairingApprovedJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

    @Test
    fun authResponseIncludesCapabilitiesWhenPresent() {
        val o = obj(createAuthResponseJson(
            success = true,
            players = listOf("internal_exo", "internal_mpv"),
            browsers = listOf("webview", "gecko"),
        ))
        assertEquals(
            listOf("internal_exo", "internal_mpv"),
            o["players"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("webview", "gecko"),
            o["browsers"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun authResponseOmitsCapabilitiesWhenEmpty() {
        val o = obj(createAuthResponseJson(success = true))
        assertNull(o["players"])
        assertNull(o["browsers"])
    }

    @Test
    fun pairingApprovedIncludesCapabilitiesAndOmitsGeckoWhenNotInstalled() {
        // The browsers list models a TV without the GeckoView plugin: webview only.
        val o = obj(createPairingApprovedJson(
            token = "tok",
            players = listOf("internal_exo", "internal_mpv"),
            browsers = listOf("webview"),
        ))
        assertEquals("tok", o["token"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("internal_exo", "internal_mpv"),
            o["players"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals(listOf("webview"), o["browsers"]?.jsonArray?.map { it.jsonPrimitive.content })
    }
}
