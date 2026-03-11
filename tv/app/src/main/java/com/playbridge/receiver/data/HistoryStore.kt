package com.playbridge.receiver.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.playbridge.protocol.protocolJson
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
    val headers: Map<String, String>? = null,
    val thumbnailPath: String? = null,
    val playlistJson: String? = null,
    val playlistIndex: Int = 0,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val externalSubtitleUrl: String? = null,
    val videoFilter: String? = null,
    val customFilterValues: List<Float>? = null,
    val playbackSpeed: Float? = null,
    val videoScalingMode: Int? = null,
    val isFavorite: Boolean = false
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
        headers: Map<String, String>?,
        thumbnailPath: String? = null,
        playlistJson: String? = null,
        playlistIndex: Int = 0,
        preferredAudioLanguage: String? = null,
        preferredSubtitleLanguage: String? = null,
        externalSubtitleUrl: String? = null,
        videoFilter: String? = null,
        customFilterValues: List<Float>? = null,
        playbackSpeed: Float? = null,
        videoScalingMode: Int? = null
    ) {
        if (url.isBlank()) return
        
        // For playlist items, use a stable ID based on the playlist content
        // so all episodes update the same history entry instead of creating duplicates.
        val id = if (playlistJson != null) {
            "playlist_${playlistJson.hashCode()}"
        } else {
            url
        }
        
        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            // check for existing item to preserve thumbnail if new one is not provided
            val existingItem = currentList.find { it.id == id }
            val finalThumbnailPath = thumbnailPath ?: existingItem?.thumbnailPath

            val newItem = PlaybackHistoryItem(
                id = id,
                url = url,
                title = title,
                position = position,
                duration = duration,
                timestamp = System.currentTimeMillis(),
                contentType = contentType,
                headers = headers,
                thumbnailPath = finalThumbnailPath,
                playlistJson = playlistJson,
                playlistIndex = playlistIndex,
                preferredAudioLanguage = preferredAudioLanguage,
                preferredSubtitleLanguage = preferredSubtitleLanguage,
                externalSubtitleUrl = externalSubtitleUrl,
                videoFilter = videoFilter,
                customFilterValues = customFilterValues,
                playbackSpeed = playbackSpeed,
                videoScalingMode = videoScalingMode,
                isFavorite = existingItem?.isFavorite ?: false
            )

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

    suspend fun toggleFavorite(id: String) {
        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            val index = currentList.indexOfFirst { it.id == id }
            if (index != -1) {
                val item = currentList[index]
                currentList[index] = item.copy(isFavorite = !item.isFavorite)
                prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                    ListSerializer(PlaybackHistoryItem.serializer()),
                    currentList
                )
            }
        }
    }
}
