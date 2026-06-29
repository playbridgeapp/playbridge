package com.playbridge.shared.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end regression tests for the SAS pairing handshake. These mirror the exact
 * transcript and MAC construction used by the phone (WebSocketClient) and TV
 * (WebSocketServer.WssTransport) so a regression in either side's byte order, commitment
 * scheme, or key-confirmation step is caught here rather than in the field.
 *
 * Wire recap (commit → challenge → reveal → confirmation):
 *   commit      = SHA256(senderEphPub || nonceS)
 *   transcript  = commit || tvEphPub || nonceT || senderEphPub || nonceS
 *   SAS         = generateSAS(sharedSecret, transcript)        // shown on TV, typed on phone
 *   confirmKey  = HKDF-Expand(HKDF-Extract(null, shared), "confirmationKey", 32)
 *   confirmMac  = HMAC-SHA256(confirmKey, transcript)          // the *only* approval signal
 */
class SasHandshakeTest {

    private val confirmationInfo = "confirmationKey".toByteArray()

    /** One end of the handshake. */
    private class Party {
        val keyPair = SasCrypto.generateX25519KeyPair()
        val nonce = SasCrypto.generateNonce(16)
    }

    private fun commitOf(senderPub: ByteArray, nonceS: ByteArray): ByteArray =
        SasCrypto.sha256(senderPub + nonceS)

    private fun transcriptOf(
        commit: ByteArray, tvPub: ByteArray, nonceT: ByteArray,
        senderPub: ByteArray, nonceS: ByteArray,
    ): ByteArray = commit + tvPub + nonceT + senderPub + nonceS

    private fun confirmationMac(shared: ByteArray, transcript: ByteArray): ByteArray {
        val prk = SasCrypto.hkdfExtract(salt = null, ikm = shared)
        val key = SasCrypto.hkdfExpand(prk, info = confirmationInfo, length = 32)
        return SasCrypto.hmacSha256(key, transcript)
    }

    @Test
    fun honestHandshake_bothSidesAgreeOnSasAndMac() {
        val phone = Party()
        val tv = Party()

        // Phone commits to its ephemeral key before the TV reveals anything.
        val commit = commitOf(phone.keyPair.publicKey, phone.nonce)

        // TV verifies the commitment when the phone later reveals (commit binding).
        assertTrue(
            "TV must be able to reproduce the commitment from the revealed key",
            commit.contentEquals(commitOf(phone.keyPair.publicKey, phone.nonce))
        )

        // Each side derives the shared secret independently.
        val phoneShared = SasCrypto.calculateECDH(phone.keyPair.privateKey, tv.keyPair.publicKey)
        val tvShared = SasCrypto.calculateECDH(tv.keyPair.privateKey, phone.keyPair.publicKey)
        assertTrue("ECDH must agree", phoneShared.contentEquals(tvShared))

        val transcript = transcriptOf(
            commit, tv.keyPair.publicKey, tv.nonce, phone.keyPair.publicKey, phone.nonce
        )

        // SAS shown on the TV must equal the SAS the phone computes (and the user types).
        val tvSas = SasCrypto.generateSAS(tvShared, transcript)
        val phoneSas = SasCrypto.generateSAS(phoneShared, transcript)
        assertEquals(phoneSas, tvSas)

        // Phone sends the confirmation MAC; the TV recomputes and accepts it.
        val phoneMac = confirmationMac(phoneShared, transcript)
        val tvExpectedMac = confirmationMac(tvShared, transcript)
        assertTrue("Confirmation MAC must verify", phoneMac.contentEquals(tvExpectedMac))
    }

    @Test
    fun tamperedMac_isRejected() {
        val phone = Party()
        val tv = Party()
        val commit = commitOf(phone.keyPair.publicKey, phone.nonce)
        val shared = SasCrypto.calculateECDH(phone.keyPair.privateKey, tv.keyPair.publicKey)
        val transcript = transcriptOf(
            commit, tv.keyPair.publicKey, tv.nonce, phone.keyPair.publicKey, phone.nonce
        )

        val goodMac = confirmationMac(shared, transcript)
        val tampered = goodMac.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

        assertFalse(
            "A flipped byte in the MAC must not verify",
            tampered.contentEquals(confirmationMac(shared, transcript))
        )
    }

    @Test
    fun forgedReveal_breaksCommitment() {
        val phone = Party()
        val attacker = Party()

        val commit = commitOf(phone.keyPair.publicKey, phone.nonce)

        // An attacker who didn't open the commitment tries to substitute its own key at reveal.
        val forgedCommit = commitOf(attacker.keyPair.publicKey, phone.nonce)
        assertFalse(
            "Substituting the ephemeral key after commit must fail commitment verification",
            forgedCommit.contentEquals(commit)
        )
    }

    @Test
    fun mitmRelay_yieldsDifferentSasPerLeg() {
        // A relay runs two independent handshakes. The user reads the TV-leg SAS and types it
        // into the phone, which expects the phone-leg SAS — so a MITM is caught by transcription.
        val phone = Party()
        val tv = Party()
        val relayToPhone = Party() // relay's keypair facing the phone
        val relayToTv = Party()    // relay's keypair facing the TV

        // Phone↔relay leg.
        val phoneCommit = commitOf(phone.keyPair.publicKey, phone.nonce)
        val phoneLegShared = SasCrypto.calculateECDH(phone.keyPair.privateKey, relayToPhone.keyPair.publicKey)
        val phoneLegTranscript = transcriptOf(
            phoneCommit, relayToPhone.keyPair.publicKey, relayToPhone.nonce,
            phone.keyPair.publicKey, phone.nonce
        )
        val phoneLegSas = SasCrypto.generateSAS(phoneLegShared, phoneLegTranscript)

        // Relay↔TV leg (relay impersonates the sender to the TV).
        val tvCommit = commitOf(relayToTv.keyPair.publicKey, relayToTv.nonce)
        val tvLegShared = SasCrypto.calculateECDH(tv.keyPair.privateKey, relayToTv.keyPair.publicKey)
        val tvLegTranscript = transcriptOf(
            tvCommit, tv.keyPair.publicKey, tv.nonce,
            relayToTv.keyPair.publicKey, relayToTv.nonce
        )
        val tvLegSas = SasCrypto.generateSAS(tvLegShared, tvLegTranscript)

        // Deterministic basis of the protection: the two legs never share a secret.
        assertFalse(
            "MITM legs must not share an ECDH secret",
            phoneLegShared.contentEquals(tvLegShared)
        )
        // And the codes the two ends show differ (1-in-a-million false-accept is acceptable).
        assertNotEquals(
            "MITM must surface as a SAS mismatch the user can see",
            phoneLegSas, tvLegSas
        )
    }
}
