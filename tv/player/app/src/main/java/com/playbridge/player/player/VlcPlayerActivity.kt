package com.playbridge.player.player

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
import com.playbridge.player.R
import com.playbridge.player.server.ServerService
import com.playbridge.player.data.HistoryStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import com.playbridge.player.logging.FileLogger

class VlcPlayerActivity : PlayerActivity(), IVLCVout.Callback {

    companion object {
        private const val TAG = "VlcPlayerActivity"
    }

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var controlsManager: VlcControlsManager

    private val playerLock = Any()

    private fun setupMediaPlayer() {
        synchronized(playerLock) {
            mediaPlayer?.let { oldPlayer ->
                oldPlayer.setEventListener(null)
                oldPlayer.vlcVout.detachViews()
                oldPlayer.release()
            }

            val player = MediaPlayer(libVLC)
            runOnUiThread {
                if (::surfaceView.isInitialized) {
                    surfaceView.visibility = android.view.View.VISIBLE
                }
            }
            player.vlcVout.apply {
                setVideoView(surfaceView)
                addCallback(this@VlcPlayerActivity)
                attachViews()
            }
            player.scale = 0f
            player.setEventListener { event ->
                controlsManager.handleEvent(event)
                handlePlayerEvent(event)
            }
            mediaPlayer = player
            controlsManager.attachPlayer()
        }
    }

    private fun handlePlayerEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening ->
                FileLogger.i(TAG, "Opening: ${originalM3u8Url ?: "(unknown)"}")

            MediaPlayer.Event.Buffering -> {
                val pct = event.buffering.toInt()
                if (pct < 100) FileLogger.d(TAG, "Buffering: $pct%")
            }

            MediaPlayer.Event.Playing -> {
                FileLogger.i(TAG, "Playing at ${mediaPlayer?.time ?: 0}ms")
                isLoadingNewStream = false
                // Defer resume seek until VLC is actually playing and ready to accept seeks.
                pendingResumeTime?.let { resumeAt ->
                    pendingResumeTime = null
                    runOnUiThread { mediaPlayer?.time = resumeAt }
                }
                runOnUiThread { applyPreferredLanguages() }
            }

            MediaPlayer.Event.Paused ->
                FileLogger.i(TAG, "Paused at ${mediaPlayer?.time ?: 0}ms")

            MediaPlayer.Event.Stopped ->
                FileLogger.i(TAG, "Stopped")

            MediaPlayer.Event.EncounteredError -> {
                FileLogger.e(TAG, "VLC encountered an error (url=${originalM3u8Url ?: "(unknown)"})")
                isLoadingNewStream = false
                handleVlcError()
            }

