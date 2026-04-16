package com.playbridge.player.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.util.Log
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "PlayerControlsManager"
private const val FADE_DURATION = 250L

/**
 * Manages the player controls overlay: gradient background, play/pause, tracks,
 * seekbar with split timestamps, fade animations, and buffering spinner.
 */
class PlayerControlsManager(
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
    private val streamInfoText: TextView,
    private val seasonInfoText: TextView,
    private val elapsedText: TextView,
    private val remainingText: TextView,
    private val titleText: TextView,
    private val bufferingSpinner: ProgressBar,
    private val playerProvider: () -> ExoPlayer?,
    private val onShowTrackSelection: () -> Unit,
    private val onShowPlaylist: () -> Unit,
    private val onShowStreams: () -> Unit,
    private val onShowFilter: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onToggleLoop: () -> Unit
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

    /** Whether the full controls overlay (with buttons) is currently visible. */
    val isFullOverlayVisible: Boolean
        get() = controlsRoot.visibility == View.VISIBLE && controlsPanel.visibility == View.VISIBLE

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

    /**
     * Helper to show UI on focus.
     */

    fun setupControls() {
        // Play/pause button
        playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        tracksButton.setOnClickListener {
            onShowTrackSelection()
        }

        playlistButton.setOnClickListener {
            onShowPlaylist()
        }

        streamsButton.setOnClickListener {
            onShowStreams()
        }

        filterButton.setOnClickListener {
            onShowFilter()
        }

        loopButton.setOnClickListener {
            onToggleLoop()
        }

        prevButton.setOnClickListener {
            onPrevious()
        }

        nextButton.setOnClickListener {
            onNext()
        }
    }

    /** Update the loop button tint to reflect the current loop state. */
    fun updateLoopIcon(isLooping: Boolean) {
        if (isLooping) {
            loopButton.setColorFilter(android.graphics.Color.parseColor("#4FC3F7"))
        } else {
            loopButton.clearColorFilter()
        }
    }

    /** Show or hide the playlist-related buttons based on whether a playlist is active. */
    fun setPlaylistVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        playlistButton.visibility = vis
        prevButton.visibility = vis
        nextButton.visibility = vis
    }

    /**
     * Show or hide the stream selection button.
     */
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
                // Feed buffer data into BufferSeekBar's custom fields (avoids system
                // rendering an unwanted secondaryProgress white bar over the track)
                (seekBar as? BufferSeekBar)?.let {
                    it.durationMs = duration
                    it.bufferedMs = p.bufferedPosition
                }
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
        seekBar.visibility = View.VISIBLE
        titleText.visibility = View.VISIBLE
        updateStreamInfo()

        updatePlayPauseIcon()
        if (!isScrubbing) updateProgress()
        startUpdateProgress()

        // Only set focus on initial overlay open (not when buttons are already navigated)
        val currentFocus = controlsRoot.rootView.findFocus()
        val hasButtonFocus = currentFocus?.id == com.playbridge.player.R.id.btn_play_pause ||
                             currentFocus?.id == com.playbridge.player.R.id.btn_tracks ||
                             currentFocus?.id == com.playbridge.player.R.id.btn_playlist ||
                             currentFocus?.id == com.playbridge.player.R.id.btn_prev ||
                             currentFocus?.id == com.playbridge.player.R.id.btn_next ||
                             currentFocus?.id == com.playbridge.player.R.id.btn_filter ||
                             currentFocus?.id == com.playbridge.player.R.id.btn_loop
        if (!hasButtonFocus) {
            playPauseButton.post { playPauseButton.requestFocus() }
        }

        hideControlsHandler.removeCallbacks(hideControlsRunnable)
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
        titleText.visibility = View.VISIBLE
        seasonInfoText.visibility = if (!seasonInfoText.text.isNullOrBlank()) View.VISIBLE else View.GONE
        streamInfoText.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE
        updateStreamInfo()
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
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
                // Hide overlay when playback resumes
                hideUI()
            }
            updatePlayPauseIcon()
        }
    }

    fun updatePlayPauseIcon() {
        val isPlaying = playerProvider()?.isPlaying == true
        val iconRes = if (isPlaying) com.playbridge.player.R.drawable.ic_pause else com.playbridge.player.R.drawable.ic_play
        playPauseButton.setImageResource(iconRes)
    }

    fun showBuffering() {
        bufferingSpinner.visibility = View.VISIBLE
    }

    fun hideBuffering() {
        bufferingSpinner.visibility = View.GONE
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
            updateStreamInfo()

            commitSeekHandler.removeCallbacks(commitSeekRunnable)
            commitSeekHandler.postDelayed(commitSeekRunnable, 400) // Lower commit delay for faster responsiveness

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

    /** Build and display stream info (resolution, codec, bitrate, audio). */
    private fun updateStreamInfo() {
        val player = playerProvider() ?: run {
            streamInfoText.visibility = View.GONE
            return
        }

        val parts = mutableListOf<String>()
        parts.add("ExoPlayer")

        // Get video and audio format from selected tracks (source format, not decoded)

        var videoFormat: Format? = null
        var audioFormat: Format? = null
        for (group in player.currentTracks.groups) {
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val fmt = group.getTrackFormat(i)
                    when (group.type) {
                        C.TRACK_TYPE_VIDEO -> if (videoFormat == null) videoFormat = fmt
                        C.TRACK_TYPE_AUDIO -> if (audioFormat == null) audioFormat = fmt
                    }
                }
            }
        }

        // Video info
        if (videoFormat != null) {
            // Resolution (from source track, not decoded output)
            if (videoFormat.height != Format.NO_VALUE) {
                parts.add("${videoFormat.height}p")
            }
            // Video codec
            videoFormat.codecs?.let { codec ->
                val shortCodec = when {
                    codec.startsWith("avc") -> "H.264"
                    codec.startsWith("hvc") || codec.startsWith("hev") -> "H.265"
                    codec.startsWith("vp9") || codec.startsWith("vp09") -> "VP9"
                    codec.startsWith("av01") -> "AV1"
                    else -> codec.uppercase()
                }
                parts.add(shortCodec)
            }
            // Video bitrate
            if (videoFormat.bitrate != Format.NO_VALUE && videoFormat.bitrate > 0) {
                val mbps = videoFormat.bitrate / 1_000_000f
                parts.add("%.1f Mbps".format(mbps))
            }
        }

        // Audio info
        if (audioFormat != null) {
            val audioParts = mutableListOf<String>()
            audioFormat.codecs?.let { codec ->
                val shortCodec = when {
                    codec.startsWith("mp4a") -> "AAC"
                    codec.startsWith("ac-3") || codec == "ac3" -> "AC3"
                    codec.startsWith("ec-3") || codec == "eac3" -> "EAC3"
                    codec.startsWith("dtsc") || codec.startsWith("dtsh") || codec.startsWith("dtse") -> "DTS"
                    codec.startsWith("opus") -> "Opus"
                    codec.startsWith("flac") -> "FLAC"
                    else -> codec.uppercase()
                }
                audioParts.add(shortCodec)
            }
            if (audioFormat.channelCount != Format.NO_VALUE) {
                val chLabel = when (audioFormat.channelCount) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "${audioFormat.channelCount}ch"
                }
                audioParts.add(chLabel)
            }
            audioFormat.language?.let { lang ->
                if (lang.isNotBlank() && lang != "und") {
                    audioParts.add(lang.uppercase())
                }
            }
            if (audioParts.isNotEmpty()) {
                parts.add("\uD83D\uDD0A " + audioParts.joinToString(" "))
            }
        }

        if (parts.isNotEmpty()) {
            streamInfoText.text = "ExoPlayer | " + parts.filter { it != "ExoPlayer" }.joinToString("  •  ")
            streamInfoText.visibility = View.VISIBLE
        } else {
            streamInfoText.visibility = View.GONE
        }
    }
}
