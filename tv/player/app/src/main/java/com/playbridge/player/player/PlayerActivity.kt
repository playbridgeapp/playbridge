package com.playbridge.player.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.playbridge.player.server.ServerService
import com.playbridge.player.logging.FileLogger
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.RequiresApi

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.playbridge.player.ui.player.PlayerControlsViewModel

abstract class PlayerActivity : ComponentActivity() {

    protected var launchJob: Job? = null

    // Common abstract properties and functions for player controls
    abstract fun play()
    abstract fun pause()
    abstract fun isPlaying(): Boolean
    abstract fun getMediaDuration(): Long
    abstract fun getCurrentPosition(): Long
    abstract fun seekTo(position: Long)
    abstract fun getVideoSurfaceView(): android.view.SurfaceView?
    /** Stop current playback and clear the video surface (make it black) for a smooth transition. */
    abstract fun stopPlayback()
    protected open fun getPlayerProgressManager(): ProgressManager? = null
    protected open fun showVideoFilterDialog() {}

    // Shared playback configuration
    var defaultVideoQuality: String? = null      // e.g. "720p", "1080p", "2160p"
    var maxBitrateCapMbps: Double? = null         // explicit bitrate cap from phone settings (Mbps)
    var externalSubtitles: List<String>? = null   // list of external subtitle URLs
    var isFrameRateMatchingEnabled: Boolean = false
    var isLoudnessEnhancerEnabled: Boolean = false

    protected fun setupPlaybackExtras(intent: Intent?) {
        defaultVideoQuality = intent?.getStringExtra("default_video_quality")
        maxBitrateCapMbps = if (intent?.hasExtra(ServerService.EXTRA_MAX_BITRATE_CAP_MBPS) == true)
            intent.getDoubleExtra(ServerService.EXTRA_MAX_BITRATE_CAP_MBPS, 0.0).takeIf { it > 0.0 }
        else null
        
        externalSubtitles = intent?.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Handle window insets
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ensure only one player activity is alive at a time so that pressing back always
        // returns to the home/library screen rather than a previously-used player.
        current?.get()?.let { prev ->
            if (prev !== this && !prev.isFinishing) {
                prev.finish()
            }
        }
        current = java.lang.ref.WeakReference(this)
        // Mark the server context as "player" so the request_pairing guard works regardless
        // of whether this activity was launched by handleCommand() (phone cast) or directly
        // from the TV's history/favourites screen (which bypasses handleCommand entirely).
        ServerService.notifyContextPlayer()

        // Load refresh rate matching setting
        val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        isFrameRateMatchingEnabled = prefs.getBoolean("frame_rate_matching", false)
        isLoudnessEnhancerEnabled = prefs.getBoolean("loudness_enhancer", false)
        FileLogger.i("PlayerActivity", "Frame rate matching enabled: $isFrameRateMatchingEnabled, Loudness enhancer: $isLoudnessEnhancerEnabled")
    }

    private var lastMatchedFps: Float = 0f

    /**
     * Updates the display refresh rate to match the intended video frame rate.
     * Uses Android 11+ Surface.setFrameRate API.
     */
    protected fun updateRefreshRate(fps: Float) {
        if (!isFrameRateMatchingEnabled || fps <= 0f) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return

        // Skip if we already matched this exact FPS recently to avoid redundant handshakes
        if (Math.abs(fps - lastMatchedFps) < 0.01f) return

        val surfaceView = getVideoSurfaceView() ?: return

        FileLogger.i("PlayerActivity", "Requesting refresh rate matching: ${fps}fps")
        try {
            surfaceView.holder.surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            lastMatchedFps = fps
        } catch (e: Exception) {
            FileLogger.e("PlayerActivity", "Failed to set frame rate: ${e.message}")
        }
    }

    /**
     * Toggles the audio loudness enhancer (Night Mode) for the current player engine.
     */
    protected fun applyLoudnessEnhancer(enabled: Boolean, adapter: PlayerEngineAdapter?) {
        FileLogger.i("PlayerActivity", "Applying loudness enhancer: $enabled")
        adapter?.setLoudnessEnhancer(enabled)
    }

    override fun onDestroy() {
        if (current?.get() === this) {
            current = null
            // Only reset activeContext when THIS is genuinely the last player being torn down —
            // not when it's being replaced by a new PlayerActivity (which calls prev.finish()
            // from its own onCreate, setting `current` to itself before our onDestroy runs).
            // If we reset here while a new player is already in `current`, activeContext would
            // become "idle" during the new session, letting request_pairing open PairingScreen
            // on top of the new video and killing it.
            ServerService.notifyContextIdle()
        }
        super.onDestroy()
    }


