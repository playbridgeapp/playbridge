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
    private val filterButton: ImageButton,
    private val onShowSettings: () -> Unit,
    private val onShowPlaylist: () -> Unit,
    private val onError: () -> Unit,
    private val onSeekForwardRequested: () -> Unit,
    private val onSeekBackwardRequested: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var isControlsVisible = false
    private var isSeekOnlyVisible = false
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

    /**
     * Dispatch a VLC MediaPlayer event into the controls layer.
     * VlcPlayerActivity owns the single setEventListener call and forwards here,
     * so it can also handle EndReached and other activity-level events in the
     * same listener without one overwriting the other.
     */
    fun handleEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                handler.post {
                    playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
                    // Do NOT hide the buffering spinner here — the Buffering event (buffering==100f)
                    // is the correct signal. Hiding it on Playing causes the spinner to vanish
                    // before VLC has finished re-buffering at the seeked position.
                    hideControls()
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
                // Time update is handled by the poll loop when controls are visible
            }
            MediaPlayer.Event.LengthChanged -> {
                handler.post { updateProgress() }
            }
            MediaPlayer.Event.EncounteredError -> {
                handler.post { onError() }
            }
        }
    }

    init {
        // Hide unused buttons that ExoPlayer uses but VLC doesn't (for now)
        playlistButton.visibility = View.GONE
        prevButton.visibility = View.GONE
        nextButton.visibility = View.GONE
        filterButton.visibility = View.GONE
        streamInfoText.visibility = View.GONE

        tracksButton.visibility = View.VISIBLE

        // Set up Play/Pause
        playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        // Set up Settings/Tracks button
        tracksButton.setOnClickListener {
            onShowSettings()
        }

        playlistButton.setOnClickListener {
            onShowPlaylist()
        }

        prevButton.setOnClickListener {
            onPrevious()
        }

        nextButton.setOnClickListener {
            onNext()
        }

        // Initially hide controls
        hideControls()
    }

    fun setPlaylistVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        playlistButton.visibility = vis
        prevButton.visibility = vis
        nextButton.visibility = vis
    }

    fun getTitle(): String? {
        return titleText.text.toString()
    }

    fun setPendingSeekTime(time: Long?) {
        activePendingSeekTime = time
        if (time != null) {
            updatePendingSeekProgress(time)
        }
        // When time is null (seek committed), do NOT call updateProgress() — VLC's seek is async
        // and player.time still reflects the pre-seek position at this point, which would snap
        // the seekbar back. The 1s poll loop will pick up the real position once VLC confirms it.
    }

    fun attachPlayer() {
        // Event listener is owned by VlcPlayerActivity via a single setEventListener call
        // that dispatches to handleEvent(). We just kick off the progress poll here.
        updateProgress()
    }

    fun detachPlayer() {
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
        isSeekOnlyVisible = false

        controlsRoot.visibility = View.VISIBLE
        controlsPanel.visibility = View.VISIBLE
        titleText.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE

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

    fun showSeekUI() {
        isControlsVisible = false
        isSeekOnlyVisible = true

        controlsRoot.visibility = View.VISIBLE
        controlsPanel.visibility = View.GONE
        titleText.visibility = View.GONE
        streamInfoText.visibility = View.GONE
        seekBar.visibility = View.VISIBLE

        // Reset hide timer
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, 3000) // Seek UI hides slightly faster

        // Start updating progress
        handler.removeCallbacks(updateProgressRunnable)
        handler.post(updateProgressRunnable)
    }

    fun hideControls() {
        isControlsVisible = false
        isSeekOnlyVisible = false

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

    private fun updatePendingSeekProgress(newTime: Long) {
        val player = playerProvider() ?: return
        val duration = player.length

        if (duration > 0) {
            val progressPercent = (newTime.toFloat() / duration.toFloat()) * 1000
            seekBar.progress = progressPercent.toInt()
        } else {
            seekBar.progress = 0
        }

        elapsedText.text = formatTime(newTime)
        remainingText.text = formatTime(duration - newTime)
    }

    fun onSeekForward() {
        onSeekForwardRequested()
    }

    fun onSeekBackward() {
        onSeekBackwardRequested()
    }

    fun isControlsVisible() = isControlsVisible
    fun isFullOverlayVisible() = isControlsVisible

    private fun updateProgress() {
        val player = playerProvider() ?: return
        val position = player.time
        val duration = player.length

        val target = activePendingSeekTime
        if (target != null) {
            // Keep showing the committed seek target until VLC confirms the seek by advancing
            // player.time to within 3 seconds of the target. Without this, the 1s poll would
            // snap the seekbar back to the pre-seek position while VLC's async seek is in flight.
            if (position > 0 && Math.abs(position - target) < 3000L) {
                activePendingSeekTime = null
            } else {
                updatePendingSeekProgress(target)
                return
            }
        }

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
