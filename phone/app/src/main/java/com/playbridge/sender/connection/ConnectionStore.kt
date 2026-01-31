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
     * Save TV device
     */
    suspend fun saveTvDevice(device: TvDevice) {
        context.dataStore.edit { prefs ->
            prefs[TV_DEVICE] = protocolJson.encodeToString(TvDevice.serializer(), device)
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
