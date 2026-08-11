package com.playbridge.player.pairing

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.playbridge.player.model.PairedDevice
import com.playbridge.shared.protocol.protocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pairing_store")

/**
 * DataStore-backed storage for pairing information
 */
class PairingStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val defaultDeviceName: String,
) {

    constructor(context: Context) : this(context.dataStore, Build.MODEL)

    internal constructor(dataStore: DataStore<Preferences>) : this(dataStore, "PlayBridge TV")
    
    companion object {
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val SERVER_PORT = intPreferencesKey("server_port")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val PAIRED_DEVICES = stringPreferencesKey("paired_devices")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val AUTHORIZED_TOKENS = stringPreferencesKey("authorized_tokens")
        private val AUTHORIZED_TOKEN_VERIFIERS = stringPreferencesKey("authorized_token_verifiers")

        const val DEFAULT_PORT = com.playbridge.shared.protocol.Config.DEFAULT_PORT
    }
    
    /**
     * Track if the user has seen the pairing screen.
     */
    val isOnboardingDone: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ONBOARDING_DONE] ?: false
    }

    suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { prefs ->
            prefs[ONBOARDING_DONE] = done
        }
    }
    
    /**
     * Get the current device ID (UUID), or create a new one if none exists
     */
    suspend fun getOrCreateDeviceId(): String {
        val current = dataStore.data.first()[DEVICE_ID]
        if (current != null) return current

        val newId = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            prefs[DEVICE_ID] = newId
        }
        return newId
    }

    /**
     * Device ID flow
     */
    val deviceId: Flow<String> = dataStore.data.map { prefs ->
        prefs[DEVICE_ID] ?: getOrCreateDeviceId()
    }

    /**
     * Get the current auth token, or create a new one if none exists
     */
    suspend fun getOrCreateToken(): String {
        val current = dataStore.data.first()[AUTH_TOKEN]
        if (current != null) return current
        
        val newToken = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            prefs[AUTH_TOKEN] = newToken
        }
        return newToken
    }
    
    /**
     * Regenerate the auth token (for security reset)
     */
    suspend fun regenerateToken(): String {
        val newToken = UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            prefs[AUTH_TOKEN] = newToken
        }
        return newToken
    }
    
    /**
     * Server port flow
     */
    val serverPort: Flow<Int> = dataStore.data.map { prefs ->
        prefs[SERVER_PORT]?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
    }
    
    /**
     * Set server port
     */
    suspend fun setServerPort(port: Int) {
        require(port in 1..65535) { "Server port must be between 1 and 65535" }
        dataStore.edit { prefs ->
            prefs[SERVER_PORT] = port
        }
    }
    
    /**
     * Device name flow
     */
    val deviceName: Flow<String> = dataStore.data.map { prefs ->
        prefs[DEVICE_NAME] ?: defaultDeviceName
    }
    
    /**
     * Set device name
     */
    suspend fun setDeviceName(name: String) {
        dataStore.edit { prefs ->
            prefs[DEVICE_NAME] = name
        }
    }
    
    /**
     * Get list of paired devices
     */
    val pairedDevices: Flow<List<PairedDevice>> = dataStore.data.map { prefs ->
        val json = prefs[PAIRED_DEVICES] ?: "[]"
        try {
            protocolJson.decodeFromString<List<PairedDevice>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Authorize and record a paired device without ever persisting the raw token.
     */
    suspend fun addAuthorizedPairedDevice(device: PairedDevice, token: String) {
        val verifier = hashToken(token)
        dataStore.edit { prefs ->
            val verifiers = prefs.decodeStringSet(AUTHORIZED_TOKEN_VERIFIERS)
            verifiers.add(verifier)
            prefs.writeStringSet(AUTHORIZED_TOKEN_VERIFIERS, verifiers)
            prefs.upsertPairedDevice(device.copy(token = "", tokenVerifier = verifier))
        }
    }
    
    /**
     * Forget a paired device: remove from the list and revoke its token so it cannot reconnect.
     */
    suspend fun forgetDevice(device: PairedDevice) {
        dataStore.edit { prefs ->
            val devices = prefs.decodePairedDevices()
            devices.removeAll { it.id == device.id }
            prefs.writePairedDevices(devices)

            val legacyTokens = prefs.decodeStringSet(AUTHORIZED_TOKENS)
            if (device.token.isNotEmpty()) {
                legacyTokens.remove(device.token)
            }
            prefs.writeStringSet(AUTHORIZED_TOKENS, legacyTokens)

            val verifiers = prefs.decodeStringSet(AUTHORIZED_TOKEN_VERIFIERS)
            if (device.tokenVerifier.isNotEmpty()) {
                verifiers.remove(device.tokenVerifier)
            }
            if (device.token.isNotEmpty()) {
                verifiers.remove(hashToken(device.token))
            }
            prefs.writeStringSet(AUTHORIZED_TOKEN_VERIFIERS, verifiers)
        }
    }

    /**
     * Forget all paired devices and clear all authorized tokens.
     */
    suspend fun forgetAllDevices() {
        dataStore.edit { prefs ->
            prefs[PAIRED_DEVICES] = "[]"
            prefs[AUTHORIZED_TOKENS] = "[]"
            prefs[AUTHORIZED_TOKEN_VERIFIERS] = "[]"
        }
    }

    // ── Per-device token authorization ────────────────────────────────────────

    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun Preferences.decodePairedDevices(): MutableList<PairedDevice> {
        val json = this[PAIRED_DEVICES] ?: "[]"
        return try {
            protocolJson.decodeFromString<List<PairedDevice>>(json).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun MutablePreferences.writePairedDevices(devices: List<PairedDevice>) {
        this[PAIRED_DEVICES] = protocolJson.encodeToString(
            ListSerializer(PairedDevice.serializer()),
            devices,
        )
    }

    private fun MutablePreferences.upsertPairedDevice(device: PairedDevice) {
        val devices = decodePairedDevices()
        devices.removeAll {
            (device.deviceUUID.isNotEmpty() && it.deviceUUID == device.deviceUUID) ||
                it.id == device.id
        }
        devices.add(device)
        writePairedDevices(devices)
    }

    private fun Preferences.decodeStringSet(key: Preferences.Key<String>): MutableSet<String> {
        val json = this[key] ?: "[]"
        return try {
            protocolJson.decodeFromString<List<String>>(json).toMutableSet()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun MutablePreferences.writeStringSet(
        key: Preferences.Key<String>,
        values: Set<String>,
    ) {
        this[key] = protocolJson.encodeToString(
            ListSerializer(String.serializer()),
            values.toList(),
        )
    }

    private fun MutablePreferences.migratePairedDeviceToken(token: String, verifier: String) {
        val devices = decodePairedDevices()
        var changed = false
        val migrated = devices.map { device ->
            if (device.token == token) {
                changed = true
                device.copy(token = "", tokenVerifier = verifier)
            } else {
                device
            }
        }
        if (changed) {
            writePairedDevices(migrated)
        }
    }

    suspend fun isTokenAuthorized(token: String): Boolean {
        val verifier = hashToken(token)
        var authorized = false
        dataStore.edit { prefs ->
            val verifiers = prefs.decodeStringSet(AUTHORIZED_TOKEN_VERIFIERS)
            val legacyTokens = prefs.decodeStringSet(AUTHORIZED_TOKENS)
            if (verifier in verifiers) {
                if (legacyTokens.remove(token)) {
                    prefs.writeStringSet(AUTHORIZED_TOKENS, legacyTokens)
                }
                prefs.migratePairedDeviceToken(token, verifier)
                authorized = true
                return@edit
            }

            if (legacyTokens.remove(token)) {
                verifiers.add(verifier)
                prefs.writeStringSet(AUTHORIZED_TOKENS, legacyTokens)
                prefs.writeStringSet(AUTHORIZED_TOKEN_VERIFIERS, verifiers)
                prefs.migratePairedDeviceToken(token, verifier)
                authorized = true
            }
        }
        return authorized
    }
}
