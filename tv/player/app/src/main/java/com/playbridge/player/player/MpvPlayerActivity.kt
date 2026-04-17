package com.playbridge.player.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.playbridge.player.R
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import com.playbridge.player.logging.FileLogger
import androidx.annotation.OptIn
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
// ComposeOptIn alias removed — androidx.compose.runtime.OptIn does not exist

/**
 * MPV-based video player activity.
 *
 * Wraps the mpv-android library (MPVLib) behind the same PlayerActivity interface used by
 * ExoPlayerActivity and VlcPlayerActivity.  The activity owns the MPVLib lifecycle: create →
 * init → [surface attached] → loadfile → … → destroy.
 *
 * Prerequisites
 * -------------
 * Download the prebuilt AAR from the mpv-android project (see tv/app/libs/README.md) and place
 * it at tv/app/libs/mpv-android.aar before building.
 */
class MpvPlayerActivity : PlayerActivity(), MPVLib.EventObserver {

    companion object {
        private const val TAG = "MpvPlayerActivity"
    }


    private lateinit var surfaceView: SurfaceView
    private lateinit var controlsManager: MpvControlsManager
    private lateinit var progressManager: ProgressManager
    private lateinit var historyStore: HistoryStore
    private var mpvInitialized = false
    private var receiverRegistered = false

    // Current media state (updated via MPV property observers)
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L
    private var isPlayingState: Boolean = false
    // Buffered-ahead time in ms (from demuxer-cache-time, observed as seconds Double)
    private var bufferAheadMs: Long = 0L

    // Settings state
    private var subtitleUrls: List<String> = emptyList()
    private var currentSubtitleUrl: String? = null
    private var currentHeaders: Map<String, String>? = null
    private var currentUrl: String? = null
    private var currentPlaybackSpeed: Float = 1.0f

    // Stream info (updated via property observers)
    private var videoCodecRaw: String = ""
    private var videoHeight: Long = 0L
    private var videoBitrateBps: Long = 0L
    private var audioCodecRaw: String = ""
    private var audioChannels: String = ""

    // Position to seek to once MPV_EVENT_FILE_LOADED fires
    private var pendingResumePositionMs: Long = 0L

    private var isLoadingNewStream = false

    // Seek-stuck detection: if MPV_EVENT_PLAYBACK_RESTART doesn't follow a seek within this
    // window, ffmpeg is looping on a failed range request (e.g. "partial file" from the server).
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val seekTimeoutMs = 10_000L
    private var preSeePositionMs: Long = 0L
    private val seekTimeoutRunnable = Runnable {
        FileLogger.w(TAG, "Seek timeout — MPV stuck retrying failed range request. Recovering to ${preSeePositionMs}ms.")
        runOnUiThread {
            controlsManager.onBufferingChanged(false)
            Toast.makeText(this, "Seek failed (network error)", Toast.LENGTH_SHORT).show()
            // Seek back to the pre-seek position (known-good). Using positionMs here would
            // re-seek to the stuck target because MPV reports the target as time-pos mid-seek.
            MPVLib.command("seek", (preSeePositionMs / 1000.0).toString(), "absolute+keyframes")
        }
    }

    // Playlist state
    private var playlistItems: MutableList<com.playbridge.protocol.PlayPayload> = mutableListOf()
    private var playlistIndex: Int = 0

    private var activeDialog: android.app.Dialog? = null

    // ── PlayerActivity abstract impl ─────────────────────────────────────────

    override fun play() {
        if (!mpvInitialized) return
        MPVLib.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        if (!mpvInitialized) return
        MPVLib.setPropertyBoolean("pause", true)
    }

    override fun isPlaying(): Boolean = isPlayingState

    override fun getMediaDuration(): Long = durationMs

    override fun getCurrentPosition(): Long = positionMs

    override fun seekTo(position: Long) {
        if (!mpvInitialized) return
        if (durationMs > 0) {
            preSeePositionMs = positionMs
            MPVLib.command("seek", (position / 1000.0).toString(), "absolute+keyframes")
            scheduleSeekTimeout()
        } else {
            // Restore position on load
            pendingResumePositionMs = position
        }
    }

