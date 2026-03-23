package com.playbridge.receiver.player

import android.content.Intent
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.playbridge.receiver.R
import com.playbridge.receiver.server.ServerService
import com.playbridge.receiver.data.HistoryStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.playbridge.receiver.ui.theme.PlayBridgeTVTheme
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import com.playbridge.receiver.logging.FileLogger

class VlcPlayerActivity : PlayerActivity(), IVLCVout.Callback {

    companion object {
        private const val TAG = "VlcPlayerActivity"
    }

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var controlsManager: VlcControlsManager

    // Settings state
    private var subtitleUrls: List<String> = emptyList()
    private var currentSubtitleUrl: String? = null
    private var currentPlaybackSpeed: Float = 1.0f
    private var currentVideoScalingMode: String = "Fit"

    // Settings state
    private var originalM3u8Url: String? = null
    private var currentHeaders: Map<String, String>? = null

    // Playlist state
    private var playlistItems: MutableList<com.playbridge.protocol.PlayPayload> = mutableListOf()
    private var playlistIndex: Int = 0

    // Settings dialog state
    private var activeDialog: android.app.Dialog? = null
    private var surfaceLayoutListener: android.view.View.OnLayoutChangeListener? = null

    private lateinit var progressManager: ProgressManager
    private lateinit var historyStore: HistoryStore

    // Seek buffering
    private var pendingSeekTime: Long? = null
    private var pendingResumeTime: Long? = null
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Timestamp of the most recently committed seek. Each seek captures this value in a closure;
    // if the value has changed by the time the resync runnable fires, a newer seek superseded it
    // and the resync is skipped.
    private var lastSeekCommitTime = 0L

    private val performSeekRunnable = Runnable {
        pendingSeekTime?.let { targetTime ->
            mediaPlayer?.time = targetTime
            pendingSeekTime = null
            // Do NOT clear the pending seek time in the controls manager yet.
            // VLC's seek is async — player.time still reflects the pre-seek position until VLC
            // confirms it. Clearing activePendingSeekTime here would let the 1s poll snap the
            // seekbar back to the old position. Instead, keep showing the target and let
            // VlcControlsManager.updateProgress() auto-clear once player.time catches up.
            schedulePostSeekVideoResync()
        }
    }

