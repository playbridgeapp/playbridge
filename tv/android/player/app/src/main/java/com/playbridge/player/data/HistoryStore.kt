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
    // Kept only because it was bookmarked: a private cast (skip_history) or one made while
    // history is off. It is removed, not demoted to history, when the bookmark is removed.
    val bookmarkOnly: Boolean = false,
    // TV-side choices made after the cast began. Nullable keeps history written by older
    // versions readable and lets current global player/rendering settings remain authoritative.
    val playbackContext: PlaybackContext? = null,
)

internal fun historyDurationForSave(duration: Long, existingDuration: Long?): Long =
    duration.takeIf { it > 0L } ?: existingDuration ?: 0L

/**
 * Whether a play may be written. A bookmarked entry always keeps its place up to date;
 * anything else is written only when the cast is [recordable] (history on, not private).
 */
internal fun historyWriteAllowed(recordable: Boolean, existing: PlaybackHistoryItem?): Boolean =
    recordable || existing?.isFavorite == true

/**
 * The list after the bookmark on [id] is toggled. Removing the bookmark from a
 * bookmark-only entry removes the entry, so a private cast never falls into history.
 */
internal fun historyAfterBookmarkToggle(
    items: List<PlaybackHistoryItem>,
    id: String,
): List<PlaybackHistoryItem> = items.mapNotNull { item ->
    when {
        item.id != id -> item
        item.isFavorite && item.bookmarkOnly -> null
        else -> item.copy(isFavorite = !item.isFavorite)
    }
}

/** What "Clear watch history" keeps: bookmarked entries only. Everything else is dropped. */
internal fun historyKeptWhenCleared(items: List<PlaybackHistoryItem>): List<PlaybackHistoryItem> =
    items.filter { it.isFavorite }

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
        val logKey = historyLogKey(id)
        if (url.isBlank() || payloadJson.isBlank()) {
            FileLogger.w(
                TAG,
                "Context persistence skipped for entry=$logKey: " +
                    "urlPresent=${url.isNotBlank()}, payloadPresent=${payloadJson.isNotBlank()}",
            )
            return
        }

        // Private casts and plays made while history is off are written only to keep an
        // existing bookmark's place up to date.
        val recordable = isRecordable(payloadJson)

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
            if (!historyWriteAllowed(recordable, existingItem)) {
                FileLogger.d(TAG, "Context persistence skipped for entry=$logKey: not recordable")
                return@edit
            }
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
                bookmarkOnly = !recordable,
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
    
    /** History on and the cast not sent as private (skip_history). */
    private fun isRecordable(payloadJson: String): Boolean {
        val private = com.playbridge.shared.protocol.decodePlaylistPayloadJson(payloadJson)
            ?.items?.any { it.skip_history == true } == true
        val historyOn = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            .getBoolean("enable_history", true)
        return !private && historyOn
    }

    /**
     * Bookmarks what is playing, whatever kind of cast it is. An entry that already exists is
     * flagged; otherwise one is created, marked bookmark-only when the play itself must not
     * appear in history.
     */
    suspend fun bookmark(
        id: String,
        payloadJson: String,
        url: String,
        title: String?,
        position: Long,
        duration: Long,
        thumbnailUrl: String? = null,
        playbackContext: PlaybackContext? = null,
    ) {
        if (url.isBlank() || payloadJson.isBlank()) return
        val recordable = isRecordable(payloadJson)
        context.historyDataStore.edit { prefs ->
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(
                    prefs[PLAYBACK_HISTORY] ?: "[]",
                ).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            val index = currentList.indexOfFirst { it.id == id }
            if (index >= 0) {
                currentList[index] = currentList[index].copy(isFavorite = true)
            } else {
                currentList.add(
                    0,
                    PlaybackHistoryItem(
                        id = id,
                        payloadJson = payloadJson,
                        url = url,
                        title = title,
                        position = position.coerceAtLeast(0L),
                        duration = duration.coerceAtLeast(0L),
                        thumbnailUrl = thumbnailUrl,
                        isFavorite = true,
                        bookmarkOnly = !recordable,
                        playbackContext = playbackContext,
                    ),
                )
            }
            prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                ListSerializer(PlaybackHistoryItem.serializer()),
                currentList,
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
    
    /** Removes every entry that is not a bookmark (and its thumbnail); bookmarks are kept. */
    suspend fun clearHistory() {
        val removedIds = mutableListOf<String>()
        context.historyDataStore.edit { prefs ->
            val currentJson = prefs[PLAYBACK_HISTORY] ?: "[]"
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(currentJson)
            } catch (e: Exception) {
                emptyList()
            }

            val favoritesOnly = historyKeptWhenCleared(currentList)
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
        var removed = false
        context.historyDataStore.edit { prefs ->
            val currentList = try {
                protocolJson.decodeFromString<List<PlaybackHistoryItem>>(prefs[PLAYBACK_HISTORY] ?: "[]")
            } catch (e: Exception) {
                emptyList()
            }
            if (currentList.none { it.id == id }) return@edit
            // Removing a bookmark keeps the entry in history, except for a bookmark-only
            // entry (a private cast), which is removed with its bookmark.
            val updated = historyAfterBookmarkToggle(currentList, id)
            removed = updated.none { it.id == id }
            prefs[PLAYBACK_HISTORY] = protocolJson.encodeToString(
                ListSerializer(PlaybackHistoryItem.serializer()),
                updated,
            )
        }
        if (removed) thumbnailStore.remove(id)
    }
}
