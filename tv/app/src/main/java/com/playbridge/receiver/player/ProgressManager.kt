package com.playbridge.receiver.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.TextureView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.playbridge.receiver.data.HistoryStore
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
    private val playerView: PlayerView,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val playerProvider: () -> ExoPlayer?
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
        customFilterValues: List<Float>? = null
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
    }

    /**
     * Update just the selection metadata (call before saving progress).
     */
    fun updateSelections(
        preferredAudioLanguage: String? = null,
        preferredSubtitleLanguage: String? = null,
        externalSubtitleUrl: String? = null,
        videoFilter: String? = null,
        customFilterValues: List<Float>? = null
    ) {
        currentPreferredAudioLanguage = preferredAudioLanguage
        currentPreferredSubtitleLanguage = preferredSubtitleLanguage
        currentExternalSubtitleUrl = externalSubtitleUrl
        currentVideoFilter = videoFilter
        currentCustomFilterValues = customFilterValues
    }

    /**
     * Attempt to restore playback position from history for the given [url].
     */
    fun restoreProgress(url: String) {
        lifecycleScope.launch {
            try {
                val history = historyStore.history.first()
                val item = history.find { it.url == url }
                if (item != null && item.position > 5000 && item.position < (item.duration - 5000)) {
                    Log.i(TAG, "Resuming from history: ${item.position}ms")
                    playerProvider()?.seekTo(item.position)
                    android.widget.Toast.makeText(context, "Resuming playback", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore history", e)
            }
        }
    }

    /**
     * Persist current playback position and optional thumbnail to history.
     */
    fun saveProgress(thumbnailBitmap: Bitmap? = null) {
        val player = playerProvider() ?: return
        val url = currentUrl
        Log.d(TAG, "Attempting to save progress for $url")
        if (url != null && player.duration > 0 && player.currentPosition > 0) {
            val position = player.currentPosition
            val duration = player.duration
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
                                currentExternalSubtitleUrl, currentVideoFilter, currentCustomFilterValues
                            )
                            Log.d(TAG, "Progress saved successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save progress", e)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Not saving: URL=$url, Duration=${player.duration}, Pos=${player.currentPosition}")
        }
    }

    /**
     * Capture a bitmap from the PlayerView's SurfaceView asynchronously.
     */
    suspend fun captureBitmapSuspend(): Bitmap? {
        val surfaceView = playerView.videoSurfaceView as? android.view.SurfaceView ?: return null
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
