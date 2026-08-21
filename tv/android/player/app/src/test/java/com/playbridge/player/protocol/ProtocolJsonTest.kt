package com.playbridge.player.protocol

import com.playbridge.shared.protocol.createAuthResponseJson
import com.playbridge.shared.protocol.createProtectedPairingApprovedJson
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.protocol.createScreenMirrorCandidateCommandJson
import com.playbridge.shared.protocol.createScreenMirrorOfferJson
import com.playbridge.shared.protocol.createScreenMirrorReadyJson
import com.playbridge.shared.protocol.createScreenMirrorStartJson
import com.playbridge.shared.protocol.parseIncomingMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload
import playbridge.PlaylistPayload

class ProtocolJsonTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun playlistSkipPreplayRoundTripsThroughTheParser() {
        val parsed = parseIncomingMessage(
            createPlaylistCommandJson(
                PlaylistPayload(
                    items = listOf(PlayPayload(url = "https://media.example/song.mp3")),
                    skip_preplay = true,
                ),
            ),
        )
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.Playlist)
        assertEquals(
            true,
            (parsed as com.playbridge.shared.protocol.IncomingMessage.Playlist).payload.skip_preplay,
        )
    }

    @Test
    fun pairingApprovedContainsOnlyProtectedCredentialEnvelope() {
        val o = obj(createProtectedPairingApprovedJson("nonce-b64", "ciphertext-b64"))
        assertEquals("pairing_approved", o["type"]?.jsonPrimitive?.content)
        assertEquals("nonce-b64", o["nonce"]?.jsonPrimitive?.content)
        assertEquals("ciphertext-b64", o["ciphertext"]?.jsonPrimitive?.content)
        assertNull(o["token"])
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
    fun authResponseAdvertisesScreenMirrorCapability() {
        val o = obj(createAuthResponseJson(success = true, screenMirrorWebRtc = true))
        assertEquals(true, o["screenMirrorWebRtc"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun parsesScreenMirrorOfferAndCandidateCommands() {
        val sessionId = "a271abbb-8a9c-4947-a776-8af9c6fe7fd0"
        val offer = parseIncomingMessage(createScreenMirrorOfferJson(sessionId, "v=0\\r\\n"))
        assertTrue(offer is com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorOffer)

        val candidate = parseIncomingMessage(createScreenMirrorCandidateCommandJson(
            sessionId, "0", 0, "candidate:1 1 UDP 1 192.168.1.20 5000 typ host",
        ))
        assertTrue(candidate is com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorCandidate)
    }

    @Test
    fun screenMirrorStartRequiresProtocolVersionOne() {
        val sessionId = "a271abbb-8a9c-4947-a776-8af9c6fe7fd0"
        assertTrue(
            parseIncomingMessage(createScreenMirrorStartJson(sessionId)) is
                com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorStart,
        )

        val missingVersion =
            """{"type":"command","action":"screen_mirror_start","payload":{"sessionId":"$sessionId"}}"""
        val unsupportedVersion =
            """{"type":"command","action":"screen_mirror_start","payload":{"sessionId":"$sessionId","protocolVersion":2}}"""
        assertTrue(parseIncomingMessage(missingVersion) is com.playbridge.shared.protocol.IncomingMessage.Unknown)
        assertTrue(parseIncomingMessage(unsupportedVersion) is com.playbridge.shared.protocol.IncomingMessage.Unknown)
    }

    @Test
    fun screenMirrorReadyRoundTripsThroughTheParser() {
        val sessionId = "6ea051d8-d6d6-4513-aad4-7c72852420d5"
        val parsed = parseIncomingMessage(createScreenMirrorReadyJson(sessionId))
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorReady)
        assertEquals(sessionId, (parsed as com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorReady).sessionId)
    }

    @Test
    fun authResponseOmitsCapabilitiesWhenEmpty() {
        val o = obj(createAuthResponseJson(success = true))
        assertNull(o["players"])
        assertNull(o["browsers"])
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