    override fun getVideoSurfaceView(): android.view.SurfaceView? = surfaceView

    override fun stopPlayback() {
        if (!mpvInitialized) return
        FileLogger.i(TAG, "stopPlayback() — clearing MPV state for transition")
        MPVLib.command("stop")
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.i(TAG, "=== MpvPlayerActivity CREATED ===")
        FileLogger.i(TAG, "Intent action: ${intent?.action}")

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::controlsManager.isInitialized && controlsManager.isControlsVisible()) {
                        controlsManager.hideControls()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        setContentView(R.layout.activity_mpv_player)
        surfaceView = findViewById(R.id.surface_view)

        historyStore = HistoryStore(this)
        progressManager = ProgressManager(
            context = this,
            historyStore = historyStore,
            lifecycleScope = lifecycleScope,
            playerActivity = this
        )

        // Initialise MPV
        try {
            MPVLib.create(this)
            mpvInitialized = true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to create MPV instance", e)
            Toast.makeText(this, "Failed to start MPV player", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Video output — gpu with Android GPU context
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")

        // Hardware decoding via MediaCodec — zero-copy SurfaceTexture path, no CPU round-trip.
        MPVLib.setOptionString("hwdec", "mediacodec")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,vp8,vp9,av1")

        // Use bilinear scaling — faster than the default lanczos/spline on large frames.
        MPVLib.setOptionString("scale",  "bilinear")
        MPVLib.setOptionString("dscale", "bilinear")
        MPVLib.setOptionString("cscale", "bilinear")

        // Audio
        MPVLib.setOptionString("ao", "audiotrack")
        MPVLib.setOptionString("audio-channels", "stereo")

        // Lock to display VSync; audio is resampled to match.
        MPVLib.setOptionString("video-sync", "display-resample")

        // Drop frames at both the decoder and VO to relieve backpressure on high-bitrate streams.
        MPVLib.setOptionString("framedrop", "decoder+vo")

        // Demuxer buffer — caps scale with available device RAM (see PlayerActivity.computeBufferConfig).
        val bufCfg = computeBufferConfig()
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", bufCfg.demuxerMaxBytes)
        MPVLib.setOptionString("demuxer-max-back-bytes", bufCfg.demuxerMaxBackBytes)

        // Network
        MPVLib.setOptionString("tls-verify", "no")
        // Disable the ytdl hook — yt-dlp is not installed, so the fallback just wastes ~1s
        // spawning subprocesses on every open failure.
        MPVLib.setOptionString("ytdl", "no")

        MPVLib.init()

        // Attach MPV rendering surface
        // NOTE: addObserver is called AFTER controlsManager is initialized below to avoid
        // a race where MPV's native event thread fires callbacks before controlsManager exists.
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                MPVLib.attachSurface(holder.surface)
                MPVLib.setOptionString("force-window", "immediate")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                MPVLib.setPropertyString("android-surface-size", "${w}x${h}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                MPVLib.setOptionString("force-window", "no")
                MPVLib.detachSurface()
            }
        })

        controlsManager = MpvControlsManager(
            controlsRoot          = findViewById(R.id.controls_root),
            controlsPanel         = findViewById(R.id.controls_panel),
            seekBar               = findViewById(R.id.player_seekbar),
            playPauseButton       = findViewById(R.id.btn_play_pause),
            streamInfoText        = findViewById(R.id.tv_stream_info),
            seasonInfoText        = findViewById(R.id.tv_season_info),
            elapsedText           = findViewById(R.id.tv_elapsed),
            remainingText         = findViewById(R.id.tv_remaining),
            titleText             = findViewById(R.id.title_text),
            bufferingSpinner      = findViewById(R.id.buffering_spinner),
            tracksButton          = findViewById(R.id.btn_tracks),
            playlistButton        = findViewById(R.id.btn_playlist),
            streamsButton         = findViewById(R.id.btn_streams),
            prevButton            = findViewById(R.id.btn_prev),
            nextButton            = findViewById(R.id.btn_next),
            filterButton          = findViewById(R.id.btn_filter),
            getPosition           = { positionMs },
            getDuration           = { durationMs },
            getBufferedPosition   = { (positionMs + bufferAheadMs).coerceAtMost(durationMs) },
            isPlayerPlaying       = { isPlayingState },
            onTogglePlayPause     = {
                MPVLib.setPropertyBoolean("pause", isPlayingState) // toggle
            },
            onShowSettings        = { showTrackSelectionDialog() },
            onShowPlaylist        = { showPlaylistPicker() },
            onShowStreams         = { showStreamSelectionDialog() },
            onSeekForwardRequested  = { handleSeek(1) },
            onSeekBackwardRequested = { handleSeek(-1) },
            onPrevious            = { playPreviousInPlaylist() },
            onNext                = { playNextInPlaylist() }
        )

        // Register MPV observer NOW — after controlsManager is initialized — so that
        // property-change callbacks can safely reference controlsManager without crashing.
        // Format IDs: MPV_FORMAT_STRING=1, MPV_FORMAT_FLAG=3, MPV_FORMAT_INT64=4, MPV_FORMAT_DOUBLE=5
        MPVLib.addObserver(this)
        MPVLib.observeProperty("pause",               3) // Boolean
        MPVLib.observeProperty("time-pos",            4) // Long (seconds)
        MPVLib.observeProperty("duration",            4) // Long (seconds)
        MPVLib.observeProperty("height",              4) // Long (px)
        MPVLib.observeProperty("video-bitrate",       4) // Long (bits/s)
        MPVLib.observeProperty("video-codec",         1) // String
        MPVLib.observeProperty("audio-codec",         1) // String
        MPVLib.observeProperty("audio-channels",      1) // String e.g. "stereo", "5.1"
        MPVLib.observeProperty("demuxer-cache-time",  5) // Double (seconds buffered ahead)

        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_QUEUE_ADD)
            addAction(ServerService.ACTION_PLAYLIST_JUMP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(remoteReceiver, filter)
        }
        receiverRegistered = true

