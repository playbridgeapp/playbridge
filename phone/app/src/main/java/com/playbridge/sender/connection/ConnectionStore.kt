package com.playbridge.sender.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.model.protocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "connection_store")

/**
 * DataStore-backed storage for TV connection info
 */
class ConnectionStore(private val context: Context) {
    
    companion object {
        private val TV_DEVICE = stringPreferencesKey("tv_device")
        private val DISCOVERED_DEVICES = stringPreferencesKey("discovered_devices")
        private val DEVICE_HISTORY = stringPreferencesKey("device_history")
    }
    
    /**
     * Get stored TV device
     */
    val tvDevice: Flow<TvDevice?> = context.dataStore.data.map { prefs ->
        prefs[TV_DEVICE]?.let {
            try {
                protocolJson.decodeFromString<TvDevice>(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Get device history
     */
    val deviceHistory: Flow<List<TvDevice>> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_HISTORY]?.let {
            try {
                protocolJson.decodeFromString<List<TvDevice>>(it)
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }
    
    /**
     * Save TV device
     */
    suspend fun saveTvDevice(device: TvDevice) {
        context.dataStore.edit { prefs ->
            prefs[TV_DEVICE] = protocolJson.encodeToString(TvDevice.serializer(), device)
        }
    }

    /**
     * Add to history
     */
    /**
     * Add to history
     */
    suspend fun addToHistory(device: TvDevice) {
        context.dataStore.edit { prefs ->
            val historyJson = prefs[DEVICE_HISTORY]
            val currentHistory = if (historyJson != null) {
                try {
                    protocolJson.decodeFromString<List<TvDevice>>(historyJson)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            // Remove existing entry for same IP/Port if exists
            val filtered = currentHistory.filterNot { it.ip == device.ip && it.port == device.port }
            
            // Add to front
            val newHistory = (listOf(device) + filtered).take(10)
            
            prefs[DEVICE_HISTORY] = protocolJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(TvDevice.serializer()), 
                newHistory
            )
        }
    }

    /**
     * Remove from history
     */
    suspend fun removeFromHistory(device: TvDevice) {
        context.dataStore.edit { prefs ->
            val historyJson = prefs[DEVICE_HISTORY] ?: return@edit
            val currentHistory = try {
                protocolJson.decodeFromString<List<TvDevice>>(historyJson)
            } catch (e: Exception) {
                emptyList()
            }

            val newHistory = currentHistory.filterNot { it.ip == device.ip && it.port == device.port }
            
            prefs[DEVICE_HISTORY] = protocolJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(TvDevice.serializer()), 
                newHistory
            )
        }
    }
    
    /**
     * Clear stored TV device
     */
    suspend fun clearTvDevice() {
        context.dataStore.edit { prefs ->
            prefs.remove(TV_DEVICE)
        }
    }
    
    /**
     * Check if we have a stored TV device
     */
    suspend fun hasTvDevice(): Boolean {
        return context.dataStore.data.first()[TV_DEVICE] != null
    }
}
