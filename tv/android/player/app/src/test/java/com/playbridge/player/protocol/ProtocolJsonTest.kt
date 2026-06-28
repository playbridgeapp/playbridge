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
import org.junit.Assert.assertTrue
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
            players = listOf("exo", "mpv"),
            browsers = listOf("webview", "gecko"),
        ))
        assertEquals(
            listOf("exo", "mpv"),
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
            players = listOf("exo", "mpv"),
            browsers = listOf("webview"),
        ))
        assertEquals("tok", o["token"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("exo", "mpv"),
            o["players"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals(listOf("webview"), o["browsers"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun pairingCommitSerializationAndParsing() {
        val json = com.playbridge.shared.protocol.createPairingCommitJson(
            commit = "my-commit-hash",
            deviceName = "My Phone",
            deviceUUID = "uuid-1234"
        )
        val parsed = com.playbridge.shared.protocol.parseIncomingMessage(json)
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.PairingCommit)
        val commitMsg = (parsed as com.playbridge.shared.protocol.IncomingMessage.PairingCommit).msg
        assertEquals("pairing_commit", commitMsg.type)
        assertEquals("my-commit-hash", commitMsg.commit)
        assertEquals("My Phone", commitMsg.device_name)
        assertEquals("uuid-1234", commitMsg.device_uuid)
    }

    @Test
    fun pairingChallengeSerializationAndParsing() {
        val json = com.playbridge.shared.protocol.createPairingChallengeJson(
            tvEphPub = "tv-pubkey-bytes",
            nonceT = "nonce-t-bytes"
        )
        val parsed = com.playbridge.shared.protocol.parseIncomingMessage(json)
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.PairingChallenge)
        val challengeMsg = (parsed as com.playbridge.shared.protocol.IncomingMessage.PairingChallenge).msg
        assertEquals("pairing_challenge", challengeMsg.type)
        assertEquals("tv-pubkey-bytes", challengeMsg.tv_eph_pub)
        assertEquals("nonce-t-bytes", challengeMsg.nonce_t)
    }

    @Test
    fun pairingRevealSerializationAndParsing() {
        val json = com.playbridge.shared.protocol.createPairingRevealJson(
            senderEphPub = "sender-pubkey-bytes",
            nonceS = "nonce-s-bytes"
        )
        val parsed = com.playbridge.shared.protocol.parseIncomingMessage(json)
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.PairingReveal)
        val revealMsg = (parsed as com.playbridge.shared.protocol.IncomingMessage.PairingReveal).msg
        assertEquals("pairing_reveal", revealMsg.type)
        assertEquals("sender-pubkey-bytes", revealMsg.sender_eph_pub)
        assertEquals("nonce-s-bytes", revealMsg.nonce_s)
    }

    @Test
    fun pairingConfirmationSerializationAndParsing() {
        val json = com.playbridge.shared.protocol.createPairingConfirmationJson(
            mac = "confirmation-mac"
        )
        val parsed = com.playbridge.shared.protocol.parseIncomingMessage(json)
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.PairingConfirmation)
        val confirmationMsg = (parsed as com.playbridge.shared.protocol.IncomingMessage.PairingConfirmation).msg
        assertEquals("pairing_confirmation", confirmationMsg.type)
        assertEquals("confirmation-mac", confirmationMsg.mac)
    }
}
