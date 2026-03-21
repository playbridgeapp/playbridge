package com.playbridge.receiver.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.playbridge.receiver.R
import com.playbridge.receiver.data.HistoryStore
import com.playbridge.receiver.server.ServerService
import com.playbridge.receiver.ui.theme.PlayBridgeTVTheme
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
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

    private lateinit var surfaceView: SurfaceView
    private lateinit var controlsManager: MpvControlsManager
    private lateinit var progressManager: ProgressManager
    private lateinit var historyStore: HistoryStore

    // Current media state (updated via MPV property observers)
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L
    private var isPlayingState: Boolean = false

    // Settings state
    private var subtitleUrls: List<String> = emptyList()
    private var currentHeaders: Map<String, String>? = null
    private var currentUrl: String? = null
    private var currentPlaybackSpeed: Float = 1.0f

    // Position to seek to once MPV_EVENT_FILE_LOADED fires
    private var pendingResumePositionMs: Long = 0L

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
        val seconds = position / 1000.0
        MPVLib.command("seek", seconds.toString(), "absolute")
    }

    override fun getVideoSurfaceView(): SurfaceView? =
        if (this::surfaceView.isInitialized) surfaceView else null

    // ── MPVLib.EventObserver ─────────────────────────────────────────────────

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) {}
    override fun eventProperty(property: String, value: MPVNode) {}

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos" -> positionMs = value * 1000L
            "duration"  -> durationMs = value * 1000L
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
                runOnUiThread {
                    controlsManager.onBufferingChanged(false)
                    // Apply resume position stashed by seekTo() before the file was loaded.
                    // At this point durationMs > 0 so seekTo() will execute the MPV command.
                    val resumeAt = pendingResumePositionMs
                    pendingResumePositionMs = 0L
                    if (resumeAt > 0) seekTo(resumeAt)
                    // Load first external subtitle if provided
                    subtitleUrls.firstOrNull()?.let { sub ->
                        MPVLib.command("sub-add", sub, "select")
                    }
                }
            }
            7 -> { // MPV_EVENT_END_FILE
                runOnUiThread {
                    if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                        playNextInPlaylist()
                    } else {
                        progressManager.saveProgress()
                        finish()
                    }
                }
            }
            20 -> runOnUiThread { controlsManager.onBufferingChanged(true) }  // MPV_EVENT_SEEK
            21 -> runOnUiThread { controlsManager.onBufferingChanged(false) } // MPV_EVENT_PLAYBACK_RESTART
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
        MPVLib.create(this)
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("ao", "audiotrack")
        MPVLib.setOptionString("tls-verify", "no")
        MPVLib.setOptionString("network-timeout", "15")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "32MiB")
        MPVLib.setOptionString("demuxer-max-back-bytes", "8MiB")
        MPVLib.init()

        // Observe the properties we care about.
        // Format IDs from mpv/client.h: MPV_FORMAT_FLAG=3, MPV_FORMAT_INT64=4
        MPVLib.addObserver(this)
        MPVLib.observeProperty("pause",    3) // MPV_FORMAT_FLAG
        MPVLib.observeProperty("time-pos", 4) // MPV_FORMAT_INT64
        MPVLib.observeProperty("duration", 4) // MPV_FORMAT_INT64

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

    override fun onDestroy() {
        super.onDestroy()
        progressManager.saveProgress()
        controlsManager.detachPlayer()
        unregisterReceiver(remoteReceiver)
        MPVLib.removeObserver(this)
        MPVLib.destroy()
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
                    android.util.Log.e("MpvPlayerActivity", "Error parsing M3U", e)
                }
            }
            playVideo(url, headers)
        }
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private fun playVideo(url: String, headers: Map<String, String>?) {
        currentUrl = url

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
                    android.util.Log.w("MpvPlayerActivity", "Failed to restore playlist: ${e.message}")
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
                applyHttpHeaders(headers)
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

        val extra = headers.filterKeys { it !in listOf("User-Agent", "Referer") }
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
            android.util.Log.e("MpvPlayerActivity", "Failed to broadcast playlist status", e)
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        // Speed and subtitle controls via a simple dialog — can be expanded later.
        val speeds = arrayOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val currentIdx = speedValues.indexOfFirst { it == currentPlaybackSpeed }.coerceAtLeast(0)

        pause()
        android.app.AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(speeds, currentIdx) { dialog, which ->
                currentPlaybackSpeed = speedValues[which]
                MPVLib.setPropertyDouble("speed", currentPlaybackSpeed.toDouble())
                dialog.dismiss()
                play()
            }
            .setNegativeButton("Cancel") { _, _ -> play() }
            .show()
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
