package com.playbridge.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.playbridge.player.logging.FileLogger
import com.playbridge.shared.protocol.protocolJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private const val TAG = "HistoryStore"

// v2: history now stores the raw PlaylistPayload JSON the phone sent (source of truth for
// replay) plus the TV-side progress and a little UI metadata — nothing flattened. The name
// bump deliberately abandons the old v1 schema (no migration: old entries are simply never
// read, i.e. history starts clean after upgrade).
private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "history_store_v2")

@Serializable
data class PlaybackTrackPreference(
    val id: String? = null,
    val label: String? = null,
    val language: String? = null,
)

@Serializable
data class PlaybackContext(
    val audioTrack: PlaybackTrackPreference? = null,
    val subtitleTrack: PlaybackTrackPreference? = null,
    val subtitlesDisabled: Boolean = false,
    val externalSubtitleUrl: String? = null,
    val playbackSpeed: Float? = null,
    val videoScalingMode: String? = null,
    val videoQualityMaxHeight: Int? = null,
    val subtitleDelayMs: Long? = null,
    val isLooping: Boolean? = null,
)

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
    // Remote poster/backdrop URL or a private captured-frame file URL.
    val thumbnailUrl: String? = null,
    // Changes only when a captured frame replaces the thumbnail. The Library uses this as
    // its image-cache key so rewriting the same private JPEG path becomes visible at once.
    val thumbnailRevision: Long = 0L,
    val isFavorite: Boolean = false,
    // TV-side choices made after the cast began. Nullable keeps history written by older
    // versions readable and lets current global player/rendering settings remain authoritative.
    val playbackContext: PlaybackContext? = null,
)

internal fun historyDurationForSave(duration: Long, existingDuration: Long?): Long =
    duration.takeIf { it > 0L } ?: existingDuration ?: 0L

class HistoryStore(private val context: Context) {
    private val thumbnailStore by lazy { HistoryThumbnailStore(context) }

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
        thumbnailUrl: String? = null,
        playbackContext: PlaybackContext? = null,
    ) {
        if (com.playbridge.shared.protocol.decodePlaylistPayloadJson(payloadJson)
                ?.items?.any { it.skip_history == true } == true
        ) return
        val logKey = historyLogKey(id)
        if (url.isBlank() || payloadJson.isBlank()) {
            FileLogger.w(
                TAG,
                "Context persistence skipped for entry=$logKey: " +
                    "urlPresent=${url.isNotBlank()}, payloadPresent=${payloadJson.isNotBlank()}",
            )
            return
        }

        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enable_history", true)) {
            FileLogger.d(TAG, "Context persistence skipped for entry=$logKey: history disabled")
            return
        }

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
            // Landing is recorded before the renderer knows its duration. Preserve the known
            // duration so immediately backing out of a resumed item does not temporarily move
            // it out of Continue Watching.
            val finalDuration = historyDurationForSave(duration, existingItem?.duration)
            val finalPlaybackContext = playbackContext ?: existingItem?.playbackContext

            FileLogger.i(
                TAG,
                "Persisting playback context entry=$logKey, position=$position/$finalDuration, " +
                    "incoming=${playbackContext.toSafeLogString()}, " +
                    "existing=${existingItem?.playbackContext.toSafeLogString()}, " +
                    "final=${finalPlaybackContext.toSafeLogString()}",
            )

            val newItem = PlaybackHistoryItem(
                id = id,
                payloadJson = payloadJson,
                url = url,
                title = title,
                position = position,
                duration = finalDuration,
                timestamp = System.currentTimeMillis(),
                thumbnailUrl = finalThumbnailUrl,
                thumbnailRevision = existingItem?.thumbnailRevision ?: 0L,
                isFavorite = existingItem?.isFavorite ?: false,
                playbackContext = finalPlaybackContext,
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
        var removed = false
        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            if (currentList.removeAll { it.id == id }) {
                 removed = true
                 prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                    ListSerializer(PlaybackHistoryItem.serializer()),
                    currentList
                )
            }
        }
        if (removed) thumbnailStore.remove(id)
    }
    
    suspend fun clearHistory() {
        val removedIds = mutableListOf<String>()
        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson)
            } catch (e: Exception) {
                emptyList()
            }

            val favoritesOnly = currentList.filter { it.isFavorite }
            removedIds += currentList.asSequence()
                .filterNot(PlaybackHistoryItem::isFavorite)
                .map(PlaybackHistoryItem::id)

            if (favoritesOnly.isEmpty()) {
                prefs.remove(PLAYBACK_HISTORY)
            } else {
                prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                    ListSerializer(PlaybackHistoryItem.serializer()),
                    favoritesOnly
                )
            }
        }
        thumbnailStore.removeAll(removedIds)
    }

    suspend fun updateThumbnail(id: String, thumbnailUrl: String) {
        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            val index = currentList.indexOfFirst { it.id == id }
            if (index >= 0) {
                currentList[index] = currentList[index].copy(
                    thumbnailUrl = thumbnailUrl,
                    thumbnailRevision = System.currentTimeMillis(),
                )
                prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                    ListSerializer(PlaybackHistoryItem.serializer()),
                    currentList,
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
                // Favorite is an independent Library attribute. Removing the flag must not
                // delete playback history or make an item disappear from Recent.
                currentList[index] = item.copy(isFavorite = !item.isFavorite)

                prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                    ListSerializer(PlaybackHistoryItem.serializer()),
                    currentList
                )
            }
        }
    }
}
