package com.playbridge.shared.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.AsymmetricCipherKeyPair

object SasCrypto {

    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    /**
     * Generate an ephemeral X25519 private/public keypair
     */
    fun generateX25519KeyPair(): KeyPair {
        val random = SecureRandom()
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(random))
        val pair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val privateKey = (pair.private as X25519PrivateKeyParameters).encoded
        val publicKey = (pair.public as X25519PublicKeyParameters).encoded
        return KeyPair(privateKey, publicKey)
    }

    /**
     * Compute the shared ECDH secret using the private key and peer's public key
     */
    fun calculateECDH(privateKeyBytes: ByteArray, peerPublicKeyBytes: ByteArray): ByteArray {
        val privKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val pubKey = X25519PublicKeyParameters(peerPublicKeyBytes, 0)
        val agreement = X25519Agreement()
        agreement.init(privKey)
        val secret = ByteArray(32)
        agreement.calculateAgreement(pubKey, secret, 0)
        return secret
    }

    /**
     * Compute SHA-256 hash of the input data
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * Compute HMAC-SHA256 of the input data using the specified key
     */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * HKDF Extract phase (RFC 5869)
     */
    fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = salt ?: ByteArray(32) // Default to 32 bytes of zeros
        return hmacSha256(actualSalt, ikm)
    }

    /**
     * HKDF Expand phase (RFC 5869)
     */
    fun hkdfExpand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i = 1
        while (offset < length) {
            mac.reset()
            mac.update(t)
            if (info != null) mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            i++
        }
        return okm
    }

    /**
     * Generate the 6-digit Short Authentication String (SAS) code
     * calculated as: truncate(SHA-256(sharedSecret || transcript)) modulo 1,000,000
     */
    fun generateSAS(sharedSecret: ByteArray, transcript: ByteArray): String {
        val hash = sha256(sharedSecret + transcript)
        
        // Convert first 4 bytes to a 32-bit positive integer (big-endian)
        val value = ((hash[0].toInt() and 0xFF) shl 24) or
                    ((hash[1].toInt() and 0xFF) shl 16) or
                    ((hash[2].toInt() and 0xFF) shl 8) or
                    (hash[3].toInt() and 0xFF)
        
        val sasInt = (value and 0x7FFFFFFF) % 1_000_000
        return String.format("%06d", sasInt)
    }

    /**
     * Generate random bytes for nonces
     */
    fun generateNonce(size: Int = 16): ByteArray {
        val nonce = ByteArray(size)
        SecureRandom().nextBytes(nonce)
        return nonce
    }
}
