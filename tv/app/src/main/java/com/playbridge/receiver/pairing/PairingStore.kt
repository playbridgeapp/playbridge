package com.playbridge.receiver.pairing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.playbridge.receiver.model.PairedDevice
import com.playbridge.protocol.protocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pairing_store")

/**
 * DataStore-backed storage for pairing information
 */
class PairingStore(private val context: Context) {
    
    companion object {
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val SERVER_PORT = intPreferencesKey("server_port")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val PAIRED_DEVICES = stringPreferencesKey("paired_devices")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        
        const val DEFAULT_PORT = com.playbridge.protocol.Config.DEFAULT_PORT
    }
    
    /**
     * Get the current device ID (UUID), or create a new one if none exists
     */
    suspend fun getOrCreateDeviceId(): String {
        val current = context.dataStore.data.first()[DEVICE_ID]
        if (current != null) return current

        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { prefs ->
            prefs[DEVICE_ID] = newId
        }
        return newId
    }

    /**
     * Device ID flow
     */
    val deviceId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_ID] ?: getOrCreateDeviceId()
    }

    /**
     * Get the current auth token, or create a new one if none exists
     */
    suspend fun getOrCreateToken(): String {
        val current = context.dataStore.data.first()[AUTH_TOKEN]
        if (current != null) return current
        
        val newToken = UUID.randomUUID().toString()
        context.dataStore.edit { prefs ->
            prefs[AUTH_TOKEN] = newToken
        }
        return newToken
    }
    
    /**
     * Regenerate the auth token (for security reset)
     */
    suspend fun regenerateToken(): String {
        val newToken = UUID.randomUUID().toString()
        context.dataStore.edit { prefs ->
            prefs[AUTH_TOKEN] = newToken
        }
        return newToken
    }
    
    /**
     * Server port flow
     */
    val serverPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SERVER_PORT] ?: DEFAULT_PORT
    }
    
    /**
     * Set server port
     */
    suspend fun setServerPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[SERVER_PORT] = port
        }
    }
    
    /**
     * Device name flow
     */
    val deviceName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_NAME] ?: android.os.Build.MODEL
    }
    
    /**
     * Set device name
     */
    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_NAME] = name
        }
    }
    
    /**
     * Get list of paired devices
     */
    val pairedDevices: Flow<List<PairedDevice>> = context.dataStore.data.map { prefs ->
        val json = prefs[PAIRED_DEVICES] ?: "[]"
        try {
            protocolJson.decodeFromString<List<PairedDevice>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Add a paired device
     */
    suspend fun addPairedDevice(device: PairedDevice) {
        context.dataStore.edit { prefs ->
            val current = prefs[PAIRED_DEVICES]?.let {
                try {
                    protocolJson.decodeFromString<List<PairedDevice>>(it).toMutableList()
                } catch (e: Exception) {
                    mutableListOf()
                }
            } ?: mutableListOf()
            
            // Remove existing device with same ID and add new one
            current.removeAll { it.id == device.id }
            current.add(device)
            
            prefs[PAIRED_DEVICES] = protocolJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(PairedDevice.serializer()),
                current
            )
        }
    }
    
    /**
     * Remove a paired device
     */
    suspend fun removePairedDevice(deviceId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PAIRED_DEVICES]?.let {
                try {
                    protocolJson.decodeFromString<List<PairedDevice>>(it).toMutableList()
                } catch (e: Exception) {
                    mutableListOf()
                }
            } ?: mutableListOf()
            
            current.removeAll { it.id == deviceId }
            
            prefs[PAIRED_DEVICES] = protocolJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(PairedDevice.serializer()),
                current
            )
        }
    }
}