    /**
     * Buffer caps computed from the device's current available memory.
     *
     * ExoPlayer strategy: time-based primary constraint (prioritizeTimeOverSizeThresholds=true),
     * with [targetBytes] as a hard ceiling for ultra-high-bitrate content only (e.g. 4K REMUX
     * at 100 Mbps).  Back-buffer is intentionally omitted: setBackBuffer() allocates from the
     * same DefaultAllocator pool as the forward buffer, so a 30 s back-buffer at 25 Mbps eats
     * ~94 MB of a 128 MB cap — starving the forward buffer and causing the oscillating
     * "buffer reset" symptom observed on Hisense TVs.
     *
     * MPV strategy: demuxer-max-bytes caps the in-RAM ring buffer; demuxer-max-back-bytes
     * is managed independently by libmpv outside Android's allocator, so it does not compete.
     *
     * Tiers (based on availMem at player launch):
     *
     *   ≥ 1 500 MB  →  90 s / 800 MB safety cap  (emulator / high-end)
     *   ≥   800 MB  →  60 s / 400 MB safety cap
     *   ≥   400 MB  →  45 s / 200 MB safety cap
     *      < 400 MB  →  30 s / 100 MB safety cap  (Hisense / very constrained)
     */
    data class BufferConfig(
        /** ExoPlayer DefaultLoadControl maxBufferMs (primary time-based cap) */
        val maxBufferMs: Int,
        /** ExoPlayer DefaultLoadControl byte ceiling — guards against very high-bitrate content */
        val targetBytes: Int,
        /** MPV demuxer-max-bytes option string, e.g. "128MiB" */
        val demuxerMaxBytes: String,
        /** MPV demuxer-max-back-bytes option string */
        val demuxerMaxBackBytes: String,
        /** Whether ExoPlayer should prioritize time goals over size thresholds. */
        val prioritizeTime: Boolean,
    )

