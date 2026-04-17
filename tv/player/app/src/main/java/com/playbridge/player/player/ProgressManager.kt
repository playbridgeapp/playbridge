package com.playbridge.player.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.TextureView
import androidx.lifecycle.LifecycleCoroutineScope
import com.playbridge.player.data.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ProgressManager"

/**
 * Manages playback progress persistence (save/restore) and thumbnail capture.
 */
class ProgressManager(
    private val context: Context,
    private val historyStore: HistoryStore,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val playerActivity: PlayerActivity
) {
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var currentContentType: String? = null
    private var currentHeaders: Map<String, String>? = null
    private var currentPlaylistJson: String? = null
    private var currentPlaylistIndex: Int = 0
    private var currentPreferredAudioLanguage: String? = null
    private var currentPreferredSubtitleLanguage: String? = null
    private var currentExternalSubtitleUrl: String? = null
    private var currentVideoFilter: String? = null
    private var currentCustomFilterValues: List<Float>? = null
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
        currentUrl = url
        currentTitle = title
        currentContentType = contentType
        currentHeaders = headers
        currentPlaylistJson = playlistJson
        currentPlaylistIndex = playlistIndex
        currentPreferredAudioLanguage = preferredAudioLanguage
        currentPreferredSubtitleLanguage = preferredSubtitleLanguage
        currentExternalSubtitleUrl = externalSubtitleUrl
        currentVideoFilter = videoFilter
        currentCustomFilterValues = customFilterValues
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
        videoFilter: String? = null,
        customFilterValues: List<Float>? = null,
        playbackSpeed: Float? = null,
        videoScalingMode: Int? = null
    ) {
        currentPreferredAudioLanguage = preferredAudioLanguage
        currentPreferredSubtitleLanguage = preferredSubtitleLanguage
        currentExternalSubtitleUrl = externalSubtitleUrl
        currentVideoFilter = videoFilter
        currentCustomFilterValues = customFilterValues
        currentPlaybackSpeed = playbackSpeed
        currentVideoScalingMode = videoScalingMode
    }

    /**
     * Attempt to restore playback position from history for the given [url].
     * Returns the history item if found, so callers can restore other settings.
     */
    suspend fun restoreProgress(url: String): com.playbridge.player.data.PlaybackHistoryItem? {
        return try {
            val history = historyStore.history.first()
            val item = history.find { it.url == url }
            if (item != null && item.position > 5000 && item.position < (item.duration - 5000)) {
                Log.i(TAG, "Resuming from history: ${item.position}ms")
                playerActivity.seekTo(item.position)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Resuming playback", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            item
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore history", e)
            null
        }
    }

    /**
     * Persist current playback position and optional thumbnail to history.
     */
    fun saveProgress(thumbnailBitmap: Bitmap? = null) {
        val duration = playerActivity.getMediaDuration()
        val position = playerActivity.getCurrentPosition()
        val url = currentUrl
        Log.d(TAG, "Attempting to save progress for $url")
        if (url != null && duration > 0 && position > 0) {
            val title = currentTitle
            val contentType = currentContentType
            val headers = currentHeaders

            lifecycleScope.launch {
                withContext(NonCancellable) {
                    var thumbnailPath: String? = null

                    if (thumbnailBitmap != null) {
                        thumbnailPath = saveBitmapToStorage(thumbnailBitmap)
                        Log.d(TAG, "Saved thumbnail to: $thumbnailPath")
                    }

                    Log.d(TAG, "Saving progress: $position / $duration")

                    withContext(Dispatchers.IO) {
                        try {
                            historyStore.saveProgress(
                                url, title, position, duration, contentType, headers,
                                thumbnailPath, currentPlaylistJson, currentPlaylistIndex,
                                currentPreferredAudioLanguage, currentPreferredSubtitleLanguage,
                                currentExternalSubtitleUrl, currentVideoFilter, currentCustomFilterValues,
                                currentPlaybackSpeed, currentVideoScalingMode
                            )
                            Log.d(TAG, "Progress saved successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save progress", e)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Not saving: URL=$url, Duration=${duration}, Pos=${position}")
        }
    }

    /**
     * Capture a bitmap from the PlayerView's SurfaceView asynchronously.
     */
    suspend fun captureBitmapSuspend(): Bitmap? {
        val surfaceView = playerActivity.getVideoSurfaceView() ?: return null
        val holder = surfaceView.holder
        val surface = holder.surface
        if (surface == null || !surface.isValid) {
            Log.d(TAG, "Cannot capture bitmap: surface is invalid or null")
            return null
        }

        val width = surfaceView.width
        val height = surfaceView.height

        if (width <= 0 || height <= 0) return null

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(surfaceView, bitmap, { copyResult ->
                    if (copyResult == android.view.PixelCopy.SUCCESS) {
                        Log.d(TAG, "Captured bitmap from SurfaceView via PixelCopy")
                        continuation.resume(bitmap) { }
                    } else {
                        Log.e(TAG, "PixelCopy failed with error code: $copyResult")
                        continuation.resume(null) { }
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Failed to capture screenshot: ${e.message}")
                continuation.resume(null) { }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture screenshot", e)
                continuation.resume(null) { }
            }
        }
    }

    private suspend fun saveBitmapToStorage(bitmap: Bitmap): String? {
        return withContext(Dispatchers.IO) {
            try {
                val thumbnailsDir = File(context.filesDir, "thumbnails")
                if (!thumbnailsDir.exists()) thumbnailsDir.mkdirs()

                val filename = "${currentUrl?.hashCode() ?: System.currentTimeMillis()}.jpg"
                val file = File(thumbnailsDir, filename)

                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }

                Log.d(TAG, "Saved bitmap size: ${file.length()} bytes")
                file.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save bitmap", e)
                null
            }
        }
    }
}
