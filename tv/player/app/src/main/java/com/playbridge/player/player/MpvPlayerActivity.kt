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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch

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

    // True while we've issued a new loadfile command but FILE_LOADED hasn't fired yet.
    // Suppresses the finish() that would otherwise trigger on the END_FILE event for the
    // previous stream when switching mid-playback via onNewIntent.
    private var isLoadingNewStream = false

    // Seek-stuck detection: if MPV_EVENT_PLAYBACK_RESTART doesn't follow a seek within this
    // window, ffmpeg is looping on a failed range request (e.g. "partial file" from the server).
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val seekTimeoutMs = 10_000L
    // Position captured just before each seek — used by the timeout to recover to a known-good
    // position. We cannot use positionMs at timeout time: MPV updates time-pos to the seek
    // target while seeking, so positionMs reflects the failing target, not where we came from.
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
        MPVLib.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        MPVLib.setPropertyBoolean("pause", true)
    }

    override fun isPlaying(): Boolean = isPlayingState

    override fun getMediaDuration(): Long = durationMs

    override fun getCurrentPosition(): Long = positionMs

    override fun seekTo(position: Long) {
        if (durationMs == 0L) {
            // File not loaded yet — ProgressManager called us to restore position;
            // stash it and apply once MPV_EVENT_FILE_LOADED fires.
            pendingResumePositionMs = position
            return
        }
        preSeePositionMs = positionMs
        val seconds = position / 1000.0
        // keyframes = snap to nearest keyframe, same as ExoPlayer's CLOSEST_SYNC.
        // This makes interactive seeking as fast as ExoPlayer vs the default exact seek
        // which decodes every frame between the keyframe and the target position.
        MPVLib.command("seek", seconds.toString(), "absolute+keyframes")
    }

    /** Exact seek used only for resume-position restore where precision matters. */
    private fun seekExact(position: Long) {
        preSeePositionMs = positionMs
        val seconds = position / 1000.0
        MPVLib.command("seek", seconds.toString(), "absolute")
    }

    override fun getVideoSurfaceView(): SurfaceView? =
        if (this::surfaceView.isInitialized) surfaceView else null

    // ── MPVLib.EventObserver ─────────────────────────────────────────────────

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Double) {}
    override fun eventProperty(property: String, value: MPVNode) {}

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "video-codec"    -> { videoCodecRaw = value; updateStreamInfo() }
            "audio-codec"    -> { audioCodecRaw = value; updateStreamInfo() }
            "audio-channels" -> { audioChannels = value; updateStreamInfo() }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos"      -> positionMs = value * 1000L
            "duration"      -> durationMs = value * 1000L
            "height"        -> { videoHeight = value; updateStreamInfo() }
            "video-bitrate" -> { videoBitrateBps = value; updateStreamInfo() }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            isPlayingState = !value
            runOnUiThread { controlsManager.onPlayingChanged(isPlayingState) }
        }
    }

    // libmpv event IDs (from mpv/client.h):
    //   MPV_EVENT_END_FILE = 7, MPV_EVENT_FILE_LOADED = 8,
    //   MPV_EVENT_SEEK = 20,    MPV_EVENT_PLAYBACK_RESTART = 21
    override fun event(eventId: Int, data: MPVNode) {
        when (eventId) {
            8 -> { // MPV_EVENT_FILE_LOADED
                FileLogger.i(TAG, "File loaded — duration=${durationMs}ms, resume=${pendingResumePositionMs}ms")
                runOnUiThread {
                    seekHandler.removeCallbacks(seekTimeoutRunnable)
                    isLoadingNewStream = false
                    controlsManager.onBufferingChanged(false)
                    // Apply resume position stashed by seekTo() before the file was loaded.
                    // At this point durationMs > 0 so seekTo() will execute the MPV command.
                    val resumeAt = pendingResumePositionMs
                    pendingResumePositionMs = 0L
                    if (resumeAt > 0) seekExact(resumeAt)
                    // Load first external subtitle if provided
                    subtitleUrls.firstOrNull()?.let { sub ->
                        MPVLib.command("sub-add", sub, "select")
                    }
                }
            }
            7 -> { // MPV_EVENT_END_FILE
                FileLogger.i(TAG, "End-file url=${currentUrl ?: "(unknown)"}")
                runOnUiThread {
                    // Suppress if we already issued a new loadfile (stream switch via onNewIntent).
                    // MPV fires END_FILE for the old stream before FILE_LOADED for the new one.
                    if (isLoadingNewStream) return@runOnUiThread
                    if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                        playNextInPlaylist()
                    } else {
                        progressManager.saveProgress()
                        finish()
                    }
                }
            }
            20 -> { // MPV_EVENT_SEEK
                FileLogger.d(TAG, "Seek — buffering")
                runOnUiThread {
                    controlsManager.onBufferingChanged(true)
                    seekHandler.removeCallbacks(seekTimeoutRunnable)
                    seekHandler.postDelayed(seekTimeoutRunnable, seekTimeoutMs)
                }
            }
            21 -> { // MPV_EVENT_PLAYBACK_RESTART
                FileLogger.d(TAG, "Playback restart — buffering done")
                runOnUiThread {
                    seekHandler.removeCallbacks(seekTimeoutRunnable)
                    controlsManager.onBufferingChanged(false)
                }
            }
        }
    }

    // ── Broadcast receiver ───────────────────────────────────────────────────

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_REMOTE -> {
                    when (intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)) {
                        "up", "down", "left", "right" -> {
                            if (!controlsManager.isControlsVisible()) controlsManager.showControls()
                        }
                        "enter" -> controlsManager.toggleControls()
                        "back" -> {
                            if (controlsManager.isControlsVisible()) controlsManager.hideControls()
                            else finish()
                        }
                    }
                }
                ServerService.ACTION_CONTROL -> {
                    when (intent.getStringExtra(ServerService.EXTRA_COMMAND)) {
                        "play_pause" -> controlsManager.togglePlayPause()
                        "stop" -> finish()
                        "seek_fwd" -> controlsManager.onSeekForward()
                        "seek_rev" -> controlsManager.onSeekBackward()
                    }
                }
                ServerService.ACTION_QUEUE_ADD -> {
                    ServerService.drainPendingQueueItems().forEach { payload ->
                        playlistItems.add(payload)
                    }
                    controlsManager.setPlaylistVisible(true)
                    broadcastPlaylistStatus()
                }
                ServerService.ACTION_PLAYLIST_JUMP -> {
                    val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                    if (index >= 0) playItemAtIndex(index)
                }
            }
        }
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

        // Demuxer buffer — sized for 4K REMUX bitrates (~80–100 Mbps).
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "256MiB")
        MPVLib.setOptionString("demuxer-max-back-bytes", "64MiB")

        // Network
        MPVLib.setOptionString("tls-verify", "no")
        // Disable the ytdl hook — yt-dlp is not installed, so the fallback just wastes ~1s
        // spawning subprocesses on every open failure.
        MPVLib.setOptionString("ytdl", "no")

        MPVLib.init()

        // Observe the properties we care about.
        // Format IDs from mpv/client.h: MPV_FORMAT_FLAG=3, MPV_FORMAT_INT64=4
        MPVLib.addObserver(this)
        // Format IDs: MPV_FORMAT_STRING=1, MPV_FORMAT_FLAG=3, MPV_FORMAT_INT64=4
        MPVLib.observeProperty("pause",         3) // Boolean
        MPVLib.observeProperty("time-pos",      4) // Long (seconds)
        MPVLib.observeProperty("duration",      4) // Long (seconds)
        MPVLib.observeProperty("height",        4) // Long (px)
        MPVLib.observeProperty("video-bitrate", 4) // Long (bits/s)
        MPVLib.observeProperty("video-codec",   1) // String
        MPVLib.observeProperty("audio-codec",   1) // String
        MPVLib.observeProperty("audio-channels",1) // String e.g. "stereo", "5.1"

        // Attach MPV rendering surface
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
            elapsedText           = findViewById(R.id.tv_elapsed),
            remainingText         = findViewById(R.id.tv_remaining),
            titleText             = findViewById(R.id.title_text),
            bufferingSpinner      = findViewById(R.id.buffering_spinner),
            tracksButton          = findViewById(R.id.btn_tracks),
            playlistButton        = findViewById(R.id.btn_playlist),
            prevButton            = findViewById(R.id.btn_prev),
            nextButton            = findViewById(R.id.btn_next),
            filterButton          = findViewById(R.id.btn_filter),
            getPosition           = { positionMs },
            getDuration           = { durationMs },
            isPlayerPlaying       = { isPlayingState },
            onTogglePlayPause     = {
                MPVLib.setPropertyBoolean("pause", isPlayingState) // toggle
            },
            onShowSettings        = { showSettingsDialog() },
            onShowPlaylist        = { showPlaylistPicker() },
            onSeekForwardRequested  = { handleSeek(1) },
            onSeekBackwardRequested = { handleSeek(-1) },
            onPrevious            = { playPreviousInPlaylist() },
            onNext                = { playNextInPlaylist() }
        )

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

        // Drain queue items that arrived before our receiver was registered
        ServerService.drainPendingQueueItems().forEach { payload ->
            playlistItems.add(payload)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action != KeyEvent.ACTION_DOWN) return super.onKeyDown(keyCode, event)

        val isFullOverlayVisible = controlsManager.isFullOverlayVisible()

        if (isFullOverlayVisible) {
            // Controls overlay is up — let system handle D-pad focus navigation,
            // but eat up/down so focus doesn't escape the controls row.
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { controlsManager.togglePlayPause(); true }
                KeyEvent.KEYCODE_MEDIA_PLAY  -> { play();  true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { pause(); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // Normal mode — D-pad drives seeking/volume, center pauses and opens overlay.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                pause()
                controlsManager.showControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val multiplier = if ((event.repeatCount) > 10) 5 else 1
                handleSeek(-multiplier)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val multiplier = if ((event.repeatCount) > 10) 5 else 1
                handleSeek(multiplier)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { controlsManager.togglePlayPause(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY  -> { play();  true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { pause(); true }
            KeyEvent.KEYCODE_MEDIA_STOP  -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        FileLogger.i(TAG, "=== MpvPlayerActivity DESTROYED ===")
        seekHandler.removeCallbacks(seekTimeoutRunnable)
        super.onDestroy()
        if (::progressManager.isInitialized) progressManager.saveProgress()
        if (::controlsManager.isInitialized) controlsManager.detachPlayer()
        if (receiverRegistered) {
            unregisterReceiver(remoteReceiver)
            receiverRegistered = false
        }
        if (mpvInitialized) {
            MPVLib.removeObserver(this)
            MPVLib.destroy()
        }
    }

    // ── Intent handling ──────────────────────────────────────────────────────

    private fun handleIntent(intent: Intent) {
        val url   = intent.getStringExtra(ServerService.EXTRA_URL)
        val title = intent.getStringExtra(ServerService.EXTRA_TITLE)

        intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)?.let {
            subtitleUrls = it
        }

        @Suppress("UNCHECKED_CAST")
        val headers = intent.getSerializableExtra(ServerService.EXTRA_HEADERS) as? HashMap<String, String>

        val isPlaylist      = intent.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false)
        val inMemoryPlaylist = PlaylistStore.currentPlaylist

        if (isPlaylist && inMemoryPlaylist != null && inMemoryPlaylist.isNotEmpty()) {
            playlistItems = inMemoryPlaylist.toMutableList()
            playlistIndex = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0)
            controlsManager.setPlaylistVisible(true)
        } else {
            playlistItems = mutableListOf()
            controlsManager.setPlaylistVisible(false)
        }

        if (url == null) {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        title?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            controlsManager.setTitle(it)
        }

        currentHeaders = headers

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val urlWithoutQuery = url.substringBefore("?")
            if (urlWithoutQuery.endsWith(".m3u")) {
                try {
                    val parsedPlaylist = M3uParser.fetchAndParseM3u(url, headers)
                    if (parsedPlaylist != null && parsedPlaylist.isNotEmpty()) {
                        playlistItems = parsedPlaylist.toMutableList()
                        PlaylistStore.currentPlaylist = parsedPlaylist
                        playlistIndex = 0
                        controlsManager.setPlaylistVisible(true)

                        val firstItem = parsedPlaylist[0]
                        controlsManager.setTitle(firstItem.title ?: title)
                        subtitleUrls = firstItem.subtitles ?: emptyList()
                        playVideo(firstItem.url, firstItem.headers)
                        return@launch
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG,"Error parsing M3U", e)
                }
            }
            playVideo(url, headers)
        }
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private fun playVideo(url: String, headers: Map<String, String>?) {
        currentUrl = url
        FileLogger.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        FileLogger.i(TAG, "URL: $url")
        FileLogger.i(TAG, "Title: ${controlsManager.getTitle()}")
        FileLogger.i(TAG, "Headers: $headers")
        FileLogger.i(TAG, "===========================================")


        lifecycleScope.launch {
            // seekTo() will intercept this call while durationMs == 0 (file not yet loaded)
            // and stash the position in pendingResumePositionMs; it is applied on FILE_LOADED.
            val historyItem = progressManager.restoreProgress(url)

            // Restore playlist from history if needed
            if (playlistItems.isEmpty() && historyItem?.playlistJson != null) {
                try {
                    val decoded = com.playbridge.protocol.protocolJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(
                            com.playbridge.protocol.PlayPayload.serializer()
                        ),
                        historyItem.playlistJson!!
                    )
                    if (decoded.isNotEmpty()) {
                        playlistItems = decoded.toMutableList()
                        playlistIndex = historyItem.playlistIndex
                        runOnUiThread { controlsManager.setPlaylistVisible(true) }
                    }
                } catch (e: Exception) {
                    FileLogger.w(TAG,"Failed to restore playlist: ${e.message}")
                }
            }

            val plistJson = if (playlistItems.isNotEmpty()) {
                try {
                    com.playbridge.protocol.protocolJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(
                            com.playbridge.protocol.PlayPayload.serializer()
                        ),
                        playlistItems
                    )
                } catch (e: Exception) { null }
            } else null

            progressManager.setCurrentMedia(
                url                       = url,
                title                     = controlsManager.getTitle(),
                contentType               = null,
                headers                   = headers,
                playlistJson              = plistJson,
                playlistIndex             = playlistIndex,
                preferredAudioLanguage    = null,
                preferredSubtitleLanguage = null,
                externalSubtitleUrl       = subtitleUrls.firstOrNull(),
                playbackSpeed             = currentPlaybackSpeed
            )

            runOnUiThread {
                isLoadingNewStream = true
                applyHttpHeaders(headers)
                FileLogger.i(TAG, "loadfile: $url")
                MPVLib.command("loadfile", url)
                controlsManager.onBufferingChanged(true)
            }
        }
    }

    /**
     * Translate the headers map into MPV options before each loadfile call.
     * Must be called on the main thread, before MPVLib.command("loadfile", …).
     */
    private fun applyHttpHeaders(headers: Map<String, String>?) {
        if (headers.isNullOrEmpty()) return

        headers["User-Agent"]?.let { MPVLib.setOptionString("user-agent", it) }
        headers["Referer"]?.let { MPVLib.setOptionString("referrer", it) }

        // Exclude headers that MPV manages itself (Range), browser-only fetch-metadata headers
        // (Sec-Fetch-*), and headers whose values contain commas (Accept) — MPV's
        // http-header-fields is a comma-separated list, so a comma in a value would corrupt it.
        val skipKeys = setOf("User-Agent", "Referer", "Range", "Accept", "Accept-Language",
            "Sec-Fetch-Dest", "Sec-Fetch-Site", "Sec-Fetch-Mode")
        val extra = headers.filterKeys { it !in skipKeys }
        if (extra.isNotEmpty()) {
            MPVLib.setOptionString(
                "http-header-fields",
                extra.entries.joinToString(",") { "${it.key}: ${it.value}" }
            )
        }
    }

    // ── Seek ─────────────────────────────────────────────────────────────────

    private fun handleSeek(direction: Int) {
        val seekStepMs = 10_000L * direction
        val target = (positionMs + seekStepMs).coerceIn(0, durationMs)
        controlsManager.setPendingSeekTime(target)
        controlsManager.showSeekUI()
        seekTo(target)
    }

    // ── Playlist ─────────────────────────────────────────────────────────────

    private fun playNextInPlaylist() {
        if (playlistItems.isEmpty() || playlistIndex >= playlistItems.size - 1) return
        playlistIndex++
        playPlaylistItem(playlistItems[playlistIndex])
        broadcastPlaylistStatus()
    }

    private fun playPreviousInPlaylist() {
        if (playlistItems.isEmpty() || playlistIndex <= 0) return
        playlistIndex--
        playPlaylistItem(playlistItems[playlistIndex])
        broadcastPlaylistStatus()
    }

    private fun playItemAtIndex(index: Int) {
        if (playlistItems.isEmpty() || index < 0 || index >= playlistItems.size) return
        playlistIndex = index
        playPlaylistItem(playlistItems[index])
        broadcastPlaylistStatus()
    }

    private fun playPlaylistItem(item: com.playbridge.protocol.PlayPayload) {
        progressManager.saveProgress()
        controlsManager.setTitle(item.title)
        subtitleUrls = item.subtitles ?: emptyList()
        playVideo(item.url, item.headers)
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
            FileLogger.e(TAG,"Failed to broadcast playlist status", e)
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val wasPlaying = isPlayingState
        if (wasPlaying) pause()

        val tracks = buildTrackList()
        val audioTracks = tracks.filter { it.type == "audio" }
        val subtitleTracks = tracks.filter { it.type == "sub" }

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setContent {
            PlayBridgeTVTheme {
                MpvTrackSelectionDialog(
                    audioTracks              = audioTracks,
                    subtitleTracks           = subtitleTracks,
                    externalSubtitleUrls     = subtitleUrls,
                    currentExternalSubtitleUrl = currentSubtitleUrl,
                    currentPlaybackSpeed     = currentPlaybackSpeed,
                    onDismiss                = { dialog.dismiss() },
                    onAudioTrackSelected     = { id ->
                        if (id != null) MPVLib.command("set", "aid", id.toString())
                        dialog.dismiss()
                    },
                    onSubtitleTrackSelected  = { id ->
                        if (id != null) {
                            MPVLib.command("set", "sid", id.toString())
                            currentSubtitleUrl = null
                        } else {
                            MPVLib.command("set", "sid", "no")
                            currentSubtitleUrl = null
                        }
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
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = obj.optString("type")
                if (type !in listOf("audio", "sub")) return@mapNotNull null
                val id    = obj.optInt("id")
                val title = obj.optString("title").takeIf { it.isNotBlank() }
                    ?: obj.optString("lang").takeIf { it.isNotBlank() }
                    ?: "Track $id"
                MpvTrack(
                    id         = id,
                    type       = type,
                    title      = title,
                    lang       = obj.optString("lang").takeIf { it.isNotBlank() },
                    codec      = obj.optString("codec").takeIf { it.isNotBlank() },
                    isSelected = obj.optBoolean("selected")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun updateStreamInfo() {
        val info = buildString {
            if (videoHeight > 0) append("${videoHeight}p")
            if (videoCodecRaw.isNotBlank()) {
                val codec = when {
                    videoCodecRaw.contains("hevc", ignoreCase = true) ||
                    videoCodecRaw.contains("h265", ignoreCase = true) -> "HEVC"
                    videoCodecRaw.contains("avc",  ignoreCase = true) ||
                    videoCodecRaw.contains("h264", ignoreCase = true) -> "H.264"
                    videoCodecRaw.contains("av1",  ignoreCase = true) -> "AV1"
                    videoCodecRaw.contains("vp9",  ignoreCase = true) -> "VP9"
                    videoCodecRaw.contains("vp8",  ignoreCase = true) -> "VP8"
                    else -> videoCodecRaw.uppercase()
                }
                if (isNotEmpty()) append(" ")
                append(codec)
            }
            if (videoBitrateBps > 0) {
                if (isNotEmpty()) append(" ")
                append("%.1fMbps".format(videoBitrateBps / 1_000_000.0))
            }
            if (audioCodecRaw.isNotBlank()) {
                val codec = when {
                    audioCodecRaw.contains("eac3",  ignoreCase = true) ||
                    audioCodecRaw.contains("e-ac-3", ignoreCase = true) -> "EAC3"
                    audioCodecRaw.contains("ac3",   ignoreCase = true) ||
                    audioCodecRaw.contains("ac-3",  ignoreCase = true) -> "AC3"
                    audioCodecRaw.contains("aac",   ignoreCase = true) -> "AAC"
                    audioCodecRaw.contains("dts",   ignoreCase = true) -> "DTS"
                    audioCodecRaw.contains("flac",  ignoreCase = true) -> "FLAC"
                    audioCodecRaw.contains("mp3",   ignoreCase = true) -> "MP3"
                    else -> audioCodecRaw.uppercase()
                }
                if (isNotEmpty()) append(" · ")
                append(codec)
            }
            if (audioChannels.isNotBlank()) {
                val ch = when (audioChannels) {
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

    private fun showPlaylistPicker() {
        if (playlistItems.isEmpty()) return

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
                    items = playlistItems,
                    currentIndex = playlistIndex,
                    onItemSelected = { index ->
                        dialog.dismiss()
                        playItemAtIndex(index)
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
}
