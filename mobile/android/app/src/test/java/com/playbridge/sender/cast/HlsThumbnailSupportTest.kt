package com.playbridge.sender.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsThumbnailSupportTest {

    @Test
    fun rangeHeader_supportsBoundedAndOpenEndedResources() {
        assertEquals("bytes=200-299", hlsRangeHeader(offset = 200, length = 100))
        assertEquals("bytes=200-", hlsRangeHeader(offset = 200, length = -1))
        assertNull(hlsRangeHeader(offset = 0, length = -1))
    }

    @Test
    fun initializationVector_supportsSequenceAndExplicitFormats() {
        assertArrayEquals(
            ByteArray(15) + byteArrayOf(10),
            decodeHlsInitializationVector("a"),
        )
        assertArrayEquals(
            ByteArray(15) + byteArrayOf(10),
            decodeHlsInitializationVector("0x0000000000000000000000000000000A"),
        )
        assertNull(decodeHlsInitializationVector("not-hex"))
        assertNull(decodeHlsInitializationVector("1".repeat(33)))
    }

    @Test
    fun aes128Segment_isDecryptedWithPlaylistIv() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16).also { it[15] = 42 }
        val clear = "decodable media segment payload".encodeToByteArray()
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(clear)
        }

        assertArrayEquals(clear, decryptHlsAes128(encrypted, key, "2a"))
    }
}