    /**
     * Schedule a post-seek video decoder resync to fix frozen video in malformed MKV files.
     *
     * When seeking in an MKV with corrupt EBML data, VLC's parser resyncs forward past the bad
     * cluster. Audio recovers immediately (small/frequent packets), but the video decoder loses
     * its keyframe reference and shows the last decoded frame indefinitely — player.time advances
     * with audio, making the freeze invisible to event-based detection.
     *
     * Fix: 2s after the seek commits (by which point the demuxer is in clean data), toggle the
     * video track off→on. This closes the video decoder without re-seeking, then reopens it at
     * the current demuxer position. VLC waits for the next keyframe (already in the clean region)
     * and video resumes. Brief black screen (~100–500ms), but recovers a permanently frozen frame.
     */
    private fun schedulePostSeekVideoResync() {
        val commitTime = System.currentTimeMillis()
        lastSeekCommitTime = commitTime
        seekHandler.postDelayed({
            if (lastSeekCommitTime != commitTime) return@postDelayed // superseded by a newer seek
            val player = mediaPlayer ?: return@postDelayed
            if (!player.isPlaying || pendingSeekTime != null) return@postDelayed

            val videoTrack = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)
                ?: return@postDelayed

            FileLogger.d(TAG,"Post-seek video decoder resync (track toggle)")
            player.unselectTrackType(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)
            seekHandler.postDelayed({
                if (lastSeekCommitTime == commitTime) {
                    mediaPlayer?.selectTrack(videoTrack.id)
                }
            }, 200)
        }, 2000)
    }

    override fun play() { mediaPlayer?.play() }
    override fun pause() { mediaPlayer?.pause() }
    override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    override fun getMediaDuration(): Long = mediaPlayer?.length ?: 0L
    override fun getCurrentPosition(): Long = (mediaPlayer?.time) ?: 0L
    override fun seekTo(position: Long) {
        mediaPlayer?.time = position
        pendingSeekTime = null
        seekHandler.removeCallbacks(performSeekRunnable)
    }
    override fun getVideoSurfaceView(): android.view.SurfaceView? = if (this::surfaceView.isInitialized) surfaceView else null

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ServerService.ACTION_REMOTE) {
                val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                when (key) {
                    "up", "down", "left", "right" -> {
                        if (!controlsManager.isControlsVisible()) {
                            controlsManager.showControls()
                        }
                    }
                    "enter" -> {
                        controlsManager.toggleControls()
                    }
                    "back" -> {
                        if (controlsManager.isControlsVisible()) {
                            controlsManager.hideControls()
                        } else {
                            finish()
                        }
                    }
                }
            } else if (intent?.action == ServerService.ACTION_CONTROL) {
                when (intent.getStringExtra(ServerService.EXTRA_COMMAND)) {
                    "play_pause" -> controlsManager.togglePlayPause()
                    "stop" -> finish()
                    "seek_fwd" -> controlsManager.onSeekForward()
                    "seek_rev" -> controlsManager.onSeekBackward()
                }
            } else if (intent?.action == ServerService.ACTION_QUEUE_ADD) {
                ServerService.drainPendingQueueItems().forEach { payload ->
                    playlistItems.add(payload)
                }
                controlsManager.setPlaylistVisible(true)
                broadcastPlaylistStatus()
            } else if (intent?.action == ServerService.ACTION_PLAYLIST_JUMP) {
                val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                if (index >= 0) {
                    playItemAtIndex(index) // broadcasts status internally
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.i(TAG, "=== VlcPlayerActivity CREATED ===")
        FileLogger.i(TAG, "Intent action: ${intent?.action}")

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::controlsManager.isInitialized && controlsManager.isControlsVisible()) {
                    controlsManager.hideControls()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContentView(R.layout.activity_vlc_player)
        surfaceView = findViewById(R.id.surface_view)

        historyStore = HistoryStore(this)
        progressManager = ProgressManager(
            context = this,
            historyStore = historyStore,
            lifecycleScope = lifecycleScope,
            playerActivity = this
        )

        // Setup VLC
        val args = ArrayList<String>().apply {
            add("-vvv") // Verbosity
        }
        libVLC = LibVLC(this, args)
        mediaPlayer = MediaPlayer(libVLC)

        mediaPlayer?.vlcVout?.apply {
            setVideoView(surfaceView)
            addCallback(this@VlcPlayerActivity)
            attachViews()
        }

        // Set video scale to 0 (fit to screen)
        mediaPlayer?.scale = 0f

        controlsManager = VlcControlsManager(
            controlsRoot = findViewById(R.id.controls_root),
            controlsPanel = findViewById(R.id.controls_panel),
            seekBar = findViewById(R.id.player_seekbar),
            playPauseButton = findViewById(R.id.btn_play_pause),
            streamInfoText = findViewById(R.id.tv_stream_info),
            elapsedText = findViewById(R.id.tv_elapsed),
            remainingText = findViewById(R.id.tv_remaining),
            titleText = findViewById(R.id.title_text),
            bufferingSpinner = findViewById(R.id.buffering_spinner),
            playerProvider = { mediaPlayer },
            tracksButton = findViewById(R.id.btn_tracks),
            playlistButton = findViewById(R.id.btn_playlist),
            prevButton = findViewById(R.id.btn_prev),
            nextButton = findViewById(R.id.btn_next),
            filterButton = findViewById(R.id.btn_filter),
            onShowSettings = { showSettingsDialog() },
            onShowPlaylist = { showPlaylistPicker() },
            onError = { handleVlcError() },
            onSeekForwardRequested = { handleSeek(1) },
            onSeekBackwardRequested = { handleSeek(-1) },
            onPrevious = { playPreviousInPlaylist() },
            onNext = { playNextInPlaylist() }
        )

        controlsManager.attachPlayer()

        // Single unified event listener — VlcControlsManager.handleEvent() handles UI state;
        // activity-level logic (EndReached, pendingResumeTime) is handled here.
        // Must be set AFTER controlsManager is initialised so handleEvent() is safe to call.
        mediaPlayer?.setEventListener { event ->
            controlsManager.handleEvent(event)
            when (event.type) {
                MediaPlayer.Event.Opening ->
                    FileLogger.i(TAG, "Opening: ${originalM3u8Url ?: "(unknown)"}")

                MediaPlayer.Event.Buffering -> {
                    val pct = event.buffering.toInt()
                    if (pct < 100) FileLogger.d(TAG, "Buffering: $pct%")
                }

                MediaPlayer.Event.Playing -> {
                    FileLogger.i(TAG, "Playing at ${mediaPlayer?.time ?: 0}ms")
                    // Defer resume seek until VLC is actually playing and ready to accept seeks.
                    pendingResumeTime?.let { resumeAt ->
                        pendingResumeTime = null
                        runOnUiThread { mediaPlayer?.time = resumeAt }
                    }
                }

                MediaPlayer.Event.Paused ->
                    FileLogger.i(TAG, "Paused at ${mediaPlayer?.time ?: 0}ms")

                MediaPlayer.Event.Stopped ->
                    FileLogger.i(TAG, "Stopped")

                MediaPlayer.Event.EncounteredError ->
                    FileLogger.e(TAG, "VLC encountered an error (url=${originalM3u8Url ?: "(unknown)"})")

                MediaPlayer.Event.EndReached -> {
                    FileLogger.i(TAG, "End reached — playlist size=${playlistItems.size}, index=$playlistIndex")
                    if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                        runOnUiThread { playNextInPlaylist() }
                    } else {
                        runOnUiThread { finish() }
                    }
                }
            }
        }

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

    private fun handleIntent(intent: Intent) {
        val url = intent.getStringExtra(ServerService.EXTRA_URL)
        val title = intent.getStringExtra(ServerService.EXTRA_TITLE)

        val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)
        if (subtitles != null) {
            subtitleUrls = subtitles
        }

        @Suppress("UNCHECKED_CAST")
        val headers = intent.getSerializableExtra(ServerService.EXTRA_HEADERS) as? HashMap<String, String>

        // Read playlist if present
        val isPlaylist = intent.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false)
        val inMemoryPlaylist = PlaylistStore.currentPlaylist

        // Restore saved selections from history or incoming intent preferences
        intent.getStringExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG)?.let {
            // VlcPlayerActivity doesn't track these top-level currently like ExoPlayerActivity
            // We can just log or store them if needed later.
            // FileLogger.i(TAG, "Restored preferred audio language: $it")
        }
        intent.getStringExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG)?.let {
            // preferredSubtitleLanguage = it
            // FileLogger.i(TAG, "Restored preferred subtitle language: $it")
        }
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

        if (title != null) {
            Toast.makeText(this, title, Toast.LENGTH_SHORT).show()
            controlsManager.setTitle(title)
        }

        originalM3u8Url = url
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
                        originalM3u8Url = firstItem.url
                        currentHeaders = firstItem.headers
                        subtitleUrls = firstItem.subtitles ?: emptyList()
                        currentSubtitleUrl = null

                        if (firstItem.url.contains(".m3u8", ignoreCase = true)) {
                            M3uParser.parseMasterPlaylist(firstItem.url, firstItem.headers)
                        }
                        playVideo(firstItem.url, firstItem.headers)
                        return@launch
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Error parsing M3U", e)
                }
            }
            playVideo(url, headers)
        }
    }

    private fun playNextInPlaylist() {
        if (playlistItems.isEmpty() || playlistIndex >= playlistItems.size - 1) return
        playlistIndex++
        val nextItem = playlistItems[playlistIndex]
        playPlaylistItem(nextItem)
        broadcastPlaylistStatus()
    }

    private fun playPreviousInPlaylist() {
        if (playlistItems.isEmpty() || playlistIndex <= 0) return
        playlistIndex--
        val prevItem = playlistItems[playlistIndex]
        playPlaylistItem(prevItem)
        broadcastPlaylistStatus()
    }

    private fun playItemAtIndex(index: Int) {
        if (playlistItems.isEmpty() || index < 0 || index >= playlistItems.size) return
        playlistIndex = index
        playPlaylistItem(playlistItems[index])
        broadcastPlaylistStatus()
    }

    /**
     * Broadcast current playlist state to the phone via WebSocket.
     */
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

    private fun showPlaylistPicker() {
        if (playlistItems.isEmpty()) return

        val player = mediaPlayer ?: return
        val wasPlaying = player.isPlaying
        if (wasPlaying) player.pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            com.playbridge.receiver.ui.theme.PlayBridgeTVTheme {
                PlaylistPickerDialog(
                    items = playlistItems,
                    currentIndex = playlistIndex,
                    onItemSelected = { index ->
                        dialog.dismiss()
                        playItemAtIndex(index)
                    },
                    onDismiss = {
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) player.play()
            controlsManager.showControls()
        }
        dialog.show()
    }

    private fun playPlaylistItem(item: com.playbridge.protocol.PlayPayload) {
        syncSelectionsToProgressManager()
        progressManager.saveProgress()

        controlsManager.setTitle(item.title)
        originalM3u8Url = item.url
        currentHeaders = item.headers
        subtitleUrls = item.subtitles ?: emptyList()
        currentSubtitleUrl = null

        if (item.url.contains(".m3u8", ignoreCase = true)) {
            lifecycleScope.launch {
                M3uParser.parseMasterPlaylist(item.url, item.headers)
                playVideo(item.url, item.headers)
            }
        } else {
            playVideo(item.url, item.headers)
        }
    }

    private fun handleVlcError() {
        FileLogger.e(TAG, "handleVlcError — playlist size=${playlistItems.size}, index=$playlistIndex, url=${originalM3u8Url ?: "(unknown)"}")
        runOnUiThread {
            if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                android.widget.Toast.makeText(this, "Link failed, skipping to next...", android.widget.Toast.LENGTH_SHORT).show()
                playNextInPlaylist()
            } else {
                android.widget.Toast.makeText(this, "VLC encountered an error", android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun playVideo(url: String, headers: Map<String, String>?, resumeTime: Long? = null, startPaused: Boolean = false) {
        val title = controlsManager.getTitle()
        FileLogger.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        FileLogger.i(TAG, "URL: $url")
        FileLogger.i(TAG, "Title: $title")
        FileLogger.i(TAG, "Headers: $headers")
        FileLogger.i(TAG, "===========================================")


        lifecycleScope.launch {
            val historyItem = progressManager.restoreProgress(url)

            // Fallback: if playlist context wasn't available when handleIntent() ran
            // (e.g. PlaylistStore was cleared between MainActivity setting it and this
            // activity reading it), restore the playlist directly from the history item.
            if (playlistItems.isEmpty() && historyItem?.playlistJson != null) {
                try {
                    val decoded = com.playbridge.protocol.protocolJson.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(com.playbridge.protocol.PlayPayload.serializer()),
                        historyItem.playlistJson!!
                    )
                    if (decoded.isNotEmpty()) {
                        playlistItems = decoded.toMutableList()
                        playlistIndex = historyItem.playlistIndex
                        controlsManager.setPlaylistVisible(true)
                        FileLogger.i(TAG,"Restored playlist from history: ${playlistItems.size} items at index $playlistIndex")
                    }
                } catch (e: Exception) {
                    FileLogger.w(TAG,"Failed to restore playlist from history: ${e.message}")
                }
            }

            // Build playlist JSON for history persistence (computed after fallback restore)
            val plistJson = if (playlistItems.isNotEmpty()) {
                try {
                    com.playbridge.protocol.protocolJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(com.playbridge.protocol.PlayPayload.serializer()),
                        playlistItems
                    )
                } catch (e: Exception) { null }
            } else null

            progressManager.setCurrentMedia(
                url = url,
                title = title,
                contentType = null,
                headers = headers,
                playlistJson = plistJson,
                playlistIndex = playlistIndex,
                preferredAudioLanguage = null, // Vlc uses track ID internally, not lang directly in UI
                preferredSubtitleLanguage = null,
                externalSubtitleUrl = currentSubtitleUrl,
                playbackSpeed = currentPlaybackSpeed,
                videoScalingMode = when (currentVideoScalingMode) {
                    "Fit" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    "Fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    "Center" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    else -> null
                }
            )

            val finalResumeTime = resumeTime ?: historyItem?.position

            val media = Media(libVLC, Uri.parse(url)).apply {
                setHWDecoderEnabled(true, false)

                // Apply headers to VLC
                headers?.forEach { (key, value) ->
                    when (key.lowercase()) {
                        "user-agent" -> addOption(":http-user-agent=$value")
                        "referer" -> addOption(":http-referrer=$value")
                    }
                }

                // Reconstruct remaining custom headers to VLC's format if they are not agent/referer
                val customHeaders = headers?.filter { entry ->
                    val lowerKey = entry.key.lowercase()
                    lowerKey != "user-agent" && lowerKey != "referer"
                }?.map { "${it.key}: ${it.value}" }?.joinToString("\r\n")

                if (!customHeaders.isNullOrBlank()) {
                    addOption(":http-custom-headers=$customHeaders")
                }
            }

            mediaPlayer?.stop()
            mediaPlayer?.media = media
            media.release()

            // Restore settings from history if present
            historyItem?.playbackSpeed?.let {
                currentPlaybackSpeed = it
                mediaPlayer?.rate = it
            }
            historyItem?.videoScalingMode?.let {
                currentVideoScalingMode = when (it) {
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
                    else -> "Fit"
                }
                when (currentVideoScalingMode) {
                    "Fit" -> { mediaPlayer?.scale = 0f; mediaPlayer?.aspectRatio = null }
                    "Fill" -> { mediaPlayer?.scale = 0f; mediaPlayer?.aspectRatio = null }
                }
            }
            historyItem?.externalSubtitleUrl?.let {
                currentSubtitleUrl = it
                mediaPlayer?.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, android.net.Uri.parse(it), true)
            }

            // Stage the resume seek so the Playing event handler applies it once VLC
            // has buffered enough to reliably accept the command.
            pendingResumeTime = if (finalResumeTime != null && finalResumeTime > 0) finalResumeTime else null

            mediaPlayer?.play()

            if (startPaused) {
                mediaPlayer?.pause()
            }
        }
    }

    private fun syncSelectionsToProgressManager() {
        progressManager.updateSelections(
            externalSubtitleUrl = currentSubtitleUrl,
            playbackSpeed = currentPlaybackSpeed,
            videoScalingMode = when (currentVideoScalingMode) {
                "Fit" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                "Fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                "Center" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                else -> null
            }
        )
    }

    private fun showSettingsDialog() {
        val player = mediaPlayer ?: return
        val wasPlaying = player.isPlaying
        if (wasPlaying) player.pause()

        val videoTracks = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)?.toList() ?: emptyList()
        val currentVideoTrack = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)?.id

        val audioTracks = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio)?.toList() ?: emptyList()
        val currentAudioTrack = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio)?.id

        val subtitleTracks = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text)?.toList() ?: emptyList()
        val currentSubtitleTrack = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text)?.id

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            PlayBridgeTVTheme {
                // Reactive state for UI updates
                var liveCurrentVideoTrack by remember { mutableStateOf(currentVideoTrack) }
                var liveCurrentAudioTrack by remember { mutableStateOf(currentAudioTrack) }
                var liveCurrentSubtitleTrack by remember { mutableStateOf(currentSubtitleTrack) }
                var liveCurrentSubtitleUrl by remember { mutableStateOf(currentSubtitleUrl) }
                var liveCurrentPlaybackSpeed by remember { mutableFloatStateOf(currentPlaybackSpeed) }
                var liveCurrentVideoScalingMode by remember { mutableStateOf(currentVideoScalingMode) }

                VlcTrackSelectionDialog(
                    videoTracks = videoTracks,
                    currentVideoTrack = liveCurrentVideoTrack,
                    audioTracks = audioTracks,
                    currentAudioTrack = liveCurrentAudioTrack,
                    subtitleTracks = subtitleTracks,
                    currentSubtitleTrack = liveCurrentSubtitleTrack,
                    externalSubtitleUrls = subtitleUrls,
                    currentExternalSubtitleUrl = liveCurrentSubtitleUrl,
                    currentPlaybackSpeed = liveCurrentPlaybackSpeed,
                    currentVideoScalingMode = liveCurrentVideoScalingMode,
                    onDismiss = {
                        dialog.dismiss()
                    },
                    onVideoTrackSelected = { id ->
                        if (id == null) player.unselectTrackType(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video) else player.selectTrack(id)
                        liveCurrentVideoTrack = id
                    },
                    onAudioTrackSelected = { id ->
                        if (id == null) player.unselectTrackType(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio) else player.selectTrack(id)
                        liveCurrentAudioTrack = id
                    },
                    onSubtitleTrackSelected = { id ->
                        if (id == null) player.unselectTrackType(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text) else player.selectTrack(id)
                        liveCurrentSubtitleTrack = id
                        if (id != null) {
                            currentSubtitleUrl = null
                            liveCurrentSubtitleUrl = null
                        }
                    },
                    onExternalSubtitleSelected = { url ->
                        currentSubtitleUrl = url
                        liveCurrentSubtitleUrl = url
                        if (url != null) {
                            player.unselectTrackType(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text) // Disable embedded
                            liveCurrentSubtitleTrack = null
                            // In LibVLC Android bindings, the enum is IMedia.Slave.Type
                            player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, android.net.Uri.parse(url), true)
                        }
                    },
                    onPlaybackSpeedSelected = { speed ->
                        currentPlaybackSpeed = speed
                        liveCurrentPlaybackSpeed = speed
                        player.rate = speed
                    },
                    onVideoScalingSelected = { mode ->
                        currentVideoScalingMode = mode
                        liveCurrentVideoScalingMode = mode
                        when (mode) {
                            "Fit" -> {
                                player.scale = 0f
                                player.aspectRatio = null
                            }
                            "Fill" -> {
                                player.scale = 0f
                                // For fill we use the display aspect ratio (handled internally by scale=0 but we could force crop)
                                // Standard libvlc fill might require exact window size ratio, but for simplicity:
                                player.aspectRatio = null
                                player.scale = 0f // Let Vout scale
                            }
                            "16:9" -> {
                                player.scale = 0f
                                player.aspectRatio = "16:9"
                            }
                            "4:3" -> {
                                player.scale = 0f
                                player.aspectRatio = "4:3"
                            }
                            "Center" -> {
                                player.scale = 1f // Original size
                                player.aspectRatio = null
                            }
                        }
                    }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) player.play()
            controlsManager.showControls()
        }
        dialog.show()
    }

    override fun onDestroy() {
        FileLogger.i(TAG, "=== VlcPlayerActivity DESTROYED ===")
        activeDialog?.dismiss()
        syncSelectionsToProgressManager()
        progressManager.saveProgress()
        super.onDestroy()
        seekHandler.removeCallbacks(performSeekRunnable)
        unregisterReceiver(remoteReceiver)

        // Capture and null out references before any cleanup so nothing else uses them.
        val playerToRelease = mediaPlayer
        val vlcToRelease = libVLC
        mediaPlayer = null
        libVLC = null

        // UI-side cleanup is fast and must stay on the main thread.
        playerToRelease?.setEventListener(null)
        controlsManager.detachPlayer()
        playerToRelease?.vlcVout?.apply {
            removeCallback(this@VlcPlayerActivity)
            detachViews()
        }

        // Native VLC teardown can be slow; run it off the main thread to avoid an ANR in
        // MainActivity (same process, same main thread) when the user presses Back.
        Thread {
            playerToRelease?.release()
            vlcToRelease?.release()
        }.start()
    }

    // IVLCVout.Callback
    override fun onSurfacesCreated(vout: IVLCVout?) {
        val width = surfaceView.width
        val height = surfaceView.height
        if (width > 0 && height > 0) {
            vout?.setWindowSize(width, height)
        }
    }

    override fun onSurfacesDestroyed(vout: IVLCVout?) {}

    private fun handleSeek(multiplier: Int) {
        val player = mediaPlayer ?: return
        val length = player.length

        val currentTime = pendingSeekTime ?: player.time
        val offset = 10000L * multiplier
        val newTime = (currentTime + offset).coerceIn(0, if (length > 0) length else Long.MAX_VALUE)

        pendingSeekTime = newTime
        controlsManager.setPendingSeekTime(newTime)

        seekHandler.removeCallbacks(performSeekRunnable)
        seekHandler.postDelayed(performSeekRunnable, 400)

        controlsManager.showSeekUI()
    }

    // Handle physical remote/keyboard events
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event?.action != android.view.KeyEvent.ACTION_DOWN) return super.onKeyDown(keyCode, event)

        val isFullOverlayVisible = controlsManager.isFullOverlayVisible()

        if (isFullOverlayVisible) {
            // When full overlay is visible, let the system handle D-pad navigation for focus,
            // except for DPAD_UP/DPAD_DOWN which we ignore to prevent focus jumping off controls.
            return when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> super.onKeyDown(keyCode, event)
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    controlsManager.togglePlayPause()
                    true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    mediaPlayer?.play()
                    true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    mediaPlayer?.pause()
                    true
                }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        // When full overlay is hidden (or only seek UI is visible), D-pad controls playback (ExoPlayer parity)
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                mediaPlayer?.pause()
                controlsManager.showControls()
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                val repeatCount = event.repeatCount
                val multiplier = if (repeatCount > 10) 5 else 1
                handleSeek(-multiplier)
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val repeatCount = event.repeatCount
                val multiplier = if (repeatCount > 10) 5 else 1
                handleSeek(multiplier)
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                controlsManager.togglePlayPause()
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                mediaPlayer?.play()
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                mediaPlayer?.pause()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }


    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
        syncSelectionsToProgressManager()
        lifecycleScope.launch {
            val bitmap = progressManager.captureBitmapSuspend()
            progressManager.saveProgress(bitmap)
        }
    }

    override fun onResume() {
        super.onResume()
        surfaceLayoutListener?.let { surfaceView.removeOnLayoutChangeListener(it) }
        val listener = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0) {
                mediaPlayer?.vlcVout?.setWindowSize(width, height)
            }
        }
        surfaceLayoutListener = listener
        surfaceView.addOnLayoutChangeListener(listener)
    }
}