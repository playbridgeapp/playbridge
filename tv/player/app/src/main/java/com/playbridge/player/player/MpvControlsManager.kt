package com.playbridge.player.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView

/**
 * Controls overlay manager for MPV playback.
 *
 * Uses lambda callbacks instead of a direct player reference so it stays decoupled
 * from the MPVLib singleton.  The activity provides position/duration accessors and
 * play-pause / seek actions; the manager handles all UI state (show/hide, seekbar,
 * time display, auto-hide timer).
 */
class MpvControlsManager(
    private val controlsRoot: View,
    private val controlsPanel: View,
    private val seekBar: SeekBar,
    private val playPauseButton: ImageButton,
    private val streamInfoText: TextView,
    private val seasonInfoText: TextView,
    private val elapsedText: TextView,
    private val remainingText: TextView,
    private val titleText: TextView,
    private val bufferingSpinner: ProgressBar,
    private val tracksButton: ImageButton,
    private val playlistButton: ImageButton,
    private val streamsButton: ImageButton,
    private val prevButton: ImageButton,
    private val nextButton: ImageButton,
    private val filterButton: ImageButton,
    private val getPosition: () -> Long,       // current position in ms
    private val getDuration: () -> Long,       // total duration in ms
    private val getBufferedPosition: () -> Long, // furthest buffered position in ms
    private val isPlayerPlaying: () -> Boolean,
    private val onTogglePlayPause: () -> Unit,
    private val onShowSettings: () -> Unit,
    private val onShowPlaylist: () -> Unit,
    private val onShowStreams: () -> Unit,
    private val onSeekForwardRequested: () -> Unit,
    private val onSeekBackwardRequested: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isControlsVisible = false
    private var isSeekOnlyVisible = false

    // When a seek is in flight, hold the target here so the seekbar doesn't snap back
    // to the current (pre-seek) position during the async seek operation.
    private var activePendingSeekTime: Long? = null

    private val autoHideRunnable = Runnable { hideControls() }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (isControlsVisible || isSeekOnlyVisible) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    init {
        playlistButton.visibility = View.GONE
        streamsButton.visibility = View.GONE
        prevButton.visibility = View.GONE
        nextButton.visibility = View.GONE
        filterButton.visibility = View.GONE
        streamInfoText.visibility = View.GONE

        playPauseButton.setOnClickListener { togglePlayPause() }
        tracksButton.setOnClickListener { onShowSettings() }
        playlistButton.setOnClickListener { onShowPlaylist() }
        streamsButton.setOnClickListener { onShowStreams() }
        prevButton.setOnClickListener { onPrevious() }
        nextButton.setOnClickListener { onNext() }

        hideControls()
    }

    // ── Playback state notifications from the activity ───────────────────────

    fun onPlayingChanged(isPlaying: Boolean) {
        handler.post {
            playPauseButton.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            if (isPlaying) hideControls()
        }
    }

    fun onBufferingChanged(isBuffering: Boolean) {
        handler.post {
            bufferingSpinner.visibility = if (isBuffering) View.VISIBLE else View.GONE
        }
    }

    fun showBuffering() {
        onBufferingChanged(true)
    }

    fun hideBuffering() {
        onBufferingChanged(false)
    }

    // ── Controls visibility ───────────────────────────────────────────────────

    fun showControls() {
        isControlsVisible = true
        isSeekOnlyVisible = false

        controlsRoot.visibility = View.VISIBLE
        controlsPanel.visibility = View.VISIBLE
        titleText.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE

        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, 4000)

        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)

        controlsPanel.requestFocus()
        playPauseButton.requestFocus()
    }

    fun showSeekUI() {
        isControlsVisible = false
        isSeekOnlyVisible = true

        controlsRoot.visibility = View.VISIBLE
        controlsPanel.visibility = View.GONE
        titleText.visibility = View.VISIBLE
        seasonInfoText.visibility = if (!seasonInfoText.text.isNullOrBlank()) View.VISIBLE else View.GONE
        streamInfoText.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE

        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, 3000)

        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
    }

    fun hideControls() {
        isControlsVisible = false
        isSeekOnlyVisible = false

        controlsRoot.visibility = View.GONE
        handler.removeCallbacks(updateProgressRunnable)
    }

    fun toggleControls() {
        if (isControlsVisible) hideControls() else showControls()
    }

    fun isControlsVisible() = isControlsVisible
    fun isFullOverlayVisible() = isControlsVisible

    // ── Playback actions ──────────────────────────────────────────────────────

    fun togglePlayPause() {
        onTogglePlayPause()
        if (isControlsVisible) {
            handler.removeCallbacks(autoHideRunnable)
            handler.postDelayed(autoHideRunnable, 4000)
        }
    }

    fun onSeekForward() = onSeekForwardRequested()
    fun onSeekBackward() = onSeekBackwardRequested()

    // ── Pending seek tracking (avoids seekbar snapping during async MPV seek) ─

    fun setPendingSeekTime(time: Long?) {
        activePendingSeekTime = time
        if (time != null) updatePendingSeekProgress(time)
    }

    private fun updatePendingSeekProgress(newTime: Long) {
        val duration = getDuration()
        if (duration > 0) {
            seekBar.progress = ((newTime.toFloat() / duration.toFloat()) * 1000).toInt()
        }
        elapsedText.text = formatTime(newTime)
        remainingText.text = formatTime(duration - newTime)
    }

    // ── Metadata helpers ──────────────────────────────────────────────────────

    fun setTitle(title: String?) {
        titleText.text = title ?: ""
    }

    fun setSeasonInfo(info: String?) {
        if (info.isNullOrBlank()) {
            seasonInfoText.visibility = View.GONE
        } else {
            seasonInfoText.text = info
            seasonInfoText.visibility = View.VISIBLE
        }
    }

    fun setStreamInfo(info: String) {
        if (info.isBlank()) {
            streamInfoText.visibility = View.GONE
        } else {
            streamInfoText.text = "MPV | $info"
            streamInfoText.visibility = View.VISIBLE
        }
    }

    fun getTitle(): String? = titleText.text?.toString()

    fun setPlaylistVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        playlistButton.visibility = vis
        prevButton.visibility = vis
        nextButton.visibility = vis
    }

    fun setStreamsVisible(visible: Boolean) {
        streamsButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Show or hide prev/next navigation buttons for series navigation.
     * Unlike [setPlaylistVisible], this does NOT touch the playlist button —
     * series navigation doesn't use a playlist queue.
     */
    fun setNavigationVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        prevButton.visibility = vis
        nextButton.visibility = vis
    }

    fun detachPlayer() {
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(autoHideRunnable)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun updateProgress() {
        val position = getPosition()
        val duration = getDuration()

        val target = activePendingSeekTime
        if (target != null) {
            // Keep showing the pending seek target until MPV confirms by advancing position
            if (position > 0 && Math.abs(position - target) < 3000L) {
                activePendingSeekTime = null
            } else {
                updatePendingSeekProgress(target)
                return
            }
        }

        if (duration > 0) {
            seekBar.progress = ((position.toFloat() / duration.toFloat()) * 1000).toInt()
            // Feed buffer data into BufferSeekBar's custom fields (avoids system
            // rendering an unwanted secondaryProgress white bar over the track)
            (seekBar as? BufferSeekBar)?.let {
                it.durationMs = duration
                it.bufferedMs = getBufferedPosition()
            }
        } else {
            seekBar.progress = 0
            (seekBar as? BufferSeekBar)?.let {
                it.durationMs = 0
                it.bufferedMs = 0
            }
        }

        elapsedText.text = formatTime(position)
        remainingText.text = formatTime(duration - position)

        playPauseButton.setImageResource(
            if (isPlayerPlaying()) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return "00:00"
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
