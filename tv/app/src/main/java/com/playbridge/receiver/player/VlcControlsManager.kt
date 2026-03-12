package com.playbridge.receiver.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import org.videolan.libvlc.MediaPlayer

class VlcControlsManager(
    private val controlsRoot: View,
    private val controlsPanel: View,
    private val seekBar: SeekBar,
    private val playPauseButton: ImageButton,
    private val streamInfoText: TextView,
    private val elapsedText: TextView,
    private val remainingText: TextView,
    private val titleText: TextView,
    private val bufferingSpinner: ProgressBar,
    private val playerProvider: () -> MediaPlayer?,
    private val tracksButton: ImageButton,
    private val playlistButton: ImageButton,
    private val prevButton: ImageButton,
    private val nextButton: ImageButton,
    private val filterButton: ImageButton
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isControlsVisible = false
    private val autoHideRunnable = Runnable { hideControls() }
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (isControlsVisible) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val playerListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                handler.post {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
                    bufferingSpinner.visibility = View.GONE
                }
            }
            MediaPlayer.Event.Paused -> {
                handler.post {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                }
            }
            MediaPlayer.Event.Buffering -> {
                handler.post {
                    if (event.buffering < 100f) {
                        bufferingSpinner.visibility = View.VISIBLE
                    } else {
                        bufferingSpinner.visibility = View.GONE
                    }
                }
            }
            MediaPlayer.Event.TimeChanged -> {
                // Time update is handled by the poll loop when controls are visible,
                // but we could also update here if we want more frequent updates
            }
            MediaPlayer.Event.LengthChanged -> {
                handler.post { updateProgress() }
            }
        }
    }

    init {
        // Hide unused buttons that ExoPlayer uses but VLC doesn't (for now)
        tracksButton.visibility = View.GONE
        playlistButton.visibility = View.GONE
        prevButton.visibility = View.GONE
        nextButton.visibility = View.GONE
        filterButton.visibility = View.GONE
        streamInfoText.visibility = View.GONE

        // Set up Play/Pause
        playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        // Initially hide controls
        hideControls()
    }

    fun attachPlayer() {
        playerProvider()?.setEventListener(playerListener)
        updateProgress()
    }

    fun detachPlayer() {
        playerProvider()?.setEventListener(null)
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(autoHideRunnable)
    }

    fun setTitle(title: String?) {
        titleText.text = title ?: ""
    }

    fun toggleControls() {
        if (isControlsVisible) hideControls() else showControls()
    }

    fun showControls() {
        isControlsVisible = true
        controlsRoot.visibility = View.VISIBLE

        // Reset hide timer
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, 4000) // Hide after 4 seconds

        // Start updating progress
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)

        // Request focus on play/pause
        controlsPanel.requestFocus()
        playPauseButton.requestFocus()
    }

    fun hideControls() {
        isControlsVisible = false
        controlsRoot.visibility = View.GONE
        handler.removeCallbacks(updateProgressRunnable)
    }

    fun togglePlayPause() {
        val player = playerProvider() ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }

        // Keep controls open when interacting
        if (isControlsVisible) {
            handler.removeCallbacks(autoHideRunnable)
            handler.postDelayed(autoHideRunnable, 4000)
        }
    }

    fun onSeekForward() {
        val player = playerProvider() ?: return
        val length = player.length
        if (length > 0) {
            val newTime = (player.time + 10000).coerceAtMost(length)
            player.time = newTime
            updateProgress()
        }
        showControls()
    }

    fun onSeekBackward() {
        val player = playerProvider() ?: return
        val newTime = (player.time - 10000).coerceAtLeast(0)
        player.time = newTime
        updateProgress()
        showControls()
    }

    fun isControlsVisible() = isControlsVisible

    private fun updateProgress() {
        val player = playerProvider() ?: return
        val position = player.time
        val duration = player.length

        if (duration > 0) {
            val progressPercent = (position.toFloat() / duration.toFloat()) * 1000
            seekBar.progress = progressPercent.toInt()
        } else {
            seekBar.progress = 0
        }

        elapsedText.text = formatTime(position)
        remainingText.text = formatTime(duration - position)
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
