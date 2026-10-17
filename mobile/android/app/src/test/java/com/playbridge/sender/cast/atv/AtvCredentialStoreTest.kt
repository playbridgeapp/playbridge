package com.playbridge.sender.cast.atv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

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

    @Test fun verifiedRepairReplacesPinAndPreservesAliasesIdentityAndOtherTv() {
        val file = MemorySecretFile()
        val cipher = XorCipher()
        val store = AtvCredentialStore(file, cipher)
        store.saveIdentity(AtvClientIdentity(PEM, "private-key"))
        store.upsert(sampleRecord().copy(receiverIds = setOf("playbridge:hisense", "google_cast:cast-id")))
        store.upsert(sampleRecord().copy(fingerprint = "ef".repeat(32), mac = "11:22:33:44:55:66", receiverIds = setOf("playbridge:other")))
        store.upsert(sampleRecord().copy(fingerprint = "cd".repeat(32)))
        val restored = AtvCredentialStore(file, cipher)
        assertEquals(2, restored.records.value.size)
        val repaired = restored.records.value.single { "playbridge:hisense" in it.receiverIds }
        assertEquals("cd".repeat(32), repaired.fingerprint)
        assertEquals(setOf("playbridge:hisense", "google_cast:cast-id"), repaired.receiverIds)
        assertEquals("private-key", restored.identityOrNull()?.keyPem)
        assertTrue(restored.records.value.any { it.fingerprint == "ef".repeat(32) })
    }

    @Test fun legacyHisenseDocumentKeepsItsIdentityAndExplicitReceiverAssociation() {
        val file = legacyFile(legacyRecord("ab".repeat(32), "playbridge:hisense"))
        val store = AtvCredentialStore(file, XorCipher())
        assertEquals("private-key", store.identityOrNull()?.keyPem)
        assertEquals("ab".repeat(32), store.records.value.single().fingerprint)
        assertEquals(setOf("playbridge:hisense"), store.records.value.single().receiverIds)
        assertNull(store.records.value.single().serverName)
        store.updateLastIp("ab".repeat(32), "10.0.0.8")
        val json = JSONObject(XorCipher().decrypt(file.bytes!!).toString(Charsets.UTF_8))
        assertEquals(2, json.getInt("version"))
        assertEquals("private-key", AtvCredentialStore(file, XorCipher()).identityOrNull()?.keyPem)
    }

    @Test fun legacyDuplicateReceiverIdsMigrateToLatestVerifiedPinAndPreserveAliases() {
        val file = legacyFile(legacyRecord("ab".repeat(32), "playbridge:hisense", "google_cast:cast-id"), legacyRecord("cd".repeat(32), "playbridge:hisense"))
        val record = AtvCredentialStore(file, XorCipher()).records.value.single()
        assertEquals("cd".repeat(32), record.fingerprint)
        assertEquals(setOf("playbridge:hisense", "google_cast:cast-id"), record.receiverIds)
    }

    @Test fun legacyMacOnlyRecordDoesNotAcquireAnAssociationDuringMigration() {
        val store = AtvCredentialStore(legacyFile(legacyRecord("ab".repeat(32))), XorCipher())
        assertTrue(store.records.value.single().receiverIds.isEmpty())
    }

    @Test fun failedRepairWriteDoesNotPublishOrReplaceThePreviousPin() {
        val file = MemorySecretFile()
        val failing = object : AtvSecretFile {
            var fail = false
            override fun read() = file.read()
            override fun write(bytes: ByteArray) { if (fail) error("write failed") else file.write(bytes) }
        }
        val store = AtvCredentialStore(failing, XorCipher())
        store.upsert(sampleRecord())
        failing.fail = true
        assertTrue(runCatching { store.upsert(sampleRecord().copy(fingerprint = "cd".repeat(32))) }.isFailure)
        assertEquals("ab".repeat(32), store.records.value.single().fingerprint)
        assertEquals("ab".repeat(32), AtvCredentialStore(file, XorCipher()).records.value.single().fingerprint)
    }

    private fun legacyRecord(pin: String, vararg ids: String) = JSONObject()
        .put("fingerprint", pin).put("mac", "c0:e5:da:d8:c3:5f").put("server_name", JSONObject.NULL)
        .put("last_ip", "192.168.1.22").put("receiver_ids", JSONArray(ids.toList()))

    private fun legacyFile(vararg records: JSONObject) = MemorySecretFile().apply {
        val document = JSONObject().put("identity", JSONObject().put("cert_pem", PEM).put("key_pem", "private-key"))
            .put("tvs", JSONArray(records.toList()))
        write(XorCipher().encrypt(document.toString().toByteArray(Charsets.UTF_8)))
    }

    @Test fun legacyMigrationKeepsOtherTvIdentityAcrossReloadAndForget() {
        val file = MemorySecretFile()
        val cipher = XorCipher()
        val store = AtvCredentialStore(file, cipher)
        val old = AtvClientIdentity("OLD-CERT", "OLD-KEY")
        val fresh = AtvClientIdentity("NEW-CERT", "NEW-KEY")
        val first = sampleRecord()
        val other = first.copy(fingerprint = "cd".repeat(32), receiverIds = setOf("playbridge:other"))
        store.saveIdentity(old); store.upsert(first); store.upsert(other)
        store.saveVerifiedPairing(fresh, first, preserveOtherPairings = true)
        val restored = AtvCredentialStore(file, cipher)
        assertTrue(restored.identityOrNull() == fresh)
        assertTrue(restored.identityOrNull(first.fingerprint) == fresh)
        assertTrue(restored.identityOrNull(other.fingerprint) == old)
        assertEquals(2, restored.records.value.size)
        restored.updateLastIp(other.fingerprint, "192.0.2.2")
        assertTrue(AtvCredentialStore(file, cipher).identityOrNull(other.fingerprint) == old)
        restored.forget(first.fingerprint)
        assertTrue(AtvCredentialStore(file, cipher).identityOrNull(other.fingerprint) == old)
        restored.saveVerifiedPairing(fresh, other)
        assertTrue(AtvCredentialStore(file, cipher).identityOrNull(other.fingerprint) == fresh)
    }

    @Test fun failedMigrationWriteDoesNotReplaceIdentityOrOtherTvCredentials() {
        val file = FaultSecretFile()
        val store = AtvCredentialStore(file, XorCipher())
        val old = AtvClientIdentity("OLD-CERT", "OLD-KEY")
        store.saveIdentity(old); store.upsert(sampleRecord())
        file.fail = true
        assertTrue(runCatching { store.saveVerifiedPairing(AtvClientIdentity("NEW-CERT", "NEW-KEY"), sampleRecord(), true) }.isFailure)
        assertTrue(store.identityOrNull() == old)
        file.fail = false
        assertTrue(AtvCredentialStore(file, XorCipher()).identityOrNull() == old)
    }

    @Test fun installLabelsUseOnlyABoundedModelAndIdentitiesHaveRedactedDescriptions() {
        assertEquals("PlayBridge (Pixel 8)", atvClientLabel("Pixel 8"))
        assertEquals("PlayBridge (Android)", atvClientLabel("\n"))
        assertTrue(atvClientLabel("x".repeat(200)).length < 53)
        assertTrue(AtvClientIdentity("secret-cert", "secret-key").toString() == "AtvClientIdentity(redacted)")
        assertTrue(AtvIdentity("secret-cert", "secret-key", "ab".repeat(32), "secret-name").toString() == "AtvIdentity(redacted)")
    }

    @Test fun suffixUsesTheExistingPhoneUuidWithTheSpecifiedDomainAndEightLowercaseHexCharacters() {
        val uuid = "00000000-0000-0000-0000-000000000001"
        val name = atvClientName("Pixel 8", uuid)
        assertTrue(name == "PlayBridge (Pixel 8) d52db763")
        assertTrue(name == atvClientName("Pixel 8", uuid))
        assertTrue(atvClientName("Pixel 8", "00000000-0000-0000-0000-000000000002").endsWith("2a87b8e8"))
        assertFalse(name.contains(uuid))
        assertTrue(name.takeLast(8).matches(Regex("[0-9a-f]{8}")))
    }

    @Test fun sharedPhoneUuidAccessorCreatesOnlyWhenAbsentAndReusesTheStoredValue() {
        var saved: String? = null
        var created = 0
        val read = { saved }
        val write: (String) -> Unit = { saved = it }
        val create = { created++; "00000000-0000-0000-0000-000000000001" }
        val first = com.playbridge.sender.connection.PlayBridgePhoneIdentity.uuid(read, write, create)
        val second = com.playbridge.sender.connection.PlayBridgePhoneIdentity.uuid(read, write, create)
        assertTrue(first == second && first == saved)
        assertEquals(1, created)
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