    protected fun formatBitrate(bps: Long): String {
        return when {
            bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0)
            bps >= 1_000 -> String.format("%d Kbps", bps / 1_000)
            else -> "$bps bps"
        }
    }

    fun computeBufferConfig(): BufferConfig {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        return when {
            availMb >= 1_500 -> BufferConfig( 90_000, 800 * 1024 * 1024, "256MiB", "64MiB", true)
            availMb >=   800 -> BufferConfig( 60_000, 400 * 1024 * 1024, "192MiB", "48MiB", true)
            availMb >=   400 -> BufferConfig( 45_000, 200 * 1024 * 1024, "128MiB", "32MiB", true)
            else             -> BufferConfig( 30_000, 100 * 1024 * 1024,  "64MiB", "16MiB", false)
        }
    }

    protected fun showSwitchPlayerDialog(currentPlayerId: String) {
        val wasPlaying = isPlaying()
        if (wasPlaying) pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            androidx.tv.material3.MaterialTheme {
                SwitchPlayerDialog(
                    currentPlayer = currentPlayerId,
                    onPlayerSelected = { selectedPlayerId ->
                        dialog.dismiss()
                        switchPlayer(selectedPlayerId)
                    },
                    onDismiss = {
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.setOnDismissListener {
            if (wasPlaying) play()
        }
        dialog.show()
    }

    protected fun handlePrePlayMetadata(intent: Intent?, controlsViewModel: PlayerControlsViewModel) {
        if (intent?.getBooleanExtra(ServerService.EXTRA_SKIP_PREPLAY, false) == true) {
            FileLogger.d("PlayerActivity", "Skipping pre-play metadata per EXTRA_SKIP_PREPLAY")
            controlsViewModel.setPrePlay(null)
            return
        }
        val visualMetadataJson = intent?.getStringExtra(ServerService.EXTRA_VISUAL_METADATA)
        if (visualMetadataJson != null) {
            try {
                val metadata = com.playbridge.shared.protocol.protocolJson.decodeFromString(
                    com.playbridge.shared.protocol.VisualMetadata.serializer(),
                    visualMetadataJson
                )
                FileLogger.i("PlayerActivity", "Received pre-play metadata: ${metadata.title}")
                controlsViewModel.setPrePlay(metadata)
                controlsViewModel.setPrePlayLaunching(true)
                controlsViewModel.setPrePlayCountdown(-1) // -1 signifies "Connecting..."
            } catch (e: Exception) {
                FileLogger.e("PlayerActivity", "Failed to parse visual metadata", e)
                controlsViewModel.setPrePlay(null)
            }
        } else {
            controlsViewModel.setPrePlay(null)
        }
    }

    protected fun triggerPrePlayCountdown(controlsViewModel: PlayerControlsViewModel, onFinished: () -> Unit) {
        if (launchJob?.isActive == true) return // Already counting down
        
        startPrePlayCountdown(controlsViewModel, onFinished)
    }

    protected fun startPrePlayCountdown(controlsViewModel: PlayerControlsViewModel, onFinished: () -> Unit) {
        launchJob?.cancel()
        launchJob = lifecycleScope.launch {
            controlsViewModel.setPrePlayLaunching(true)
            for (i in 5 downTo 1) {
                controlsViewModel.setPrePlayCountdown(i)
                delay(1000)
            }
            controlsViewModel.setPrePlayCountdown(0)
            delay(500)
            
            // This will clear the overlay AND trigger play() in the activity
            onFinished()
        }
    }

    protected fun switchPlayer(newMode: String) {
        val currentPosition = getCurrentPosition()
        val pm = getPlayerProgressManager()
        val url = pm?.url ?: intent.getStringExtra(ServerService.EXTRA_URL)
        val title = pm?.title ?: intent.getStringExtra(ServerService.EXTRA_TITLE)

        if (url == null) {
            FileLogger.e("PlayerActivity", "Cannot switch player without a valid URL")
            return
        }

        if (newMode == "external" || newMode == "external_mpv") {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (title != null) {
                    putExtra(Intent.EXTRA_TITLE, title)
                    putExtra("title", title)
                }
                @Suppress("UNCHECKED_CAST")
                val headers = pm?.headers ?: intent.getSerializableExtra(ServerService.EXTRA_HEADERS) as? java.util.HashMap<String, String>
                if (headers != null) {
                    val bundle = android.os.Bundle()
                    headers.forEach { (key, value) -> bundle.putString(key, value) }
                    putExtra(android.provider.Browser.EXTRA_HEADERS, bundle)
                    val headersArray = headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
                    putExtra("headers", headersArray)
                }
            }
            val chooserIntent = Intent.createChooser(viewIntent, "Open video with...")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(chooserIntent)

            // Wait briefly before finishing to allow external player to launch smoothly
            window.decorView.postDelayed({ finish() }, 1000)
            return
        }

        val activityClass = when (newMode) {
            "internal_vlc" -> com.playbridge.player.player.VlcPlayerActivity::class.java
            "internal_mpv" -> com.playbridge.player.player.MpvPlayerActivity::class.java
            else -> com.playbridge.player.player.ExoPlayerActivity::class.java
        }

        // Just launch a new instance of the selected player activity with the same intent extras,
        // but tell it to start from currentPosition.
        val newIntent = Intent(intent)
        newIntent.setClass(this, activityClass)

        // Remove EXTRA_CONTENT_PAYLOAD so we don't re-resolve streams
        newIntent.removeExtra(ServerService.EXTRA_CONTENT_PAYLOAD)
        newIntent.removeExtra(ServerService.EXTRA_VISUAL_METADATA)
        newIntent.putExtra(ServerService.EXTRA_SKIP_PREPLAY, true)


        newIntent.putExtra(ServerService.EXTRA_URL, url)
        newIntent.putExtra(ServerService.EXTRA_TITLE, title)

        if (pm?.contentType != null) {
            newIntent.putExtra(ServerService.EXTRA_CONTENT_TYPE, pm.contentType)
        }

        @Suppress("UNCHECKED_CAST")
        val headers = pm?.headers ?: intent.getSerializableExtra(ServerService.EXTRA_HEADERS) as? java.util.HashMap<String, String>
        if (headers != null) {
            newIntent.putExtra(ServerService.EXTRA_HEADERS, java.util.HashMap(headers))
        }

        if (pm?.preferredAudioLanguage != null) {
            newIntent.putExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG, pm.preferredAudioLanguage)
        }
        if (pm?.preferredSubtitleLanguage != null) {
            newIntent.putExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG, pm.preferredSubtitleLanguage)
        }
        if (pm?.externalSubtitleUrl != null) {
            newIntent.putExtra(ServerService.EXTRA_EXTERNAL_SUBTITLE_URL, pm.externalSubtitleUrl)
        }

        newIntent.putExtra("extra_start_position", currentPosition)
        newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // Clear the explicit package/component if it was set by the original intent
        newIntent.component = android.content.ComponentName(this, activityClass)

        startActivity(newIntent)
        finish()
    }
    companion object {
        @Volatile
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null
    }
}
