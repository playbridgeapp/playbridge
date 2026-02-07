package com.playbridge.receiver.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.playbridge.receiver.model.protocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "history_store")

@Serializable
data class PlaybackHistoryItem(
    val id: String, // Generally the URL or a hash of it
    val url: String,
    val title: String?,
    val position: Long,
    val duration: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val contentType: String? = null,
    val headers: Map<String, String>? = null
)

class HistoryStore(private val context: Context) {

    companion object {
        private val PLAYBACK_HISTORY = stringPreferencesKey("playback_history")
        private const val MAX_HISTORY_SIZE = 50
    }

    val history: Flow<List<PlaybackHistoryItem>> = context.historyDataStore.data.map { prefs ->
        val json = prefs[PLAYBACK_HISTORY] ?: "[]"
        try {
            protocolJson.decodeFromString<List<PlaybackHistoryItem>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveProgress(
        url: String,
        title: String?,
        position: Long,
        duration: Long,
        contentType: String?,
        headers: Map<String, String>?
    ) {
        if (url.isBlank()) return
        
        // Don't save if position is negligible (e.g. < 5s) or fully complete (depend on logic, but here we just save)
        // We'll filter "completed" items in UI or logic if needed, but saving everything is safer.
        
        val id = url // Use URL as ID for simplicity
        val newItem = PlaybackHistoryItem(
            id = id,
            url = url,
            title = title,
            position = position,
            duration = duration,
            timestamp = System.currentTimeMillis(),
            contentType = contentType,
            headers = headers
        )

        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            // Remove existing item with same ID to update it (move to top)
            currentList.removeAll { it.id == id }
            
            // Add to beginning
            currentList.add(0, newItem)
            
            // Trim size
            if (currentList.size > MAX_HISTORY_SIZE) {
                currentList.subList(MAX_HISTORY_SIZE, currentList.size).clear()
            }

            prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                ListSerializer(PlaybackHistoryItem.serializer()),
                currentList
            )
        }
    }
    
    suspend fun removeItem(id: String) {
        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            if (currentList.removeAll { it.id == id }) {
                 prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                    ListSerializer(PlaybackHistoryItem.serializer()),
                    currentList
                )
            }
        }
    }
    
    suspend fun clearHistory() {
        context.historyDataStore.edit { prefs ->
            prefs.remove(PLAYBACK_HISTORY)
        }
    }
}
