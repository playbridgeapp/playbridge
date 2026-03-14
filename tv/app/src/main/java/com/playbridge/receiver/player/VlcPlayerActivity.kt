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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.playbridge.receiver.ui.theme.PlayBridgeTVTheme
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

class VlcPlayerActivity : PlayerActivity(), IVLCVout.Callback {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var controlsManager: VlcControlsManager

    // Settings state
    private var subtitleUrls: List<String> = emptyList()
    private var currentSubtitleUrl: String? = null
    private var currentPlaybackSpeed: Float = 1.0f
    private var currentVideoScalingMode: String = "Fit"

    // HLS Variant state
    private var hlsVariants: List<HlsVariant> = emptyList()
    private var currentHlsVariantUrl: String? = null
    private var originalM3u8Url: String? = null
    private var currentHeaders: Map<String, String>? = null

    // Seek buffering
    private var pendingSeekTime: Long? = null
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val performSeekRunnable = Runnable {
        pendingSeekTime?.let { targetTime ->
            mediaPlayer?.time = targetTime
            pendingSeekTime = null
            controlsManager.setPendingSeekTime(null)
        }
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // Setup VLC
        val args = ArrayList<String>().apply {
            add("-vvv") // Verbosity
            add("--drop-late-frames")
            add("--skip-frames")
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
            onError = { handleVlcError() },
            onSeekForwardRequested = { handleSeek(1) },
            onSeekBackwardRequested = { handleSeek(-1) }
        )

        controlsManager.attachPlayer()

        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_CONTROL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(remoteReceiver, filter)
        }

        handleIntent(intent)
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

        // Check if it's an m3u8 playlist that we can parse for multiple variants
        if (url.contains(".m3u8", ignoreCase = true)) {
            lifecycleScope.launch {
                val variants = M3uParser.parseMasterPlaylist(url, headers)
                if (variants != null && variants.isNotEmpty()) {
                    hlsVariants = variants
                }
                playVideo(url, headers)
            }
        } else {
            playVideo(url, headers)
        }
    }

    private fun handleVlcError() {
        val currentUrl = intent.getStringExtra(ServerService.EXTRA_URL) ?: ""
        val currentTitle = intent.getStringExtra(ServerService.EXTRA_TITLE)

        runOnUiThread {
            android.widget.Toast.makeText(this, "VLC encountered an error. Trying external player...", android.widget.Toast.LENGTH_SHORT).show()
            launchExternalPlayer(currentUrl, currentTitle)
            finish()
        }
    }

    private fun launchExternalPlayer(url: String, title: String?) {
        try {
            val launchIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                title?.let {
                    putExtra(Intent.EXTRA_TITLE, it)
                    putExtra("title", it)
                }
            }
            val chooser = Intent.createChooser(launchIntent, "Play with...")
            startActivity(chooser)
        } catch (e: Exception) {
            runOnUiThread {
                android.widget.Toast.makeText(this, "Could not find an external player", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playVideo(url: String, headers: Map<String, String>?, resumeTime: Long? = null, startPaused: Boolean = false) {
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

        // VLC needs to be playing to reliably accept seek commands for a new media source
        mediaPlayer?.play()

        if (resumeTime != null && resumeTime > 0) {
            mediaPlayer?.time = resumeTime
        }

        if (startPaused) {
            mediaPlayer?.pause()
        }
    }

    private fun showSettingsDialog() {
        val player = mediaPlayer ?: return
        val wasPlaying = player.isPlaying
        if (wasPlaying) player.pause()

        val videoTracks = player.videoTracks?.toList() ?: emptyList()
        val currentVideoTrack = player.videoTrack
        val isHlsVariantsAvailable = hlsVariants.isNotEmpty()

        val audioTracks = player.audioTracks?.toList() ?: emptyList()
        val currentAudioTrack = player.audioTrack

        val subtitleTracks = player.spuTracks?.toList() ?: emptyList()
        val currentSubtitleTrack = player.spuTrack

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            PlayBridgeTVTheme {
                // Reactive state for UI updates
                var liveCurrentVideoTrack by remember { mutableStateOf(currentVideoTrack) }
                var liveCurrentHlsVariant by remember { mutableStateOf(currentHlsVariantUrl) }
                var liveCurrentAudioTrack by remember { mutableStateOf(currentAudioTrack) }
                var liveCurrentSubtitleTrack by remember { mutableStateOf(currentSubtitleTrack) }
                var liveCurrentSubtitleUrl by remember { mutableStateOf(currentSubtitleUrl) }
                var liveCurrentPlaybackSpeed by remember { mutableFloatStateOf(currentPlaybackSpeed) }
                var liveCurrentVideoScalingMode by remember { mutableStateOf(currentVideoScalingMode) }

                VlcTrackSelectionDialog(
                    videoTracks = videoTracks,
                    currentVideoTrack = liveCurrentVideoTrack,
                    hlsVariants = hlsVariants,
                    currentHlsVariantUrl = liveCurrentHlsVariant,
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
                        player.videoTrack = id
                        liveCurrentVideoTrack = id
                    },
                    onHlsVariantSelected = { url ->
                        val wasHlsAuto = currentHlsVariantUrl == null

                        // Avoid unnecessary restarts
                        if (url != "AUTO" && currentHlsVariantUrl == url) return@VlcTrackSelectionDialog
                        if (url == "AUTO" && wasHlsAuto) return@VlcTrackSelectionDialog

                        currentHlsVariantUrl = if (url == "AUTO") null else url
                        liveCurrentHlsVariant = currentHlsVariantUrl

                        val time = player.time
                        if (url == "AUTO" && originalM3u8Url != null) {
                            playVideo(originalM3u8Url!!, currentHeaders, resumeTime = time, startPaused = !wasPlaying)
                        } else if (originalM3u8Url != null) {
                            lifecycleScope.launch {
                                val filteredMasterUrl = M3uParser.generateFilteredMasterPlaylist(
                                    originalM3u8Url!!,
                                    currentHeaders,
                                    url,
                                    cacheDir
                                )
                                if (filteredMasterUrl != null) {
                                    playVideo(filteredMasterUrl, currentHeaders, resumeTime = time, startPaused = !wasPlaying)
                                } else {
                                    // Fallback to playing the direct variant URL if filtering fails
                                    playVideo(url, currentHeaders, resumeTime = time, startPaused = !wasPlaying)
                                }
                            }
                        }
                    },
                    onAudioTrackSelected = { id ->
                        player.audioTrack = id
                        liveCurrentAudioTrack = id
                    },
                    onSubtitleTrackSelected = { id ->
                        player.spuTrack = id
                        liveCurrentSubtitleTrack = id
                        if (id != -1) {
                            currentSubtitleUrl = null
                            liveCurrentSubtitleUrl = null
                        }
                    },
                    onExternalSubtitleSelected = { url ->
                        currentSubtitleUrl = url
                        liveCurrentSubtitleUrl = url
                        if (url != null) {
                            player.spuTrack = -1 // Disable embedded
                            liveCurrentSubtitleTrack = -1
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
            if (wasPlaying) player.play()
            controlsManager.showControls()
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(remoteReceiver)
        controlsManager.detachPlayer()
        mediaPlayer?.vlcVout?.apply {
            removeCallback(this@VlcPlayerActivity)
            detachViews()
        }
        mediaPlayer?.release()
        libVLC?.release()
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


    override fun onResume() {
        super.onResume()
        surfaceView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0) {
                mediaPlayer?.vlcVout?.setWindowSize(width, height)
            }
        }
    }
}