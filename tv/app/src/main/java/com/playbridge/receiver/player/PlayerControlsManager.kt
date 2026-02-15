package com.playbridge.receiver.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.util.Log
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "PlayerControlsManager"
private const val FADE_DURATION = 250L

/**
 * Manages the modern player controls overlay: gradient background, centered play/pause,
 * seekbar with split timestamps, skip buttons, fade animations, and buffering spinner.
 *
 * Inspired by mpvKt/mpvext design patterns.
 */
class PlayerControlsManager(
    private val controlsRoot: View,
    private val controlsPanel: View,
    private val seekBar: SeekBar,
    private val playPauseButton: ImageButton,
    private val tracksButton: ImageButton,
    private val elapsedText: TextView,
    private val remainingText: TextView,
    private val titleText: TextView,
    private val centerPlayButton: ImageButton,
    private val skipBackButton: ImageButton,
    private val skipForwardButton: ImageButton,
    private val bufferingSpinner: ProgressBar,
    private val playerProvider: () -> ExoPlayer?,
    private val onShowTrackSelection: () -> Unit
) {
    // Scrubbing state
    var isScrubbing = false
        private set
    private var scrubPosition: Long = 0

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideUI() }
    private val commitSeekHandler = Handler(Looper.getMainLooper())
    private val commitSeekRunnable = Runnable { commitSeek() }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing) {
                updateProgress()
            }
            if (controlsRoot.visibility == View.VISIBLE) {
                hideControlsHandler.postDelayed(this, 1000)
            }
        }
    }

    fun setTitle(title: String?) {
        titleText.text = title ?: ""
    }

    /**
     * Wire up click listeners and SeekBar change/key listeners.
     */
    fun setupControls() {
        // Center play/pause button
        centerPlayButton.setOnClickListener {
            togglePlayPause()
            showControlsUI()
        }
        
        // Bottom play/pause button
        playPauseButton.setOnClickListener {
            togglePlayPause()
            showControlsUI()
        }

        // Skip buttons
        skipBackButton.setOnClickListener {
            playerProvider()?.let {
                val newPos = maxOf(0L, it.currentPosition - 10_000L)
                it.seekTo(newPos)
                updateProgress()
                showControlsUI()
            }
        }

        skipForwardButton.setOnClickListener {
            playerProvider()?.let {
                val dur = it.duration
                val newPos = if (dur > 0) minOf(dur, it.currentPosition + 10_000L) else it.currentPosition + 10_000L
                it.seekTo(newPos)
                updateProgress()
                showControlsUI()
            }
        }

        tracksButton.setOnClickListener {
            onShowTrackSelection()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = playerProvider()?.duration ?: 0
                    val newPosition = (duration * progress) / 1000
                    updateTimeLabels(newPosition, duration)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = playerProvider()?.duration ?: 0
                val newPosition = (duration * this@PlayerControlsManager.seekBar.progress) / 1000
                playerProvider()?.seekTo(newPosition)
                showSeekUI()
            }
        })

        // Handle D-pad events on SeekBar for TV-optimized scrubbing
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val multiplier = if (event.repeatCount > 10) 5 else 1
                        handleScrubbing(-10000L * multiplier)
                        showSeekUI()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val multiplier = if (event.repeatCount > 10) 5 else 1
                        handleScrubbing(10000L * multiplier)
                        showSeekUI()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    // For backward compat (used by old code paths)
    fun formatTime(currentMs: Long, durationMs: Long): String {
        return "${formatDuration(currentMs)} / ${formatDuration(durationMs)}"
    }

    private fun updateTimeLabels(positionMs: Long, durationMs: Long) {
        elapsedText.text = formatDuration(positionMs)
        val remaining = if (durationMs > 0) durationMs - positionMs else 0
        remainingText.text = "-${formatDuration(remaining)}"
    }

    fun updateProgress() {
        playerProvider()?.let { p ->
            val duration = p.duration
            val position = p.currentPosition
            if (duration > 0) {
                val progress = (1000 * position / duration).toInt()
                seekBar.progress = progress
                updateTimeLabels(position, duration)
            }
        }
    }

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
        centerPlayButton.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE
        titleText.visibility = View.VISIBLE

        updatePlayPauseIcon()
        if (!isScrubbing) updateProgress()
        startUpdateProgress()

        // Request focus on center play/pause for D-pad navigation
        val currentFocus = controlsRoot.rootView.findFocus()
        if (currentFocus == null || currentFocus.id == com.playbridge.receiver.R.id.player_view) {
            centerPlayButton.requestFocus()
        }

        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 4000)
    }

    fun showSeekUI() {
        if (controlsRoot.visibility != View.VISIBLE) {
            controlsRoot.alpha = 0f
            controlsRoot.visibility = View.VISIBLE
            controlsRoot.animate()
                .alpha(1f)
                .setDuration(FADE_DURATION)
                .start()
        }
        
        controlsPanel.visibility = View.GONE // Hide bottom buttons, show only seekbar
        centerPlayButton.visibility = View.GONE
        titleText.visibility = View.GONE
        seekBar.visibility = View.VISIBLE

        if (!isScrubbing) updateProgress()
        startUpdateProgress()

        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 3000)
    }

    fun hideUI() {
        controlsRoot.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION)
            .withEndAction { controlsRoot.visibility = View.GONE }
            .start()
        hideControlsHandler.removeCallbacks(updateProgressRunnable)
    }

    private fun startUpdateProgress() {
        hideControlsHandler.removeCallbacks(updateProgressRunnable)
        hideControlsHandler.post(updateProgressRunnable)
    }

    fun togglePlayPause() {
        if (isScrubbing) {
            commitSeek()
            return
        }
        playerProvider()?.let {
            if (it.isPlaying) it.pause() else it.play()
            updatePlayPauseIcon()
        }
    }

    fun updatePlayPauseIcon() {
        val isPlaying = playerProvider()?.isPlaying == true
        val iconRes = if (isPlaying) com.playbridge.receiver.R.drawable.ic_pause else com.playbridge.receiver.R.drawable.ic_play
        playPauseButton.setImageResource(iconRes)
        centerPlayButton.setImageResource(iconRes)
    }

    fun showBuffering() {
        bufferingSpinner.visibility = View.VISIBLE
        centerPlayButton.visibility = View.GONE
    }

    fun hideBuffering() {
        bufferingSpinner.visibility = View.GONE
        if (controlsRoot.visibility == View.VISIBLE) {
            centerPlayButton.visibility = View.VISIBLE
        }
    }

    fun handleScrubbing(deltaMs: Long) {
        if (!isScrubbing) {
            isScrubbing = true
            scrubPosition = playerProvider()?.currentPosition ?: 0
        }

        val duration = playerProvider()?.duration ?: 0
        if (duration > 0) {
            scrubPosition = (scrubPosition + deltaMs).coerceIn(0, duration)

            val progress = (1000 * scrubPosition / duration).toInt()
            seekBar.progress = progress
            updateTimeLabels(scrubPosition, duration)

            commitSeekHandler.removeCallbacks(commitSeekRunnable)
            commitSeekHandler.postDelayed(commitSeekRunnable, 1000)

            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsHandler.postDelayed(hideControlsRunnable, 4000)
        }
    }

    fun commitSeek() {
        if (isScrubbing) {
            Log.i(TAG, "Committing seek to: $scrubPosition")
            playerProvider()?.seekTo(scrubPosition)
            isScrubbing = false
            commitSeekHandler.removeCallbacks(commitSeekRunnable)
            updateProgress()
        }
    }
}