            MediaPlayer.Event.EndReached -> {
                FileLogger.i(TAG, "End reached — loop=$isLooping playlist size=${playlistItems.size}, index=$playlistIndex")
                if (isLooping) {
                    runOnUiThread {
                        mediaPlayer?.time = 0
                        mediaPlayer?.play()
                    }
                } else {
                    runOnUiThread {
                        if (isLoadingNewStream) {
                            FileLogger.i(TAG, "Ignoring EndReached because new stream is loading")
                            return@runOnUiThread
                        }

                        if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                            playNextInPlaylist()
                        } else {
                            // Check if series navigator has a next episode before finishing
                            val nav = seriesNavigator
                            if (nav != null && nav.hasNext()) {
                                playNextInPlaylist()
                            } else {
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }
    private var currentSubtitleUrl: String? = null
    private var currentPlaybackSpeed: Float = 1.0f
    private var currentVideoScalingMode: String = "Fit"

    // Settings state
    private var originalM3u8Url: String? = null
    private var currentHeaders: Map<String, String>? = null

    // Playlist state
    private var playlistItems: MutableList<com.playbridge.protocol.PlayPayload> = mutableListOf()
    private var playlistIndex: Int = 0

    // Preferred language selections — persisted across stream switches and app restarts
    private var preferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage: String? = null

    // Loop state
    private var isLooping = false
    private var isLoadingNewStream = false

    // Settings dialog state
    private var activeDialog: android.app.Dialog? = null
    private var surfaceLayoutListener: android.view.View.OnLayoutChangeListener? = null

    private lateinit var progressManager: ProgressManager
    private lateinit var historyStore: HistoryStore
    private var receiverRegistered = false

    // Settings state
    private var subtitleUrls: List<String> = emptyList()

    // Seek buffering
    private var pendingSeekTime: Long? = null
    private var pendingResumeTime: Long? = null
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var lastSeekCommitTime = 0L

    private val performSeekRunnable = Runnable {
        pendingSeekTime?.let { targetTime ->
            mediaPlayer?.time = targetTime
            pendingSeekTime = null
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

    override fun stopPlayback() {
        FileLogger.i(TAG, "stopPlayback() — clearing surface for transition")
        mediaPlayer?.let { player ->
            player.stop()
            player.vlcVout.detachViews()
        }
        runOnUiThread {
            if (::surfaceView.isInitialized) {
                surfaceView.visibility = android.view.View.INVISIBLE
            }
        }
    }

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
                    "loop_on"  -> setLooping(true)
                    "loop_off" -> setLooping(false)
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
            if (com.playbridge.player.BuildConfig.DEBUG) add("-vvv") // Verbose logging in debug only

            // Network stability
            add("--http-reconnect")
            add("--network-caching=5000") // Increased for stability
            add("--clock-jitter=500")
            add("--clock-synchro=0")
        }
        try {
            // Use applicationContext to avoid leaking activity and for better native library stability
            libVLC = LibVLC(applicationContext, args)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to initialize LibVLC", e)
            Toast.makeText(this, "Failed to start VLC player", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

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
            loopButton = findViewById(R.id.btn_loop),
            onShowSettings = { showSettingsDialog() },
            onShowPlaylist = { showPlaylistPicker() },
            onError = { handleVlcError() },
            onSeekForwardRequested = { handleSeek(1) },
            onSeekBackwardRequested = { handleSeek(-1) },
            onPrevious = { playPreviousInPlaylist() },
            onNext = { playNextInPlaylist() },
            onToggleLoop = { setLooping(!isLooping) }
        )

        setupMediaPlayer()

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
        stopPlayback()
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

        // Restore saved language preferences from history intent so they survive app restarts.
        intent.getStringExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG)?.let {
            preferredAudioLanguage = it
            FileLogger.i(TAG, "Restored preferred audio language: $it")
        }
        intent.getStringExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG)?.let {
            preferredSubtitleLanguage = it
            FileLogger.i(TAG, "Restored preferred subtitle language: $it")
        }
        setupSeriesNavigator(intent)

        if (isPlaylist && inMemoryPlaylist != null && inMemoryPlaylist.isNotEmpty()) {
            playlistItems = inMemoryPlaylist.toMutableList()
            playlistIndex = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0)
        } else {
            playlistItems = mutableListOf()
        }

        // Show playlist button when a playlist is active OR series navigator has list mode
        controlsManager.setPlaylistVisible(playlistItems.isNotEmpty() || (seriesNavigator?.episodeList?.isNotEmpty() ?: false))

        // Show prev/next buttons without the playlist button ONLY if series navigation is in optimistic mode (no list)
        if (seriesNavigator != null && seriesNavigator?.episodeList.isNullOrEmpty() && playlistItems.isEmpty()) {
            // VlcControlsManager doesn't have setNavigationVisible yet, but we can ensure they are showing
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
        isLoadingNewStream = true
        if (playlistItems.isEmpty()) {
            // No playlist queue — try series navigator if available
            val nav = seriesNavigator
            if (nav != null && nav.hasNext()) {
                lifecycleScope.launch {
                    FileLogger.i(TAG, "No playlist — trying SeriesNavigator next episode")

                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolveNext()
                    if (stream != null) {
                        val epTitle = "S${nav.currentSeason}E${nav.currentEpisode}"
                        android.widget.Toast.makeText(this@VlcPlayerActivity, "Next: $epTitle", android.widget.Toast.LENGTH_SHORT).show()
                        playVideo(url = stream.url, headers = null)
                        controlsManager.hideControls()
                    } else {
                        controlsManager.hideBuffering()
                        FileLogger.i(TAG, "SeriesNavigator returned null — series complete")
                        android.widget.Toast.makeText(this@VlcPlayerActivity, "No more episodes found", android.widget.Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } else {
                FileLogger.i(TAG, "No playlist and no series nav — finishing")
                finish()
            }
            return
        }

        if (playlistIndex >= playlistItems.size - 1) {
            FileLogger.i(TAG, "Playlist complete ($playlistIndex/${playlistItems.size}) — finishing")
            finish()
            return
        }

        playlistIndex++
        val nextItem = playlistItems[playlistIndex]
        playPlaylistItem(nextItem)
        broadcastPlaylistStatus()
    }

    private fun playPreviousInPlaylist() {
        isLoadingNewStream = true
        if (playlistItems.isEmpty()) {
            val nav = seriesNavigator
            if (nav != null && nav.hasPrev()) {
                lifecycleScope.launch {
                    FileLogger.i(TAG, "SeriesNavigator: resolving previous episode")

                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolvePrev()
                    if (stream != null) {
                        val epTitle = "S${nav.currentSeason}E${nav.currentEpisode}"
                        android.widget.Toast.makeText(this@VlcPlayerActivity, epTitle, android.widget.Toast.LENGTH_SHORT).show()
                        playVideo(url = stream.url, headers = null)
                        controlsManager.hideControls()
                    } else {
                        controlsManager.hideBuffering()
                        android.widget.Toast.makeText(this@VlcPlayerActivity, "Could not resolve previous episode", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                android.widget.Toast.makeText(this, "Already on first episode", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (playlistIndex <= 0) {
            android.widget.Toast.makeText(this, "Already on first episode", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

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

        val player = mediaPlayer ?: return
        val wasPlaying = player.isPlaying
        if (wasPlaying) player.pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            com.playbridge.player.ui.theme.PlayBridgeTVTheme {
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

    private fun playSeriesEpisodeAtIndex(index: Int) {
        val nav = seriesNavigator ?: return
        lifecycleScope.launch {
            isLoadingNewStream = true
            FileLogger.i(TAG, "SeriesNavigator: resolving episode at index $index")

            // Save progress for the current episode before switching
            syncSelectionsToProgressManager()
            progressManager.saveProgress()

            stopPlayback()
            controlsManager.showBuffering()
            val stream = nav.resolveAndAdvanceToIndex(index)
            if (stream != null) {
                val epTitle = "S${nav.currentSeason}E${nav.currentEpisode}"
                android.widget.Toast.makeText(this@VlcPlayerActivity, epTitle, android.widget.Toast.LENGTH_SHORT).show()

                // Update UI title so playVideo() logs and uses the correct metadata
                controlsManager.setTitle(epTitle)

                playVideo(url = stream.url, headers = null)
                controlsManager.hideControls()
            } else {
                isLoadingNewStream = false
                controlsManager.hideBuffering()
                android.widget.Toast.makeText(this@VlcPlayerActivity, "Could not resolve episode", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playPlaylistItem(item: com.playbridge.protocol.PlayPayload) {
        isLoadingNewStream = true
        syncSelectionsToProgressManager()
        progressManager.saveProgress()

        stopPlayback()
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

    /**
     * Enable or disable single-video loop mode for VLC.
     * Looping is implemented by seeking to 0 and replaying on EndReached.
     */
    private fun setLooping(enabled: Boolean) {
        isLooping = enabled
        controlsManager.updateLoopIcon(enabled)
        FileLogger.i(TAG, "Loop mode: $enabled")
    }

    // ── Video scaling helpers ─────────────────────────────────────────────────

    /**
     * Map VLC scaling mode string → Int for [ProgressManager] persistence.
     *
     * Media3 RESIZE_MODE constants are re-used as numeric keys since ProgressManager
     * stores a single Int.  "Center" (original size) uses RESIZE_MODE_ZOOM (4) so it
     * round-trips distinctly from "Fit" (0).  "16:9" / "4:3" use the fixed-dimension
     * constants (1 / 2) which ExoPlayer never writes via this path.
     */
    private fun vlcScalingModeToInt(mode: String): Int? = when (mode) {
        "Fit"    -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT          // 0
        "16:9"   -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH  // 1
        "4:3"    -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT // 2
        "Fill"   -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL         // 3
        "Center" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM         // 4
        else     -> null
    }

    /** Reverse of [vlcScalingModeToInt]. */
    private fun vlcScalingModeFromInt(value: Int): String = when (value) {
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH  -> "16:9"
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "4:3"
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL         -> "Fill"
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM         -> "Center"
        else                                                                -> "Fit"
    }

    /**
     * Apply [mode] to the live [mediaPlayer].  Single source of truth used by both
     * the settings dialog callback and history restore so they stay in sync.
     */
    private fun applyVlcScalingMode(mode: String) {
        val player = mediaPlayer ?: return
        when (mode) {
            "Fit" -> { player.scale = 0f; player.aspectRatio = null }
            "Fill" -> { player.scale = 0f; player.aspectRatio = "16:9" }
            "16:9" -> { player.scale = 0f; player.aspectRatio = "16:9" }
            "4:3"  -> { player.scale = 0f; player.aspectRatio = "4:3" }
            // Center: scale=1f renders at the video's native pixel size, no stretching.
            "Center" -> { player.scale = 1f; player.aspectRatio = null }
        }
    }

    /**
     * Auto-select tracks matching [preferredAudioLanguage] / [preferredSubtitleLanguage].
     *
     * Called once per stream on the first Playing event. If a track with the preferred language
     * exists and is not already selected, it is selected silently. If no match is found the
     * current VLC default is left unchanged — we never force an unexpected track.
     */
    private fun applyPreferredLanguages() {
        val player = mediaPlayer ?: return

        preferredAudioLanguage?.let { lang ->
            val tracks = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio) ?: return@let
            val current = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio)
            if (current?.language == lang) return@let // already correct
            val match = tracks.firstOrNull { it.language == lang }
            if (match != null) {
                FileLogger.i(TAG, "Auto-selecting preferred audio language '$lang' (track ${match.id})")
                player.selectTrack(match.id)
            }
        }

        preferredSubtitleLanguage?.let { lang ->
            val tracks = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text) ?: return@let
            val current = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text)
            if (current?.language == lang) return@let
            val match = tracks.firstOrNull { it.language == lang }
            if (match != null) {
                FileLogger.i(TAG, "Auto-selecting preferred subtitle language '$lang' (track ${match.id})")
                player.selectTrack(match.id)
            }
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

    private var playJob: kotlinx.coroutines.Job? = null

    private fun playVideo(url: String, headers: Map<String, String>?, resumeTime: Long? = null, startPaused: Boolean = false) {
        val title = controlsManager.getTitle()
        FileLogger.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        FileLogger.i(TAG, "URL: $url")
        FileLogger.i(TAG, "Title: $title")
        FileLogger.i(TAG, "Headers: $headers")
        FileLogger.i(TAG, "===========================================")

        playJob?.cancel()
        playJob = lifecycleScope.launch {
            val urlWithoutQuery = url.substringBefore("?")
            if (urlWithoutQuery.endsWith(".m3u")) {
                try {
                    val parsedPlaylist = M3uParser.fetchAndParseM3u(url, headers)
                    if (parsedPlaylist != null && parsedPlaylist.isNotEmpty()) {
                        playlistItems = parsedPlaylist.toMutableList()
                        com.playbridge.player.player.PlaylistStore.currentPlaylist = parsedPlaylist
                        playlistIndex = 0
                        runOnUiThread { controlsManager.setPlaylistVisible(true) }

                        val firstItem = parsedPlaylist[0]
                        runOnUiThread { controlsManager.setTitle(firstItem.title ?: title) }
                        originalM3u8Url = firstItem.url
                        currentHeaders = firstItem.headers
                        subtitleUrls = firstItem.subtitles ?: emptyList()
                        currentSubtitleUrl = null

                        if (firstItem.url.contains(".m3u8", ignoreCase = true)) {
                            M3uParser.parseMasterPlaylist(firstItem.url, firstItem.headers)
                        }
                        // Continue playback with the first item from the playlist
                        playVideoInternal(firstItem.url, firstItem.headers, resumeTime, startPaused)
                        return@launch
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Error parsing M3U", e)
                }
            }
            playVideoInternal(url, headers, resumeTime, startPaused)
        }
    }

    private suspend fun playVideoInternal(url: String, headers: Map<String, String>?, resumeTime: Long? = null, startPaused: Boolean = false) {
        val title = controlsManager.getTitle()

        // Handle cancellation gracefully during history restoration
        val historyItem = try {
            progressManager.restoreProgress(url)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w(TAG, "Failed to restore history for $url: ${e.message}")
            null
        }

        if (playlistItems.isEmpty() && historyItem?.playlistJson != null) {
            try {
                val decoded = com.playbridge.protocol.protocolJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(com.playbridge.protocol.PlayPayload.serializer()),
                    historyItem.playlistJson!!
                )
                if (decoded.isNotEmpty()) {
                    playlistItems = decoded.toMutableList()
                    playlistIndex = historyItem.playlistIndex
                    runOnUiThread { controlsManager.setPlaylistVisible(true) }
                    FileLogger.i(TAG, "Restored playlist from history: ${playlistItems.size} items at index $playlistIndex")
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to restore playlist from history: ${e.message}")
            }
        }

        // Build playlist JSON for history persistence (computed after fallback restore)
        val plistJson = if (playlistItems.isNotEmpty()) {
            try {
                com.playbridge.protocol.protocolJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(com.playbridge.protocol.PlayPayload.serializer()),
                    playlistItems
                )
            } catch (e: Exception) {
                null
            }
        } else null

        progressManager.setCurrentMedia(
            url = url,
            title = title,
            contentType = null,
            headers = headers,
            playlistJson = plistJson,
            playlistIndex = playlistIndex,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            externalSubtitleUrl = currentSubtitleUrl,
            playbackSpeed = currentPlaybackSpeed,
            videoScalingMode = vlcScalingModeToInt(currentVideoScalingMode)
        )

        val finalResumeTime = resumeTime ?: historyItem?.position

        val media = Media(libVLC, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, true)

            val extraHeaders = mutableListOf<String>()
            headers?.forEach { (key, value) ->
                when (key.lowercase()) {
                    "user-agent" -> addOption(":http-user-agent=$value")
                    "referer" -> addOption(":http-referrer=$value")
                    "cookie" -> addOption(":http-cookies=$value")
                    else -> extraHeaders.add("$key: $value")
                }
            }
            if (extraHeaders.isNotEmpty()) {
                // VLC expects headers separated by \r\n with no trailing separator.
                addOption(":http-extra-headers=${extraHeaders.joinToString("\r\n")}")
            }
        }

        // Recreate MediaPlayer to ensure a clean native state and avoid resource race conditions
        setupMediaPlayer()

        // Brief delay to allow native resources (MediaCodec) to fully return to the system pool
        kotlinx.coroutines.delay(300)

        mediaPlayer?.let { player ->
            player.media = media
            media.release()

            // Restore settings from history if present
            historyItem?.playbackSpeed?.let {
                currentPlaybackSpeed = it
                runOnUiThread { player.rate = it }
            }
            historyItem?.videoScalingMode?.let {
                currentVideoScalingMode = vlcScalingModeFromInt(it)
                runOnUiThread { applyVlcScalingMode(currentVideoScalingMode) }
            }
            historyItem?.externalSubtitleUrl?.let {
                currentSubtitleUrl = it
                runOnUiThread {
                    player.addSlave(
                        org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
                        android.net.Uri.parse(it),
                        true
                    )
                }
            }

            // Stage the resume seek so the Playing event handler applies it once VLC
            // has buffered enough to reliably accept the command.
            pendingResumeTime = if (finalResumeTime != null && finalResumeTime > 0) finalResumeTime else null

            if (startPaused) {
                runOnUiThread { player.play(); player.pause() }
            } else {
                runOnUiThread { player.play() }
            }
        }
    }

    private fun syncSelectionsToProgressManager() {
        progressManager.updateSelections(
            externalSubtitleUrl = currentSubtitleUrl,
            playbackSpeed = currentPlaybackSpeed,
            videoScalingMode = vlcScalingModeToInt(currentVideoScalingMode)
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
                        // Persist the selected track's language so it auto-applies on next play.
                        preferredAudioLanguage = audioTracks.firstOrNull { it.id == id }?.language
                            ?.also { FileLogger.i(TAG, "Saved preferred audio language: $it") }
                    },
                    onSubtitleTrackSelected = { id ->
                        if (id == null) player.unselectTrackType(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text) else player.selectTrack(id)
                        liveCurrentSubtitleTrack = id
                        if (id != null) {
                            currentSubtitleUrl = null
                            liveCurrentSubtitleUrl = null
                        }
                        // Persist the selected subtitle track's language.
                        preferredSubtitleLanguage = if (id == null) null
                        else subtitleTracks.firstOrNull { it.id == id }?.language
                            ?.also { FileLogger.i(TAG, "Saved preferred subtitle language: $it") }
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
                        applyVlcScalingMode(mode)
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
        if (::progressManager.isInitialized) {
            syncSelectionsToProgressManager()
            progressManager.saveProgress()
        }
        super.onDestroy()
        seekHandler.removeCallbacks(performSeekRunnable)
        if (receiverRegistered) {
            unregisterReceiver(remoteReceiver)
            receiverRegistered = false
        }

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
