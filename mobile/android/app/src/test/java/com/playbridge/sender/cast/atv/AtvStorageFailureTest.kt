package com.playbridge.sender.cast.atv

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.security.InvalidKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtvStorageFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = AtvClientIdentity("fixture-cert", "fixture-key")
    private val record = AtvTvRecord("ab".repeat(32), null, "Fixture TV", "10.0.0.1", setOf("playbridge:fixture"))

    @Test fun everyMutationKeepsMemoryAndDiskOnEncryptFailure() = failedMutations(encryptFails = true)

    @Test fun everyMutationKeepsMemoryAndDiskOnWriteFailure() = failedMutations(encryptFails = false)

    private fun failedMutations(encryptFails: Boolean) {
        val mutations: List<(AtvCredentialStore) -> Unit> = listOf(
            { it.saveIdentity(identity.copy(keyPem = "replacement-fixture")) },
            { it.saveVerifiedPairing(identity, record.copy(fingerprint = "cd".repeat(32))) },
            { it.updateLastIp(record.fingerprint, "10.0.0.2") },
            { it.forget(record.fingerprint) },
        )
        for (mutate in mutations) {
            val file = FaultSecretFile()
            val cipher = FaultCipher()
            val store = AtvCredentialStore(file, cipher)
            store.saveIdentity(identity)
            store.upsert(record)
            val before = file.read()!!
            file.beforeWrite = {
                assertEquals(identity, store.identityOrNull())
                assertEquals(listOf(record), store.records.value)
            }
            if (encryptFails) cipher.fail = true else file.fail = true
            val failure = assertThrows(AtvStorageException::class.java) { mutate(store) }
            assertEquals(ATV_SAVE_ERROR, failure.message)
            assertNull(failure.cause)
            assertEquals(identity, store.identityOrNull())
            assertEquals(listOf(record), store.records.value)
            assertFalse(store.recoveryRequired.value)
            assertArrayEquals(before, file.read())
            val reloaded = AtvCredentialStore(file, XorCipher())
            assertEquals(identity, reloaded.identityOrNull())
            assertEquals(listOf(record), reloaded.records.value)
        }
    }

    @Test fun failedForgetRemainsVisibleAndSuccessfulRetryStaysForgottenAfterRestart() {
        val file = FaultSecretFile()
        val store = AtvCredentialStore(file, XorCipher())
        store.saveIdentity(identity)
        store.upsert(record)
        file.fail = true
        assertThrows(AtvStorageException::class.java) { store.forget(record.fingerprint) }
        assertEquals(listOf(record), store.records.value)
        file.fail = false
        store.forget(record.fingerprint)
        assertTrue(AtvCredentialStore(file, XorCipher()).records.value.isEmpty())
        assertEquals(identity, AtvCredentialStore(file, XorCipher()).identityOrNull())
    }

    @Test fun atomicWriterSyncedTempIsReplacedAndCanBeReadOnRestart() {
        val directory = temporary.newFolder()
        val file = FilesAtvSecretFile(directory)
        val store = AtvCredentialStore(file, XorCipher())
        store.saveIdentity(identity)
        store.upsert(record)
        store.updateLastIp(record.fingerprint, "10.0.0.2")
        assertEquals("10.0.0.2", AtvCredentialStore(FilesAtvSecretFile(directory), XorCipher()).records.value.single().lastIp)
        store.forget(record.fingerprint)
        assertTrue(AtvCredentialStore(FilesAtvSecretFile(directory), XorCipher()).records.value.isEmpty())
        assertFalse(File(directory, "credentials.bin.tmp").exists())
    }

    @Test fun failedTempWriteDoesNotTouchLiveCiphertext() {
        atomicFailure(object : AtvFileWriter() {
            override fun writeAndSync(file: File, bytes: ByteArray) {
                file.writeBytes(bytes.copyOf(bytes.size / 2))
                throw IOException("fixture failure")
            }
        })
    }

    @Test fun failedAtomicRenameNeverFallsBackToDirectOverwrite() {
        atomicFailure(object : AtvFileWriter() {
            override fun replace(source: File, target: File) {
                throw AtomicMoveNotSupportedException("fixture-source", "fixture-target", "fixture failure")
            }
        })
    }

    private fun atomicFailure(writer: AtvFileWriter) {
        val directory = temporary.newFolder()
        val original = FilesAtvSecretFile(directory)
        AtvCredentialStore(original, XorCipher()).apply { saveIdentity(identity); upsert(record) }
        val before = original.read()!!
        val failing = AtvCredentialStore(FilesAtvSecretFile(directory, writer), XorCipher())
        assertThrows(AtvStorageException::class.java) { failing.forget(record.fingerprint) }
        assertEquals(listOf(record), failing.records.value)
        assertArrayEquals(before, original.read())
        assertEquals(listOf(record), AtvCredentialStore(original, XorCipher()).records.value)
        assertFalse(File(directory, "credentials.bin.tmp").exists())
    }

    @Test fun invalidatedKeyKeepsFileUntilVerifiedPairingRotatesAndRewritesIt() {
        val keys = FixtureKeystoreKeys()
        val file = MemorySecretFile()
        AtvCredentialStore(file, AndroidAtvCipher(keys)).apply { saveIdentity(identity); upsert(record) }
        val before = file.read()!!
        keys.invalid = true
        val store = AtvCredentialStore(file, AndroidAtvCipher(keys))
        assertTrue(store.recoveryRequired.value)
        assertNull(store.identityOrNull())
        assertTrue(store.records.value.isEmpty())
        assertThrows(AtvStorageException::class.java) { store.saveIdentity(identity) }
        assertArrayEquals(before, file.read())
        assertEquals(0, keys.rotations)
        val repairedIdentity = identity.copy(keyPem = "new-fixture-key")
        store.saveVerifiedPairing(repairedIdentity, record.copy(fingerprint = "cd".repeat(32)))
        assertEquals(1, keys.rotations)
        assertFalse(store.recoveryRequired.value)
        val reloaded = AtvCredentialStore(file, AndroidAtvCipher(keys))
        assertFalse(reloaded.recoveryRequired.value)
        assertEquals(repairedIdentity, reloaded.identityOrNull())
        assertEquals("cd".repeat(32), reloaded.records.value.single().fingerprint)
    }

    @Test fun missingKeyIsNotSilentlyRecreatedOnLoadOrNextOrdinaryWrite() {
        val keys = FixtureKeystoreKeys()
        val file = MemorySecretFile()
        val cipher = AndroidAtvCipher(keys)
        val initial = AtvCredentialStore(file, cipher)
        initial.saveIdentity(identity)
        initial.upsert(record)
        val before = file.read()!!
        keys.key = null
        assertTrue(AtvCredentialStore(file, AndroidAtvCipher(keys)).recoveryRequired.value)
        assertThrows(AtvStorageException::class.java) { initial.updateLastIp(record.fingerprint, "10.0.0.2") }
        assertTrue(initial.recoveryRequired.value)
        assertNull(initial.identityOrNull())
        assertEquals(listOf(record), initial.records.value)
        assertArrayEquals(before, file.read())
        assertEquals(0, keys.creations)
    }

    @Test fun failedRecoveryWriteKeepsRecoveryPendingAndDoesNotPublishAnyPairing() {
        val keys = FixtureKeystoreKeys()
        val file = FaultSecretFile()
        AtvCredentialStore(file, AndroidAtvCipher(keys)).apply { saveIdentity(identity); upsert(record) }
        val before = file.read()!!
        keys.invalid = true
        val store = AtvCredentialStore(file, AndroidAtvCipher(keys))
        file.fail = true
        assertThrows(AtvStorageException::class.java) { store.saveVerifiedPairing(identity, record) }
        assertTrue(store.recoveryRequired.value)
        assertNull(store.identityOrNull())
        assertTrue(store.records.value.isEmpty())
        assertArrayEquals(before, file.read())
        assertTrue(AtvCredentialStore(file, AndroidAtvCipher(keys)).recoveryRequired.value)
        file.fail = false
        store.saveVerifiedPairing(identity, record)
        assertFalse(AtvCredentialStore(file, AndroidAtvCipher(keys)).recoveryRequired.value)
    }

    @Test fun runtimeKeyRecoveryPreservesUnrelatedTvsWhenTheCachedIdentityIsVerified() {
        val keys = FixtureKeystoreKeys()
        val file = MemorySecretFile()
        val store = AtvCredentialStore(file, AndroidAtvCipher(keys))
        store.saveIdentity(identity)
        store.upsert(record)
        val other = record.copy(fingerprint = "ef".repeat(32), receiverIds = setOf("playbridge:other"))
        store.upsert(other)
        keys.invalid = true
        assertThrows(AtvStorageException::class.java) { store.updateLastIp(record.fingerprint, "10.0.0.2") }
        assertEquals(identity, store.pairingIdentityOrNull())
        store.saveVerifiedPairing(identity, record.copy(fingerprint = "cd".repeat(32)))
        val restored = AtvCredentialStore(file, AndroidAtvCipher(keys))
        assertEquals(identity, restored.identityOrNull())
        assertEquals(setOf("ef".repeat(32), "cd".repeat(32)), restored.records.value.map { it.fingerprint }.toSet())
    }

    @Test fun failedKeyRotationLeavesTheUnreadableFileUntouched() {
        val keys = FixtureKeystoreKeys()
        val file = MemorySecretFile()
        AtvCredentialStore(file, AndroidAtvCipher(keys)).saveIdentity(identity)
        keys.invalid = true
        keys.failRotation = true
        val before = file.read()!!
        val store = AtvCredentialStore(file, AndroidAtvCipher(keys))
        assertThrows(AtvStorageException::class.java) { store.saveVerifiedPairing(identity, record) }
        assertTrue(store.recoveryRequired.value)
        assertArrayEquals(before, file.read())
        assertNull(store.identityOrNull())
    }

    @Test fun aBlockedReadCannotRotateAUsableKeyOrOverwriteUnseenRecords() {
        val keys = FixtureKeystoreKeys()
        val memory = MemorySecretFile()
        AtvCredentialStore(memory, AndroidAtvCipher(keys)).apply { saveIdentity(identity); upsert(record) }
        var blocked = true
        val file = object : AtvSecretFile {
            override fun read(): ByteArray? {
                if (blocked) throw IOException("fixture failure")
                return memory.read()
            }
            override fun write(bytes: ByteArray) = memory.write(bytes)
        }
        val before = memory.read()!!
        val store = AtvCredentialStore(file, AndroidAtvCipher(keys))
        assertThrows(AtvStorageException::class.java) { store.pairingIdentityOrNull() }
        assertThrows(AtvStorageException::class.java) { store.saveVerifiedPairing(identity, record) }
        assertArrayEquals(before, memory.read())
        assertEquals(0, keys.rotations)
        blocked = false
        assertEquals(identity, store.pairingIdentityOrNull())
        assertFalse(store.recoveryRequired.value)
        assertEquals(listOf(record), store.records.value)
        store.saveVerifiedPairing(identity, record)
        assertEquals(0, keys.rotations)
        assertEquals(listOf(record), AtvCredentialStore(memory, AndroidAtvCipher(keys)).records.value)
    }
}

