package com.playbridge.player.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.playbridge.player.R

private const val TAG = "UnifiedControlsManager"
private const val FADE_DURATION = 250L

/**
 * Unified manager for video player controls on Android TV.
 * Interacts with any engine via [PlayerEngineAdapter].
 */
class UnifiedControlsManager(
    private val controlsRoot: View,
    private val controlsPanel: View,
    private val seekBar: SeekBar,
    private val playPauseButton: ImageButton,
    private val tracksButton: ImageButton,
    private val playlistButton: ImageButton,
    private val streamsButton: ImageButton,
    private val prevButton: ImageButton,
    private val nextButton: ImageButton,
    private val filterButton: ImageButton,
    private val loopButton: ImageButton,
    private val switchPlayerButton: ImageButton,
    private val streamInfoText: TextView,
    private val seasonInfoText: TextView,
    private val elapsedText: TextView,
    private val remainingText: TextView,
    private val titleText: TextView,
    private val hdrBadge: TextView,
    private val metaContainer: View,
    private val bufferingSpinner: ProgressBar,
    private val engine: PlayerEngineAdapter,
    private val engineType: String, // "ExoPlayer", "VLC", "MPV"
    private val onShowTrackSelection: () -> Unit,
    private val onShowPlaylist: () -> Unit,
    private val onShowStreams: () -> Unit,
    private val onSwitchPlayer: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onShowFilter: (() -> Unit)? = null,
    private val onToggleLoop: (() -> Unit)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideUI() }
    private val commitSeekHandler = Handler(Looper.getMainLooper())
    private val commitSeekRunnable = Runnable { commitSeek() }

    var isScrubbing = false
        private set
    private var scrubPosition: Long = 0

    // For VLC/MPV style async seeks to prevent snapping
    private var activePendingSeekTime: Long? = null

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing) {
                updateProgress()
            }
            if (controlsRoot.visibility == View.VISIBLE) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    init {
        setupListeners()
    }
    private fun setupListeners() {
        playPauseButton.setOnClickListener { togglePlayPause() }
        tracksButton.setOnClickListener { onShowTrackSelection() }
        playlistButton.setOnClickListener { onShowPlaylist() }
        streamsButton.setOnClickListener { onShowStreams() }
        filterButton.setOnClickListener { onShowFilter?.invoke() }
        filterButton.visibility = if (onShowFilter != null) View.VISIBLE else View.GONE

        loopButton.setOnClickListener { onToggleLoop?.invoke() }
        loopButton.visibility = if (onToggleLoop != null) View.VISIBLE else View.GONE

        switchPlayerButton.setOnClickListener { onSwitchPlayer() }
        prevButton.setOnClickListener { onPrevious() }
        nextButton.setOnClickListener { onNext() }
    }

    /** Set the video title. */
    fun setTitle(title: String?) {
        titleText.text = title ?: ""
    }

    fun getTitle(): String = titleText.text.toString()

    /** Set season/episode info (e.g. "S02 E05"). */
    fun setSeasonInfo(info: String?) {
        if (info.isNullOrBlank()) {
            seasonInfoText.visibility = View.GONE
        } else {
            seasonInfoText.text = info
            seasonInfoText.visibility = View.VISIBLE
        }
    }

    /** Update the loop button icon tint. */
    fun updateLoopIcon(isLooping: Boolean) {
        if (isLooping) {
            loopButton.setColorFilter(android.graphics.Color.parseColor("#4FC3F7"))
        } else {
            loopButton.clearColorFilter()
        }
    }

    /** Show/hide playlist and navigation buttons. */
    fun setPlaylistVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        playlistButton.visibility = vis
        prevButton.visibility = vis
        nextButton.visibility = vis
    }

    /** Show/hide stream selection button. */
    fun setStreamsVisible(visible: Boolean) {
        streamsButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Show/hide navigation buttons for series (without playlist queue). */
    fun setNavigationVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        prevButton.visibility = vis
        nextButton.visibility = vis
    }

    /**
     * Show the full controls overlay, including buttons.
     */
    fun showControlsUI() {
        if (controlsRoot.visibility != View.VISIBLE) {
            controlsRoot.alpha = 0f
            controlsRoot.visibility = View.VISIBLE
            controlsRoot.animate()
                .alpha(1f)
                .setDuration(FADE_DURATION)
                .start()
        }

        controlsPanel.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE
        titleText.visibility = View.VISIBLE
        updateStreamInfoUI()
        updatePlayPauseIcon()
        if (!isScrubbing) updateProgress()

        startUpdateProgressLoop()

        // Focus management
        val currentFocus = controlsRoot.rootView.findFocus()
        val hasButtonFocus = currentFocus?.id == R.id.btn_play_pause ||
                currentFocus?.id == R.id.btn_tracks ||
                currentFocus?.id == R.id.btn_playlist ||
                currentFocus?.id == R.id.btn_prev ||
                currentFocus?.id == R.id.btn_next ||
                currentFocus?.id == R.id.btn_filter ||
                currentFocus?.id == R.id.btn_loop ||
                currentFocus?.id == R.id.btn_switch_player

        if (!hasButtonFocus) {
            playPauseButton.post { playPauseButton.requestFocus() }
        }

        handler.removeCallbacks(hideControlsRunnable)
    }

    /**
     * Show only the seek UI (seekbar, times, titles) without the button panel.
     */
    fun showSeekUI() {
        if (controlsRoot.visibility != View.VISIBLE) {
            controlsRoot.alpha = 0f
            controlsRoot.visibility = View.VISIBLE
            controlsRoot.animate()
                .alpha(1f)
                .setDuration(FADE_DURATION)
                .start()
        }

        controlsPanel.visibility = View.GONE
        titleText.visibility = View.VISIBLE
        seasonInfoText.visibility = if (!seasonInfoText.text.isNullOrBlank()) View.VISIBLE else View.GONE
        streamInfoText.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE
        updateStreamInfoUI()
        if (!isScrubbing) updateProgress()

        startUpdateProgressLoop()

        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    /** Hide the entire UI overlay. */
    fun hideUI() {
        controlsRoot.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION)
            .withEndAction { controlsRoot.visibility = View.GONE }
            .start()
        handler.removeCallbacks(updateProgressRunnable)
    }

    fun isControlsVisible(): Boolean = controlsRoot.visibility == View.VISIBLE
    fun isFullOverlayVisible(): Boolean = controlsRoot.visibility == View.VISIBLE && controlsPanel.visibility == View.VISIBLE

    private fun startUpdateProgressLoop() {
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
    }

    fun togglePlayPause() {
        if (isScrubbing) {
            commitSeek()
            return
        }
        if (engine.isPlaying) {
            engine.pause()
        } else {
            engine.play()
            hideUI()
        }
        updatePlayPauseIcon()
    }

    fun updatePlayPauseIcon() {
        val isPlaying = engine.isPlaying
        // Use android resources or app resources
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        playPauseButton.setImageResource(iconRes)
    }

    fun showBuffering() {
        bufferingSpinner.visibility = View.VISIBLE
    }

    fun hideBuffering() {
        bufferingSpinner.visibility = View.GONE
    }

    /**
     * Handle manual scrubbing (usually via D-pad left/right).
     */
    fun handleScrubbing(deltaMs: Long) {
        if (!isScrubbing) {
            isScrubbing = true
            scrubPosition = engine.currentPosition
        }

        val duration = engine.duration
        if (duration > 0) {
            scrubPosition = (scrubPosition + deltaMs).coerceIn(0, duration)
            updateUIForPosition(scrubPosition, duration)

            commitSeekHandler.removeCallbacks(commitSeekRunnable)
            commitSeekHandler.postDelayed(commitSeekRunnable, 400)

            handler.removeCallbacks(hideControlsRunnable)
            handler.postDelayed(hideControlsRunnable, 4000)
        }
    }

    /** Force a seek commit. */
    fun commitSeek() {
        if (isScrubbing) {
            Log.i(TAG, "Committing seek to: $scrubPosition")
            engine.seekTo(scrubPosition)
            activePendingSeekTime = scrubPosition
            isScrubbing = false
            commitSeekHandler.removeCallbacks(commitSeekRunnable)
            updateProgress()
        }
    }

    fun setPendingSeekTime(timeMs: Long) {
        activePendingSeekTime = timeMs
        updateUIForPosition(timeMs, engine.duration)
    }

    private fun updateProgress() {
        val position = engine.currentPosition
        val duration = engine.duration

        // Handle pending seek snapping protection
        val target = activePendingSeekTime
        if (target != null) {
            if (position > 0 && Math.abs(position - target) < 3000L) {
                activePendingSeekTime = null
            } else {
                updateUIForPosition(target, duration)
                return
            }
        }

        updateUIForPosition(position, duration)
    }

    private fun updateUIForPosition(positionMs: Long, durationMs: Long) {
        if (durationMs > 0) {
            seekBar.progress = (1000 * positionMs / durationMs).toInt()
            (seekBar as? BufferSeekBar)?.let {
                it.durationMs = durationMs
                it.bufferedMs = engine.bufferedPosition
            }
        } else {
            seekBar.progress = 0
        }

        elapsedText.text = formatDuration(positionMs)
        val remaining = if (durationMs > 0) durationMs - positionMs else 0
        remainingText.text = "-${formatDuration(remaining)}"
    }

    private fun updateStreamInfoUI() {
        val info = engine.streamInfo
        if (info.isNullOrBlank()) {
            streamInfoText.text = engineType
        } else {
            streamInfoText.text = "$engineType | $info"
        }
        
        val hdr = engine.hdrFormat
        if (hdr != null) {
            hdrBadge.text = hdr
            hdrBadge.visibility = View.VISIBLE
        } else {
            hdrBadge.visibility = View.GONE
        }
        
        metaContainer.visibility = View.VISIBLE
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = Math.max(0, ms / 1000)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun detach() {
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        commitSeekHandler.removeCallbacks(commitSeekRunnable)
    }
}