        handleIntent(intent)

        // Drain any queue items that arrived before our receiver was registered.
        // Must happen AFTER handleIntent because handleIntent replaces playlistItems.
        ServerService.drainPendingQueueItems().forEach { payload ->
            playlistItems.add(payload)
            FileLogger.i(TAG, "Queue add (startup drain): ${payload.title ?: payload.url}")
        }
        if (playlistItems.isNotEmpty()) {
            controlsManager.setPlaylistVisible(true)
            broadcastPlaylistStatus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onPause() {
        super.onPause()
        if (::progressManager.isInitialized) progressManager.saveProgress()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) {
            finish()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(remoteReceiver)
            receiverRegistered = false
        }
        activeDialog?.dismiss()
        activeDialog = null
        if (mpvInitialized) {
            MPVLib.destroy()
            mpvInitialized = false
        }
        super.onDestroy()
    }

    // ── MPV Events ───────────────────────────────────────────────────────────

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            isPlayingState = !value
            // Must post to the UI thread: this callback fires on MPV's native event thread.
            runOnUiThread {
                if (::controlsManager.isInitialized) {
                    controlsManager.onPlayingChanged(isPlayingState)
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos" -> {
                positionMs = value * 1000
            }
            "duration" -> {
                durationMs = value * 1000
            }
            "height" -> {
                videoHeight = value
                updateStreamInfo()
            }
            "video-bitrate" -> {
                videoBitrateBps = value
                updateStreamInfo()
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "video-codec" -> {
                videoCodecRaw = value
                updateStreamInfo()
            }
            "audio-codec" -> {
                audioCodecRaw = value
                updateStreamInfo()
            }
            "audio-channels" -> {
                audioChannels = value
                updateStreamInfo()
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        if (property == "demuxer-cache-time") {
            bufferAheadMs = (value * 1000).toLong()
        }
    }

    // Required by MPVLib.EventObserver — no-op for untyped property changes
    override fun eventProperty(property: String) {}

    // Required by MPVLib.EventObserver — no-op for MPVNode-typed property changes
    override fun eventProperty(property: String, value: MPVNode) {}

    override fun event(eventId: Int, data: MPVNode) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                runOnUiThread { controlsManager.showBuffering() }
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                isLoadingNewStream = false
                runOnUiThread {
                    controlsManager.hideBuffering()
                    if (pendingResumePositionMs > 0) {
                        MPVLib.command("seek", (pendingResumePositionMs / 1000.0).toString(), "absolute+keyframes")
                        pendingResumePositionMs = 0
                    }
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                // Fired when playback resumes after buffering or seek
                seekHandler.removeCallbacks(seekTimeoutRunnable)
                runOnUiThread { controlsManager.hideBuffering() }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (isLoadingNewStream) return
                runOnUiThread {
                    if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                        playNextInPlaylist()
                    } else {
                        finish()
                    }
                }
            }
        }
    }

    // ── Internal Helpers ─────────────────────────────────────────────────────

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_REMOTE -> {
                    val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                    handleRemoteKey(key)
                }
                ServerService.ACTION_CONTROL -> {
                    val cmd = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    handleControlCommand(cmd)
                }
                ServerService.ACTION_QUEUE_ADD -> {
                    ServerService.drainPendingQueueItems().forEach { payload ->
                        playlistItems.add(payload)
                        FileLogger.i(TAG, "Queue add: ${payload.title ?: payload.url}")
                    }
                    controlsManager.setPlaylistVisible(true)
                    broadcastPlaylistStatus()
                }
                ServerService.ACTION_PLAYLIST_JUMP -> {
                    val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                    if (index >= 0) {
                        FileLogger.i(TAG, "Playlist jump to index: $index")
                        playItemAtIndex(index)
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        setupSeriesNavigator(intent)
        val url = intent?.getStringExtra(ServerService.EXTRA_URL) ?: return
        val headers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServerService.EXTRA_HEADERS, java.util.HashMap::class.java) as? Map<String, String>
        } else {
            @Suppress("UNCHECKED_CAST")
            intent.getSerializableExtra(ServerService.EXTRA_HEADERS) as? Map<String, String>
        }
        val title = intent.getStringExtra(ServerService.EXTRA_TITLE)
        val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)

        // Read playlist if present
        val isPlaylist = intent.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false)
        val inMemoryPlaylist = PlaylistStore.currentPlaylist
        if (isPlaylist && inMemoryPlaylist != null && inMemoryPlaylist.isNotEmpty()) {
            playlistItems = inMemoryPlaylist.toMutableList()
            playlistIndex = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0)
            FileLogger.i(TAG, "Playlist loaded: ${playlistItems.size} items, starting at index $playlistIndex")
        } else {
            playlistItems = mutableListOf()
        }

        // Apply title immediately if known
        title?.let { controlsManager.setTitle(it) }

        val hasPlaylist = playlistItems.isNotEmpty()
        val hasEpisodeList = seriesNavigator?.episodeList?.isNotEmpty() == true
        val isSeries = seriesNavigator?.contentType == "series"

        // Show playlist button when a playlist is active OR series navigator has list mode
        controlsManager.setPlaylistVisible(hasPlaylist || hasEpisodeList)

        // Show streams button when series navigator is active
        controlsManager.setStreamsVisible(seriesNavigator != null)

        seriesNavigator?.let { nav ->
            if (nav.contentType == "series") {
                val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                controlsManager.setSeasonInfo(seasonInfo)
            } else {
                controlsManager.setSeasonInfo(null)
            }
        }

        // Ensure prev/next buttons are visible for ANY series (including optimistic mode)
        // or if a playlist is active. Movies with no playlist will have them hidden by setPlaylistVisible(false).
        if (isSeries || hasPlaylist) {
            controlsManager.setNavigationVisible(true)
        }

        val displayTitle = if (seriesNavigator != null && seriesNavigator?.seriesTitle != null) {
            seriesNavigator?.seriesTitle
        } else {
            title
        }
        displayTitle?.let { controlsManager.setTitle(it) }

        this.subtitleUrls = subtitles ?: emptyList()
        this.currentHeaders = headers
        playVideo(url, headers)
    }

    private fun handleRemoteKey(key: String?) {
        when (key) {
            "up"    -> controlsManager.showControls()
            "down"  -> controlsManager.showControls()
            "left"  -> {
                if (controlsManager.isFullOverlayVisible()) {
                    // D-pad navigation handles focus
                } else {
                    handleSeek(-1)
                }
            }
            "right" -> {
                if (controlsManager.isFullOverlayVisible()) {
                    // D-pad navigation handles focus
                } else {
                    handleSeek(1)
                }
            }
            "center", "enter" -> {
                if (controlsManager.isFullOverlayVisible()) {
                    // D-pad center click handles button actions
                } else {
                    controlsManager.showControls()
                }
            }
            "back" -> {
                if (controlsManager.isControlsVisible()) {
                    controlsManager.hideControls()
                } else {
                    finish()
                }
            }
            "play_pause" -> {
                if (isPlayingState) pause() else play()
            }
        }
    }

    private fun handleControlCommand(command: String?) {
        when (command) {
            "play"  -> play()
            "pause" -> pause()
            "toggle_play_pause" -> if (isPlayingState) pause() else play()
            "seek_forward"  -> handleSeek(1)
            "seek_backward" -> handleSeek(-1)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (!::controlsManager.isInitialized) return super.onKeyDown(keyCode, event)

        val isFullOverlayVisible = controlsManager.isFullOverlayVisible()

        if (isFullOverlayVisible) {
            return when (keyCode) {
                // While full overlay (stream picker / episode list) is open, consume
                // directional keys silently so the Compose focus system handles them,
                // but pass through everything else.
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { controlsManager.togglePlayPause(); true }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY  -> { play(); true }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { pause(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (controlsManager.isControlsVisible()) {
                    // Controls already showing — center click is handled by Compose focus
                    super.onKeyDown(keyCode, event)
                } else {
                    controlsManager.showControls()
                    true
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (controlsManager.isControlsVisible()) {
                    super.onKeyDown(keyCode, event)
                } else {
                    val multiplier = if ((event?.repeatCount ?: 0) > 10) 5 else 1
                    handleSeek(-multiplier)
                    true
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (controlsManager.isControlsVisible()) {
                    super.onKeyDown(keyCode, event)
                } else {
                    val multiplier = if ((event?.repeatCount ?: 0) > 10) 5 else 1
                    handleSeek(multiplier)
                    true
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_RAISE,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_LOWER,
                    android.media.AudioManager.FLAG_SHOW_UI
                )
                true
            }
            android.view.KeyEvent.KEYCODE_BACK -> {
                if (controlsManager.isControlsVisible()) {
                    controlsManager.hideControls()
                } else {
                    finish()
                }
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { controlsManager.togglePlayPause(); true }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY  -> { play(); true }
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { pause(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleSeek(direction: Int) {
        val delta = 10_000L * direction
        val newPos = (positionMs + delta).coerceIn(0, durationMs)
        controlsManager.setPendingSeekTime(newPos)
        seekTo(newPos)
        controlsManager.showSeekUI()
    }

    private fun scheduleSeekTimeout() {
        seekHandler.removeCallbacks(seekTimeoutRunnable)
        seekHandler.postDelayed(seekTimeoutRunnable, seekTimeoutMs)
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private fun playVideo(url: String, headers: Map<String, String>?) {
        // Guard against MPV_EVENT_END_FILE from a prior stop() or a failed first HTTP attempt
        // triggering finish() before the new stream has loaded.
        isLoadingNewStream = true
        // Note: setupSeriesNavigator is already called by handleIntent before playVideo;
        // calling it again here would create a redundant SeriesNavigator instance.
        currentUrl = url
        if (seriesNavigator == null) {
            controlsManager.setSeasonInfo(null)
        }
        controlsManager.setStreamsVisible(seriesNavigator != null)
        FileLogger.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        FileLogger.i(TAG, "URL: $url")
        FileLogger.i(TAG, "Title: ${controlsManager.getTitle()}")
        FileLogger.i(TAG, "Headers: $headers")
        FileLogger.i(TAG, "===========================================")


        lifecycleScope.launch {
            // seekTo() will intercept this call while durationMs == 0 (file not yet loaded)
            // and stash the position in pendingResumePositionMs; it is applied on FILE_LOADED.
            val historyItem = progressManager.restoreProgress(url)
            if (historyItem != null) {
                // Apply playback speed
                if (historyItem.playbackSpeed != null) {
                    currentPlaybackSpeed = historyItem.playbackSpeed
                    MPVLib.setPropertyDouble("speed", currentPlaybackSpeed.toDouble())
                }
            }

            MPVLib.command("stop")

            // Headers
            headers?.forEach { (k, v) ->
                if (k.equals("user-agent", true)) {
                    MPVLib.setOptionString("user-agent", v)
                }
            }
            val headerString = headers?.entries?.joinToString(",") { "${it.key}: ${it.value}" } ?: ""
            MPVLib.setOptionString("http-header-fields", headerString)

            MPVLib.command("loadfile", url)
            play()
        }
    }

    private fun updateStreamInfo() {
        val info = buildString {
            if (videoHeight > 0) append("${videoHeight}p")
            if (videoCodecRaw.isNotEmpty()) {
                val short = when {
                    videoCodecRaw.contains("h264") -> "H.264"
                    videoCodecRaw.contains("hevc") -> "H.265"
                    videoCodecRaw.contains("vp9")  -> "VP9"
                    videoCodecRaw.contains("av1")  -> "AV1"
                    else -> videoCodecRaw.uppercase()
                }
                if (isNotEmpty()) append(" • ")
                append(short)
            }
            if (videoBitrateBps > 0) {
                val mbps = videoBitrateBps / 1_000_000.0
                if (isNotEmpty()) append(" • ")
                append("%.1f Mbps".format(mbps))
            }
            if (audioCodecRaw.isNotEmpty()) {
                val short = audioCodecRaw.uppercase()
                if (isNotEmpty()) append("  •  \uD83D\uDD0A $short")
                val ch = when (audioChannels.lowercase()) {
                    "stereo"              -> "2.0"
                    "5.1", "5.1(side)"   -> "5.1"
                    "7.1", "7.1(wide-side)" -> "7.1"
                    "mono"               -> "Mono"
                    else                 -> audioChannels
                }
                if (isNotEmpty()) append(" $ch")
            }
        }
        runOnUiThread { controlsManager.setStreamInfo(info) }
    }

    /**
     * Show the stream selection dialog for Stremio sources.
     */
    @OptIn(ExperimentalTvMaterial3Api::class)
    private fun showStreamSelectionDialog() {
        val nav = seriesNavigator ?: return
        val currentUrl = this.currentUrl

        val wasPlaying = isPlayingState
        if (wasPlaying) pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            val scope = rememberCoroutineScope()
            var streams by remember { mutableStateOf<List<com.playbridge.player.stremio.ScoredStremioStream>>(emptyList()) }
            var isLoading by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                streams = nav.resolveCurrentStreams()
                isLoading = false
            }

            PlayBridgeTVTheme {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Loading sources...", color = Color(0xFF00D9FF))
                    }
                } else {
                    StreamSelectionDialog(
                        streams = streams,
                        currentUrl = currentUrl,
                        preferredQuality = nav.qualityPreference,
                        preferredAddonName = nav.context.preferredAddonName,
                        onStreamSelected = { stream ->
                            dialog.dismiss()

                            // Update currentUrl so history saving works
                            this@MpvPlayerActivity.currentUrl = stream.url

                            playVideo(url = stream.url, headers = currentHeaders)
                            controlsManager.hideControls()

                        },
                        onRefresh = {
                            com.playbridge.player.stremio.StremioClient.clearCache(
                                contentId = nav.context.imdbId,
                                type = "series",
                                season = nav.currentSeason,
                                episode = nav.currentEpisode
                            )
                            isLoading = true
                            scope.launch {
                                streams = nav.resolveCurrentStreams()
                                isLoading = false
                            }
                        },
                        onDismiss = {
                            dialog.dismiss()
                        }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) play()
            controlsManager.showControls()
        }
        dialog.show()
    }

    private fun showPlaylistPicker() {
        val displayItems: List<com.playbridge.protocol.PlayPayload>
        val displayIndex: Int
        val isSeriesMode: Boolean

        if (playlistItems.isNotEmpty()) {
            displayItems = playlistItems
            displayIndex = playlistIndex
            isSeriesMode = false
        } else if (seriesNavigator?.episodeList?.isNotEmpty() == true) {
            val nav = seriesNavigator!!
            displayItems = nav.episodeList!!.map { ep ->
                val s = ep.season.toString().padStart(2, '0')
                val e = ep.episode.toString().padStart(2, '0')
                com.playbridge.protocol.PlayPayload(
                    url = "", // Not needed for UI
                    title = "S${s}E${e} - ${ep.title ?: "Episode ${ep.episode}"}"
                )
            }
            displayIndex = nav.currentIndex ?: 0
            isSeriesMode = true
        } else {
            return
        }

        val wasPlaying = isPlayingState
        if (wasPlaying) pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setContent {
            PlayBridgeTVTheme {
                PlaylistPickerDialog(
                    items = displayItems,
                    currentIndex = displayIndex,
                    onItemSelected = { index ->
                        dialog.dismiss()
                        if (isSeriesMode) {
                            playSeriesEpisodeAtIndex(index)
                        } else {
                            playItemAtIndex(index)
                        }
                    },
                    onDismiss = { dialog.dismiss() }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) play()
            controlsManager.showControls()
        }
        dialog.show()
    }

    private fun playSeriesEpisodeAtIndex(index: Int) {
        val nav = seriesNavigator ?: return
        lifecycleScope.launch {
            isLoadingNewStream = true
            FileLogger.i(TAG, "SeriesNavigator: resolving episode at index $index")

            // Save progress for the current episode before switching
            progressManager.saveProgress()

            stopPlayback()
            controlsManager.showBuffering()
            val stream = nav.resolveAndAdvanceToIndex(index)
            if (stream != null) {
                // Display season info on top left (e.g. "Season 1 (1x5)")
                val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                controlsManager.setSeasonInfo(seasonInfo)

                // Use the series title for the main title bar if available, else SxE
                val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                controlsManager.setTitle(mainTitle)

                // Update intent
                intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)

                playVideo(url = stream.url, headers = null)
                controlsManager.hideControls()
            } else {
                isLoadingNewStream = false
                controlsManager.hideBuffering()
                android.widget.Toast.makeText(this@MpvPlayerActivity, "Could not resolve episode", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Jump to a specific item in the playlist by index.
     */
    private fun playItemAtIndex(index: Int) {
        if (index < 0 || index >= playlistItems.size) return

        lifecycleScope.launch {
            // Save progress for the current episode before jumping, if it was playing
            if (::progressManager.isInitialized) progressManager.saveProgress()

            playlistIndex = index
            val item = playlistItems[index]
            val title = if (item.title != null) {
                "${item.title} (${index + 1}/${playlistItems.size})"
            } else {
                "Item ${index + 1}/${playlistItems.size}"
            }

            FileLogger.i(TAG, "Jumping to playlist item $index: $title")
            runOnUiThread {
                Toast.makeText(this@MpvPlayerActivity, title, Toast.LENGTH_SHORT).show()
                controlsManager.setTitle(title)
                controlsManager.hideControls()
            }

            stopPlayback()
            currentHeaders = item.headers
            playVideo(item.url, item.headers)
            broadcastPlaylistStatus()
        }
    }

    private fun playPreviousInPlaylist() {
        if (playlistItems.isEmpty()) {
            val nav = seriesNavigator
            if (nav != null && nav.hasPrev()) {
                lifecycleScope.launch {
                    isLoadingNewStream = true
                    FileLogger.i(TAG, "SeriesNavigator: resolving previous episode")
                    progressManager.saveProgress()
                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolvePrev()
                    if (stream != null) {
                        val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                        controlsManager.setSeasonInfo(seasonInfo)
                        val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                        controlsManager.setTitle(mainTitle)
                        intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                        intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)
                        playVideo(url = stream.url, headers = null)
                        controlsManager.hideControls()
                    } else {
                        isLoadingNewStream = false
                        controlsManager.hideBuffering()
                        Toast.makeText(this@MpvPlayerActivity, "Could not resolve previous episode", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Already on first episode", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (playlistIndex <= 0) {
            Toast.makeText(this, "Already on first episode", Toast.LENGTH_SHORT).show()
            return
        }
        playItemAtIndex(playlistIndex - 1)
    }

    private fun playNextInPlaylist() {
        if (playlistItems.isEmpty()) {
            val nav = seriesNavigator
            if (nav != null && nav.hasNext()) {
                lifecycleScope.launch {
                    isLoadingNewStream = true
                    FileLogger.i(TAG, "SeriesNavigator: resolving next episode")
                    progressManager.saveProgress()
                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolveNext()
                    if (stream != null) {
                        val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                        controlsManager.setSeasonInfo(seasonInfo)
                        val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                        controlsManager.setTitle(mainTitle)
                        intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                        intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)
                        playVideo(url = stream.url, headers = null)
                        controlsManager.hideControls()
                    } else {
                        isLoadingNewStream = false
                        controlsManager.hideBuffering()
                        Toast.makeText(this@MpvPlayerActivity, "No more episodes found", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } else {
                finish()
            }
            return
        }

        if (playlistIndex >= playlistItems.size - 1) {
            finish()
            return
        }
        playItemAtIndex(playlistIndex + 1)
    }

    private fun broadcastPlaylistStatus() {
        if (playlistItems.isEmpty()) return
        try {
            val itemsArray = org.json.JSONArray()
            playlistItems.forEachIndexed { index, item ->
                itemsArray.put(org.json.JSONObject().apply {
                    put("index", index)
                    put("title", item.title ?: "Item ${index + 1}")
                })
            }
            val statusJson = org.json.JSONObject().apply {
                put("type", "playlist_status")
                put("items", itemsArray)
                put("currentIndex", playlistIndex)
                put("totalCount", playlistItems.size)
            }.toString()
            ServerService.broadcastPlaylistStatus(statusJson)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to broadcast playlist status: ${e.message}")
        }
    }

    private fun showTrackSelectionDialog() {
        val wasPlaying = isPlayingState
        if (wasPlaying) pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setContent {
            PlayBridgeTVTheme {
                MpvTrackSelectionDialog(
                    audioTracks = buildTrackList().filter { it.type == "audio" },
                    subtitleTracks = buildTrackList().filter { it.type == "sub" },
                    externalSubtitleUrls = subtitleUrls,
                    currentExternalSubtitleUrl = currentSubtitleUrl,
                    currentPlaybackSpeed = currentPlaybackSpeed,
                    onDismiss = { dialog.dismiss() },
                    onAudioTrackSelected = { id ->
                        if (id != null) MPVLib.setPropertyInt("aid", id)
                        dialog.dismiss()
                    },
                    onSubtitleTrackSelected = { id ->
                        if (id != null) MPVLib.setPropertyInt("sid", id)
                        else MPVLib.command("set", "sid", "no")
                        dialog.dismiss()
                    },
                    onExternalSubtitleSelected = { url ->
                        currentSubtitleUrl = url
                        if (url != null) {
                            MPVLib.command("sub-add", url, "select")
                        } else {
                            MPVLib.command("set", "sid", "no")
                        }
                        dialog.dismiss()
                    },
                    onPlaybackSpeedSelected  = { speed ->
                        currentPlaybackSpeed = speed
                        MPVLib.setPropertyDouble("speed", speed.toDouble())
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) play()
            controlsManager.showControls()
        }
        dialog.show()
    }

    private fun buildTrackList(): List<MpvTrack> {
        val json = try { MPVLib.getPropertyString("track-list") ?: return emptyList() }
                   catch (e: Exception) { return emptyList() }
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                MpvTrack(
                    id = obj.getInt("id"),
                    type = obj.getString("type"),
                    title = obj.optString("title", "Track ${obj.getInt("id")}"),
                    lang = obj.optString("lang", null),
                    codec = obj.optString("codec", null),
                    isSelected = obj.optBoolean("selected", false)
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}
