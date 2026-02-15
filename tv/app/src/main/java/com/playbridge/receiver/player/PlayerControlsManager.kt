package com.playbridge.receiver.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.util.Log
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "PlayerControlsManager"

/**
 * Manages the custom player controls overlay: seekbar, play/pause button,
 * scrubbing state, progress updates, and show/hide timing.
 */
class PlayerControlsManager(
    private val controlsRoot: View,
    private val controlsPanel: View,
    private val seekBar: SeekBar,
    private val playPauseButton: ImageButton,
    private val tracksButton: ImageButton,
    private val timeText: TextView,
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

    /**
     * Wire up click listeners and SeekBar change/key listeners.
     */
    fun setupControls() {
        playPauseButton.setOnClickListener {
            togglePlayPause()
            showControlsUI()
        }

        tracksButton.setOnClickListener {
            onShowTrackSelection()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = playerProvider()?.duration ?: 0
                    val newPosition = (duration * progress) / 1000
                    timeText.text = formatTime(newPosition, duration)
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

    fun formatTime(currentMs: Long, durationMs: Long): String {
        val currentSeconds = currentMs / 1000
        val durationSeconds = durationMs / 1000
        val currentStr = String.format("%02d:%02d", currentSeconds / 60, currentSeconds % 60)
        val durationStr = String.format("%02d:%02d", durationSeconds / 60, durationSeconds % 60)
        return "$currentStr / $durationStr"
    }

    fun updateProgress() {
        playerProvider()?.let { p ->
            val duration = p.duration
            val position = p.currentPosition
            if (duration > 0) {
                val progress = (1000 * position / duration).toInt()
                seekBar.progress = progress
                timeText.text = formatTime(position, duration)
            }
        }
    }

    fun showControlsUI() {
        controlsRoot.visibility = View.VISIBLE
        controlsPanel.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE

        updatePlayPauseIcon()
        if (!isScrubbing) updateProgress()
        startUpdateProgress()

        // Request focus on Play/Pause for D-pad navigation
        val currentFocus = controlsRoot.rootView.findFocus()
        if (currentFocus == null || currentFocus.id == com.playbridge.receiver.R.id.player_view) {
            playPauseButton.requestFocus()
        }

        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 3000)
    }

    fun showSeekUI() {
        controlsRoot.visibility = View.VISIBLE
        controlsPanel.visibility = View.GONE // Hide buttons, show only seekbar
        seekBar.visibility = View.VISIBLE

        if (!isScrubbing) updateProgress()
        startUpdateProgress()

        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 3000)
    }

    fun hideUI() {
        controlsRoot.visibility = View.GONE
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
        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        playPauseButton.setImageResource(iconRes)
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
            timeText.text = formatTime(scrubPosition, duration)

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
