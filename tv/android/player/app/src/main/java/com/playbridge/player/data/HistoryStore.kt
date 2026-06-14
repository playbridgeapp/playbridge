package com.playbridge.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.playbridge.shared.protocol.protocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

// v2: history now stores the raw PlaylistPayload JSON the phone sent (source of truth for
// replay) plus the TV-side progress and a little UI metadata — nothing flattened. The name
// bump deliberately abandons the old v1 schema (no migration: old entries are simply never
// read, i.e. history starts clean after upgrade).
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "history_store_v2")

@Serializable
data class PlaybackHistoryItem(
    val id: String, // Stable, index-independent key (PlayerLauncher.historyId)
    // The exact PlaylistPayload (items + start_index + visual_metadata) the phone sent.
    // Replay decodes this and feeds it back through the same launch path as a live cast,
    // so subtitles / audio language / headers all come back unchanged.
    val payloadJson: String,
    val url: String,      // first item's URL — for resume lookup + history-card display
    val title: String?,   // denormalized purely so the list renders without decoding
    val position: Long,   // TV-side progress (not part of the phone payload)
    val duration: Long,   // TV-side progress
    val timestamp: Long = System.currentTimeMillis(),
    // Remote poster/backdrop URL from the payload's visual_metadata (null for browser-
    // detected videos with no metadata — the card falls back to a play glyph).
    val thumbnailUrl: String? = null,
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
        id: String,
        payloadJson: String,
        url: String,
        title: String?,
        position: Long,
        duration: Long,
        thumbnailUrl: String? = null
    ) {
        if (url.isBlank() || payloadJson.isBlank()) return

        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            // Preserve an existing artwork URL if the caller didn't supply one (e.g. the
            // periodic position-only save), and keep the existing favorite flag.
            val existingItem = currentList.find { it.id == id }
            val finalThumbnailUrl = thumbnailUrl ?: existingItem?.thumbnailUrl

            val newItem = PlaybackHistoryItem(
                id = id,
                payloadJson = payloadJson,
                url = url,
                title = title,
                position = position,
                duration = duration,
                timestamp = System.currentTimeMillis(),
                thumbnailUrl = finalThumbnailUrl,
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
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson)
            } catch (e: Exception) {
                emptyList()
            }

            val favoritesOnly = currentList.filter { it.isFavorite }

            if (favoritesOnly.isEmpty()) {
                prefs.remove(PLAYBACK_HISTORY)
            } else {
                prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                    ListSerializer(PlaybackHistoryItem.serializer()),
                    favoritesOnly
                )
            }
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
                if (item.isFavorite) {
                    // Unfavoriting an item completely deletes it
                    currentList.removeAt(index)
                } else {
                    currentList[index] = item.copy(isFavorite = true)
                }

                prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                    ListSerializer(PlaybackHistoryItem.serializer()),
                    currentList
                )
            }
        }
    }
}