internal class FaultSecretFile : AtvSecretFile {
    private val memory = MemorySecretFile()
    var fail = false
    var beforeWrite: (() -> Unit)? = null
    override fun read() = memory.read()
    override fun write(bytes: ByteArray) {
        beforeWrite?.invoke()
        if (fail) throw IOException("fixture failure")
        memory.write(bytes)
    }
}

internal class FaultCipher : AtvCipher {
    private val xor = XorCipher()
    var fail = false
    override fun encrypt(plain: ByteArray): ByteArray {
        if (fail) throw IllegalStateException("fixture failure")
        return xor.encrypt(plain)
    }
    override fun decrypt(blob: ByteArray) = xor.decrypt(blob)
}

internal class FixtureKeystoreKeys : AtvKeystoreKeys {
    var key: SecretKey? = freshKey()
    var invalid = false
    var failRotation = false
    var rotations = 0
    var creations = 0
    override fun get(create: Boolean): SecretKey {
        if (invalid) throw InvalidKeyException("fixture failure")
        return key ?: if (create) freshKey().also { key = it; creations++ } else throw AtvKeyUnavailableException()
    }
    override fun rotate() {
        if (failRotation) throw IOException("fixture failure")
        key = freshKey()
        invalid = false
        rotations++
    }
    private fun freshKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
