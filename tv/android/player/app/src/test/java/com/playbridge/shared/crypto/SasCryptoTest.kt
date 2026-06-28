package com.playbridge.shared.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SasCryptoTest {

    @Test
    fun ecdhAgreementParity() {
        val aliceKeyPair = SasCrypto.generateX25519KeyPair()
        val bobKeyPair = SasCrypto.generateX25519KeyPair()

        assertNotEquals(aliceKeyPair.privateKey, bobKeyPair.privateKey)
        assertNotEquals(aliceKeyPair.publicKey, bobKeyPair.publicKey)

        val aliceSecret = SasCrypto.calculateECDH(aliceKeyPair.privateKey, bobKeyPair.publicKey)
        val bobSecret = SasCrypto.calculateECDH(bobKeyPair.privateKey, aliceKeyPair.publicKey)

        assertTrue(aliceSecret.contentEquals(bobSecret))
        assertEquals(32, aliceSecret.size)
    }

    @Test
    fun sha256Helper() {
        val input = "hello world".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(input)
        val actual = SasCrypto.sha256(input)
        assertTrue(expected.contentEquals(actual))
    }

    @Test
    fun hmacSha256Helper() {
        val key = "secret-key".toByteArray()
        val data = "some-data-to-mac".toByteArray()
        val mac1 = SasCrypto.hmacSha256(key, data)
        val mac2 = SasCrypto.hmacSha256(key, data)
        assertTrue(mac1.contentEquals(mac2))
        assertEquals(32, mac1.size)
    }

    @Test
    fun hkdfExtractAndExpand() {
        val ikm = "input-keying-material-123456789".toByteArray()
        val salt = "hkdf-salt-value".toByteArray()
        val info = "hkdf-info-value".toByteArray()

        val prk = SasCrypto.hkdfExtract(salt, ikm)
        assertEquals(32, prk.size)

        val okm = SasCrypto.hkdfExpand(prk, info, 64)
        assertEquals(64, okm.size)

        // HKDF with null salt should default to zero bytes salt
        val prkNullSalt = SasCrypto.hkdfExtract(null, ikm)
        assertEquals(32, prkNullSalt.size)
    }

    @Test
    fun sasGenerationAndFormatting() {
        val sharedSecret = ByteArray(32) { 0x01.toByte() }
        val transcript = ByteArray(64) { 0x02.toByte() }

        val sas1 = SasCrypto.generateSAS(sharedSecret, transcript)
        val sas2 = SasCrypto.generateSAS(sharedSecret, transcript)

        assertEquals(sas1, sas2)
        assertEquals(6, sas1.length)
        assertTrue(sas1.all { it.isDigit() })

        // Test with another arbitrary input
        val anotherSecret = ByteArray(32) { 0xFF.toByte() }
        val anotherTranscript = ByteArray(10) { 0x00.toByte() }
        val sas3 = SasCrypto.generateSAS(anotherSecret, anotherTranscript)
        assertEquals(6, sas3.length)
        assertTrue(sas3.all { it.isDigit() })
    }
}
