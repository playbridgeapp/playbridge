package com.playbridge.player.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.playbridge.player.data.HistoryStore
import com.playbridge.shared.logging.redactUrlForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ProgressManager"

interface PlaybackProgressSource {
    fun getMediaDuration(): Long
    fun getCurrentPosition(): Long
    fun seekTo(position: Long)
}

/**
 * Manages playback progress persistence (save/restore). Artwork can be supplied by the
 * payload or updated by the host's lightweight frame-capture policy.
 */
class ProgressManager(
    private val context: Context,
    private val historyStore: HistoryStore,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val playbackSource: PlaybackProgressSource,
) {
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var currentContentType: String? = null
    private var currentHeaders: Map<String, String>? = null
    // The raw PlaylistPayload JSON + stable history key for the current item — what gets
    // persisted to history (everything else below is live-session state for switchPlayer).
    private var currentPayloadJson: String? = null
    private var currentHistoryId: String? = null
    private var currentThumbnailUrl: String? = null
    private var currentPreferredAudioLanguage: String? = null
    private var currentPreferredSubtitleLanguage: String? = null
    private var currentExternalSubtitleUrl: String? = null
    private var currentPlaybackSpeed: Float? = null
    private var currentVideoScalingMode: Int? = null

    val url: String? get() = currentUrl
    val title: String? get() = currentTitle
    val contentType: String? get() = currentContentType
    val headers: Map<String, String>? get() = currentHeaders
    val preferredAudioLanguage: String? get() = currentPreferredAudioLanguage
    val preferredSubtitleLanguage: String? get() = currentPreferredSubtitleLanguage
    val externalSubtitleUrl: String? get() = currentExternalSubtitleUrl
    val playbackSpeed: Float? get() = currentPlaybackSpeed
    val videoScalingMode: Int? get() = currentVideoScalingMode

    /**
     * Store metadata for the currently playing video so it can be saved to history.
     */
    fun setCurrentMedia(
        url: String,
        title: String?,
        contentType: String?,
        headers: Map<String, String>?,
        payloadJson: String,
        historyId: String,
        thumbnailUrl: String? = null,
        preferredAudioLanguage: String? = null,
        preferredSubtitleLanguage: String? = null,
        externalSubtitleUrl: String? = null,
        playbackSpeed: Float? = null,
        videoScalingMode: Int? = null
    ) {
        currentUrl = url
        currentTitle = title
        currentContentType = contentType
        currentHeaders = headers
        currentPayloadJson = payloadJson
        currentHistoryId = historyId
        currentThumbnailUrl = thumbnailUrl
        currentPreferredAudioLanguage = preferredAudioLanguage
        currentPreferredSubtitleLanguage = preferredSubtitleLanguage
        currentExternalSubtitleUrl = externalSubtitleUrl
        currentPlaybackSpeed = playbackSpeed
        currentVideoScalingMode = videoScalingMode
    }

    /**
     * Update just the selection metadata (call before saving progress).
     */
    fun updateSelections(
        preferredAudioLanguage: String? = null,
        preferredSubtitleLanguage: String? = null,
        externalSubtitleUrl: String? = null,
        playbackSpeed: Float? = null,
        videoScalingMode: Int? = null
    ) {
        currentPreferredAudioLanguage = preferredAudioLanguage
        currentPreferredSubtitleLanguage = preferredSubtitleLanguage
        currentExternalSubtitleUrl = externalSubtitleUrl
        currentPlaybackSpeed = playbackSpeed
        currentVideoScalingMode = videoScalingMode
    }

    /** Update cached artwork after a one-time frame capture. */
    fun updateThumbnail(thumbnailUrl: String) {
        currentThumbnailUrl = thumbnailUrl
    }

    /**
     * Attempt to restore playback position from history for the given [url].
     * Returns the history item if found, so callers can restore other settings.
     */
    suspend fun restoreProgress(
        url: String,
        seek: (Long) -> Unit = playbackSource::seekTo,
    ): com.playbridge.player.data.PlaybackHistoryItem? {
        return try {
            val history = historyStore.history.first()
            val item = history.find { it.url == url }
            if (item != null && item.position > 5000 && item.position < (item.duration - 5000)) {
                Log.i(TAG, "Resuming from history: ${item.position}ms")
                seek(item.position)
            }
            item
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to restore history", e)
            null
        }
    }

    /**
     * Write the history entry the moment playback lands, before any position progresses.
     * This guarantees the item exists (with its artwork and start position) even if the
     * app is force-killed before the first periodic save — the lifecycle callbacks that
     * used to be the only save points are skipped on a force-stop / swipe-away.
     */
    fun recordLanded(startPositionMs: Long) {
        val url = currentUrl ?: return
        val payloadJson = currentPayloadJson ?: return
        val historyId = currentHistoryId ?: return
        val title = currentTitle
        val thumbnailUrl = currentThumbnailUrl
        lifecycleScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    historyStore.saveProgress(
                        id = historyId,
                        payloadJson = payloadJson,
                        url = url,
                        title = title,
                        position = startPositionMs.coerceAtLeast(0L),
                        duration = playbackSource.getMediaDuration().coerceAtLeast(0L),
                        thumbnailUrl = thumbnailUrl,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to record landed item", e)
                }
            }
        }
    }

    /**
     * Persist the current playback position and whichever artwork the host most recently set.
     */
    fun saveProgress() {
        val duration = playbackSource.getMediaDuration()
        val position = playbackSource.getCurrentPosition()
        val url = currentUrl
        val payloadJson = currentPayloadJson
        val historyId = currentHistoryId
        if (url != null && payloadJson != null && historyId != null && duration > 0 && position > 0) {
            val title = currentTitle
            val thumbnailUrl = currentThumbnailUrl
            lifecycleScope.launch {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        historyStore.saveProgress(
                            id = historyId,
                            payloadJson = payloadJson,
                            url = url,
                            title = title,
                            position = position,
                            duration = duration,
                            thumbnailUrl = thumbnailUrl,
                        )
                        Log.d(TAG, "Saved progress: $position / $duration")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save progress", e)
                    }
                }
            }
        } else {
            Log.d(
                TAG,
                "Not saving: URL=${redactUrlForLog(url)}, payload=${payloadJson != null}, " +
                    "Duration=$duration, Pos=$position",
            )
        }
    }
}
