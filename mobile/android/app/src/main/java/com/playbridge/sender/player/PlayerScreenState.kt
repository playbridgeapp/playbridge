package com.playbridge.sender.player

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.media.AudioManager
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout

internal enum class DragMode { NONE, SEEK, VOLUME, BRIGHTNESS }

/** A frame saved to the gallery, shown briefly with a way to open it. */
internal data class CapturedFrame(val preview: Bitmap, val uri: Uri)

/** A full-width horizontal swipe scrubs this many milliseconds. */
internal const val SEEK_RANGE_MS = 120_000L

/**
 * Everything the phone player screen shows or changes besides the [Player] itself: control
 * visibility, sheets, gestures, loops and the position the seek bar draws. The controls,
 * overlays and effects all read and write this one holder.
 */
@Stable
internal class PlayerScreenState(
    val player: Player,
    /** The title the screen opened with; a media item without one falls back to it. */
    val initialTitle: String?,
    /** Played from a website's queue: the sheet says Queue rather than Episodes. */
    val websiteQueue: Boolean,
    val context: Context,
    val window: Window?,
) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    // Playback
    var isPlaying by mutableStateOf(player.isPlaying)
    /** True while playback should run, including while it buffers; drives the seek bar's motion. */
    var playWhenReady by mutableStateOf(player.playWhenReady)
    var isBuffering by mutableStateOf(true)
    // Once a frame is on screen, hide the spinner even if ExoPlayer briefly
    // stays in STATE_BUFFERING (seen on Samsung with local content:// MP4s).
    var hasRenderedFrame by mutableStateOf(false)
    /** Width over height of the picture as displayed; 0 until the video reports its size. */
    var videoAspect by mutableFloatStateOf(0f)
    /** The shape to lay out for: 16:9 until the video reports its size, so opening doesn't jump. */
    val layoutAspect: Float get() = if (videoAspect > 0f) videoAspect else 16f / 9f
    /** A landscape video is shown in the portrait layout when the phone is upright. */
    val isLandscapeVideo: Boolean get() = layoutAspect > 1.1f
    var errorMessage by mutableStateOf<String?>(null)

    // Controls / scrubbing
    var controlsVisible by mutableStateOf(true)
    var interactionTick by mutableIntStateOf(0)
    var isScrubbing by mutableStateOf(false)
    var scrubFraction by mutableFloatStateOf(0f)
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    /**
     * Pinched open in the portrait layout: a landscape video fills the upright screen, cropped at
     * the sides. For portrait clips stored with bars in a landscape frame.
     */
    var portraitFill by mutableStateOf(false)
    /** Whether [portraitFill] applies right now (upright screen, landscape video, not PiP); set by PlayerScreen. */
    var fillingPortrait by mutableStateOf(false)
    /** The resize mode on screen: cropped to fill while [fillingPortrait], else the chosen one. */
    val shownResizeMode: Int get() = if (fillingPortrait) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else resizeMode
    var speed by mutableFloatStateOf(1f)
    var locked by mutableStateOf(false)
    var showUnlock by mutableStateOf(false)
    var showTracks by mutableStateOf(false)
    var showMore by mutableStateOf(false)
    /** The seek bar's end label shows time remaining instead of the duration. */
    var showRemaining by mutableStateOf(false)
    var capturedFrame by mutableStateOf<CapturedFrame?>(null)
    var currentTracks by mutableStateOf(player.currentTracks)
    var currentTitle by mutableStateOf(initialTitle)
    /** A short confirmation shown mid-screen (speed, repeat, aspect…); clears itself. */
    var statusMessage by mutableStateOf<String?>(null)
    var showStats by mutableStateOf(false)
    var showEpisodes by mutableStateOf(false)

    val activeVideoFormat: Format? by derivedStateOf { selectedFormat(C.TRACK_TYPE_VIDEO) }
    val activeAudioFormat: Format? by derivedStateOf { selectedFormat(C.TRACK_TYPE_AUDIO) }

    // Repeat, A-B loop and pan & zoom
    var repeatMode by mutableIntStateOf(player.repeatMode)
    var abStartMs by mutableStateOf<Long?>(null)
    var abEndMs by mutableStateOf<Long?>(null)
    var isPanZoomEnabled by mutableStateOf(false)
    var scale by mutableFloatStateOf(1f)
    var panOffset by mutableStateOf(Offset.Zero)

    // Website queues start with one item and grow as Streams supplies episodes.
    // Follow timeline changes rather than retaining the initial single-item list.
    var episodeTitles by mutableStateOf(readQueueTitles())
    var currentIndex by mutableIntStateOf(player.currentMediaItemIndex)
    /**
     * Queue controls (Queue, Previous/Next, Up next, "3 of 12") appear once there are at least two
     * items; a website queue that has supplied only one item looks like a single video until
     * its next item arrives.
     */
    val isPlaylist: Boolean get() = episodeTitles.size > 1

    // Gesture HUD state
    var dragMode by mutableStateOf(DragMode.NONE)
    var volume by mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
    var brightness by mutableFloatStateOf(initialBrightness(window, context))
    var seekPreviewMs by mutableLongStateOf(0L)
    var seekDeltaMs by mutableLongStateOf(0L)
    var doubleTapSide by mutableIntStateOf(0)    // -1 = rewind, +1 = forward, 0 = none
    var doubleTapSeconds by mutableIntStateOf(0) // cumulative skip amount shown in the indicator

    // Polled position/buffer/duration.
    // Perf: MutableLongState holders — Compose tracks .longValue reads per-composable,
    // so the 400ms tick recomposes only readers (the seek bar, HUDs, stats).
    val playbackProgress = mutableLongStateOf(0L)
    val playbackBuffered = mutableLongStateOf(0L)
    val playbackDuration = mutableLongStateOf(0L)

    val activity: Activity? get() = context as? Activity

    fun readQueueTitles(): List<String> = (0 until player.mediaItemCount).map {
        player.getMediaItemAt(it).mediaMetadata.title?.toString() ?: "Episode ${it + 1}"
    }

    fun markInteraction() { interactionTick++ }

    /** Navigate to an episode: lazy resolve-and-swap, or a native timeline seek. */
    fun goToEpisode(index: Int) {
        if (index < 0 || index >= episodeTitles.size) return
        errorMessage = null
        player.seekTo(index, 0L)
        // After an error the player is idle: it must be prepared again to load the new item.
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun seekBy(deltaMs: Long) {
        val d = player.duration.coerceAtLeast(0)
        val target = (player.currentPosition + deltaMs).coerceIn(0, if (d > 0) d else Long.MAX_VALUE)
        player.seekTo(target)
        playbackProgress.longValue = target
    }

    /** Seeks to a fraction of the duration and ends any scrub. */
    fun seekToFraction(fraction: Float) {
        val duration = playbackDuration.longValue
        if (duration > 0) {
            val target = (fraction.coerceIn(0f, 1f) * duration).toLong()
            player.seekTo(target)
            playbackProgress.longValue = target
        }
        isScrubbing = false
    }

    /** Sets A, then B, then clears the loop. */
    fun stepAbLoop() {
        val currentPos = player.currentPosition
        val start = abStartMs
        if (start == null) {
            abStartMs = currentPos
            statusMessage = "Loop start [A] set"
        } else if (abEndMs == null) {
            if (currentPos > start) {
                abEndMs = currentPos
                statusMessage = "Loop end [B] set: A-B active"
            } else {
                statusMessage = "End must be after start"
            }
        } else {
            clearAbLoop()
        }
    }

    fun clearAbLoop() {
        abStartMs = null
        abEndMs = null
        statusMessage = "A-B loop cleared"
    }

    fun applySpeed(value: Float) {
        speed = value
        player.setPlaybackSpeed(value)
        statusMessage = "Speed: ${speedLabel(value)}"
    }

    fun setRepeat(mode: Int) {
        player.repeatMode = mode
        repeatMode = mode
        statusMessage = when (mode) {
            Player.REPEAT_MODE_ONE -> "Repeat: One"
            Player.REPEAT_MODE_ALL -> "Repeat: All"
            else -> "Repeat: Off"
        }
    }

    fun setAspect(mode: Int) {
        resizeMode = mode
        statusMessage = when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoomed to fill"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretched"
            else -> "Fit to screen"
        }
    }

    fun switchPortraitFill(fill: Boolean) {
        if (portraitFill == fill) return
        portraitFill = fill
        statusMessage = if (fill) "Filling the screen · pinch in to fit" else "Fit to width"
    }

    fun togglePanZoom() {
        isPanZoomEnabled = !isPanZoomEnabled
        if (!isPanZoomEnabled) resetZoom()
        statusMessage = if (isPanZoomEnabled) "Pinch to zoom, drag to pan" else "Zoom and pan off"
    }

    /** Saves the frame on screen to the gallery and offers to open it. */
    fun captureFrame() {
        val playerView = activity?.findViewById<androidx.media3.ui.PlayerView>(com.playbridge.sender.R.id.player_view) ?: return
        captureFrame(playerView) { bitmap ->
            activity?.runOnUiThread {
                val uri = bitmap?.let { saveBitmapToGallery(context, it, currentTitle ?: "PlayBridge") }
                if (bitmap != null && uri != null) capturedFrame = CapturedFrame(bitmap, uri)
                else statusMessage = "Couldn't save the frame"
            }
        }
    }

    fun resetZoom() {
        scale = 1f
        panOffset = Offset.Zero
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        markInteraction()
    }

    private fun selectedFormat(type: Int): Format? =
        currentTracks.groups.find { it.type == type }?.let { group ->
            (0 until group.length).find { group.isTrackSelected(it) }?.let { index ->
                group.getTrackFormat(index)
            }
        }
}

@Composable
internal fun rememberPlayerScreenState(player: Player, title: String?, websiteQueue: Boolean): PlayerScreenState {
    val context = LocalContext.current
    return remember(player) {
        PlayerScreenState(player, title, websiteQueue, context, (context as? Activity)?.window)
    }
}
