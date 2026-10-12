package com.playbridge.sender.cast.atv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtvCredentialStoreTest {
    @Test fun roundTripKeepsIdentityAndOneTelevision() {
        val file = MemorySecretFile()
        val cipher = XorCipher()
        val store = AtvCredentialStore(file, cipher)
        store.saveIdentity(AtvClientIdentity(PEM, "private-key"))
        store.upsert(sampleRecord())
        val encoded = file.bytes!!.toString(Charsets.UTF_8)
        assertFalse(encoded.contains("BEGIN CERTIFICATE"))
        assertFalse(encoded.contains("private-key"))

        val restored = AtvCredentialStore(file, cipher)
        assertEquals(PEM, restored.identityOrNull()?.certPem)
        assertEquals("private-key", restored.identityOrNull()?.keyPem)
        val tv = restored.records.value.single()
        assertEquals("ab".repeat(32), tv.fingerprint)
        assertEquals("C0:E5:DA:D8:C3:5F", tv.mac)
        assertEquals("Hisense", tv.serverName)
        assertEquals("192.168.1.22", tv.lastIp)
        assertEquals(setOf("playbridge:hisense"), tv.receiverIds)
    }

    @Test fun forgetRemovesOneTelevisionAndKeepsIdentity() {
        val file = MemorySecretFile()
        val store = AtvCredentialStore(file, XorCipher())
        store.saveIdentity(AtvClientIdentity(PEM, "private-key"))
        store.upsert(sampleRecord())
        store.upsert(sampleRecord().copy(fingerprint = "cd".repeat(32), serverName = "Other"))
        store.forget("ab".repeat(32))
        assertEquals(PEM, store.identityOrNull()?.certPem)
        assertEquals(listOf("Other"), store.records.value.map { it.serverName })
    }

    @Test fun corruptBytesOpenAsAnEmptyStore() {
        val file = MemorySecretFile()
        file.write("not-a-credential".toByteArray())
        val store = AtvCredentialStore(file, XorCipher())
        assertNull(store.identityOrNull())
        assertTrue(store.records.value.isEmpty())
    }

    private fun sampleRecord() = AtvTvRecord(
        fingerprint = "ab".repeat(32),
        mac = "c0:e5:da:d8:c3:5f",
        serverName = "Hisense",
        lastIp = "192.168.1.22",
        receiverIds = setOf("playbridge:hisense"),
    )

    private companion object {
        const val PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
    }
}

internal class MemorySecretFile : AtvSecretFile {
    var bytes: ByteArray? = null
    override fun read(): ByteArray? = bytes?.copyOf()
    override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
}

internal class XorCipher : AtvCipher {
    override fun encrypt(plain: ByteArray): ByteArray = ByteArray(plain.size) { index -> (plain[index].toInt() xor 0x5A).toByte() }
    override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
}
