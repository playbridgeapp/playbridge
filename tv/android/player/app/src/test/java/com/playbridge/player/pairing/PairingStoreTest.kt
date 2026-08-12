package com.playbridge.player.pairing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playbridge.player.model.PairedDevice
import com.playbridge.shared.protocol.protocolJson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairingStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: PairingStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(temporaryFolder.root, "pairing_store.preferences_pb")
        }
        store = PairingStore(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `new pairing stores only a verifier and revokes it when forgotten`() = runTest {
        val token = "new-pairing-token"
        val device = PairedDevice(id = "device-1", name = "Phone", deviceUUID = "phone-1")

        store.addAuthorizedPairedDevice(device, token)

        val storedDevice = store.pairedDevices.first().single()
        val verifier = store.hashToken(token)
        assertEquals("", storedDevice.token)
        assertEquals(verifier, storedDevice.tokenVerifier)
        assertTrue(store.isTokenAuthorized(token))
        assertFalse(store.isTokenAuthorized(verifier))
        assertEquals(setOf(verifier), storedStringSet(AUTHORIZED_TOKEN_VERIFIERS))
        assertTrue(storedStringSet(AUTHORIZED_TOKENS).isEmpty())

        store.forgetDevice(storedDevice)

        assertTrue(store.pairedDevices.first().isEmpty())
        assertFalse(store.isTokenAuthorized(token))
        assertTrue(storedStringSet(AUTHORIZED_TOKEN_VERIFIERS).isEmpty())
    }

    @Test
    fun `legacy authorization migrates token and paired device atomically`() = runTest {
        val token = "legacy-token"
        val device = PairedDevice(
            id = "device-2",
            name = "Legacy Phone",
            deviceUUID = "phone-2",
            token = token,
        )
        seedState(device = device, legacyTokens = setOf(token))

        assertTrue(store.isTokenAuthorized(token))

        val verifier = store.hashToken(token)
        val migratedDevice = store.pairedDevices.first().single()
        assertEquals("", migratedDevice.token)
        assertEquals(verifier, migratedDevice.tokenVerifier)
        assertTrue(storedStringSet(AUTHORIZED_TOKENS).isEmpty())
        assertEquals(setOf(verifier), storedStringSet(AUTHORIZED_TOKEN_VERIFIERS))
        assertTrue(store.isTokenAuthorized(token))
        assertFalse(store.isTokenAuthorized(verifier))
    }

    @Test
    fun `existing verifier migrates a leftover plaintext paired device`() = runTest {
        val token = "partially-migrated-token"
        val verifier = store.hashToken(token)
        val device = PairedDevice(
            id = "device-3",
            name = "Partially Migrated Phone",
            deviceUUID = "phone-3",
            token = token,
        )
        seedState(device = device, verifiers = setOf(verifier))

        assertTrue(store.isTokenAuthorized(token))

        val migratedDevice = store.pairedDevices.first().single()
        assertEquals("", migratedDevice.token)
        assertEquals(verifier, migratedDevice.tokenVerifier)
    }

    private suspend fun seedState(
        device: PairedDevice,
        legacyTokens: Set<String> = emptySet(),
        verifiers: Set<String> = emptySet(),
    ) {
        dataStore.edit { prefs ->
            prefs[PAIRED_DEVICES] = protocolJson.encodeToString(
                ListSerializer(PairedDevice.serializer()),
                listOf(device),
            )
            prefs[AUTHORIZED_TOKENS] = protocolJson.encodeToString(
                ListSerializer(String.serializer()),
                legacyTokens.toList(),
            )
            prefs[AUTHORIZED_TOKEN_VERIFIERS] = protocolJson.encodeToString(
                ListSerializer(String.serializer()),
                verifiers.toList(),
            )
        }
    }

    private suspend fun storedStringSet(key: Preferences.Key<String>): Set<String> {
        val json = dataStore.data.first()[key] ?: "[]"
        return protocolJson.decodeFromString<List<String>>(json).toSet()
    }

    companion object {
        private val PAIRED_DEVICES = stringPreferencesKey("paired_devices")
        private val AUTHORIZED_TOKENS = stringPreferencesKey("authorized_tokens")
        private val AUTHORIZED_TOKEN_VERIFIERS = stringPreferencesKey("authorized_token_verifiers")
    }
}
