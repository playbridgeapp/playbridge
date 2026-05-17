package com.playbridge.player.preplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.player.ExoPlayerActivity
import com.playbridge.player.player.MpvPlayerActivity
import com.playbridge.player.player.VlcPlayerActivity
import com.playbridge.player.server.ServerService
import playbridge.PlayPayload
import playbridge.PlaylistPayload
import playbridge.VisualMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PrePlayActivity"

class PrePlayActivity : ComponentActivity() {

    private var launchJob: Job? = null
    private var visualMetadata by mutableStateOf<VisualMetadata?>(null)
    private var streamUrl by mutableStateOf<String?>(null)
    private var contentType by mutableStateOf<String?>(null)
    private var playerMode by mutableStateOf<String?>(null)
    
    // Playback preferences for intent transport
    private var preferredAudioLanguage by mutableStateOf<String?>(null)
    private var preferredSubtitleLanguage by mutableStateOf<String?>(null)
    private var defaultVideoQuality by mutableStateOf<String?>(null)
    private var maxBitrateCapMbps by mutableStateOf<Double?>(null)

    private var isLaunching by mutableStateOf(false)
    private var launchCountdown by mutableIntStateOf(0)

    private val remoteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ServerService.ACTION_REMOTE) {
                val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                handleRemoteKey(key)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            val meta = visualMetadata
            if (meta != null) {
                PrePlayScreen(
                    metadata = meta,
                    isLaunching = isLaunching,
                    launchCountdown = launchCountdown,
                    onStartNow = {
                        launchJob?.cancel()
                        startPlayback()
                    },
                    onBack = {
                        launchJob?.cancel()
                        ServerService.notifyContextIdle()
                        finish()
                    }
                )
            }
        }
        
        val filter = android.content.IntentFilter(ServerService.ACTION_REMOTE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(remoteReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val payloadBytes = intent.getByteArrayExtra(ServerService.EXTRA_CONTENT_PAYLOAD)
        val isPlaylist = intent.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false)

        if (payloadBytes == null) {
            if (visualMetadata == null) {
                Log.e(TAG, "No payload provided")
                finish()
            }
            return
        }

        try {
            if (isPlaylist) {
                val playlist = PlaylistPayload.ADAPTER.decode(payloadBytes)
                val firstItem = playlist.items.getOrNull(playlist.start_index) ?: playlist.items.firstOrNull()
                visualMetadata = playlist.visual_metadata ?: firstItem?.visual_metadata
                streamUrl = firstItem?.url
                contentType = firstItem?.content_type
                playerMode = firstItem?.player_mode
                preferredAudioLanguage = firstItem?.preferred_audio_language
                preferredSubtitleLanguage = firstItem?.preferred_subtitle_language
                defaultVideoQuality = firstItem?.default_video_quality
                maxBitrateCapMbps = firstItem?.max_bitrate_cap_mbps
            } else {
                val play = PlayPayload.ADAPTER.decode(payloadBytes)
                visualMetadata = play.visual_metadata
                streamUrl = play.url
                contentType = play.content_type
                playerMode = play.player_mode
                preferredAudioLanguage = play.preferred_audio_language
                preferredSubtitleLanguage = play.preferred_subtitle_language
                defaultVideoQuality = play.default_video_quality
                maxBitrateCapMbps = play.max_bitrate_cap_mbps
            }

            FileLogger.i(TAG, "New intent received for: ${visualMetadata?.title}")
            isLaunching = false
            startCountdown()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payload", e)
            if (visualMetadata == null) finish()
        }
    }

    private fun startCountdown() {
        if (streamUrl == null) {
            FileLogger.w(TAG, "Cannot start countdown: No stream URL")
            return
        }
        
        launchJob?.cancel()
        launchJob = lifecycleScope.launch {
            isLaunching = true
            for (i in 5 downTo 1) {
                launchCountdown = i
                delay(1000)
            }
            startPlayback()
        }
    }

    private fun startPlayback() {
        val url = streamUrl ?: return
        val meta = visualMetadata ?: return
        if (isFinishing) return
        isLaunching = true

        FileLogger.i(TAG, "Launching player for: ${meta.title}")

        val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val tvPref = if (prefs.contains("player_mode")) {
            prefs.getString("player_mode", "phone") ?: "phone"
        } else {
            if (prefs.getBoolean("use_external_player", false)) "external" else "phone"
        }

        val finalMode = if (tvPref == "phone") {
            if (playerMode != null && playerMode != "tv") {
                playerMode!!
            } else {
                "internal"
            }
        } else {
            tvPref
        }

        if (finalMode == "external" || finalMode == "external_mpv") {
            val intent = if (finalMode == "external_mpv") {
                 Intent(Intent.ACTION_VIEW).apply {
                    setClassName("is.xyz.mpv", "is.xyz.mpv.MPVActivity")
                    data = android.net.Uri.parse(url)
                    putExtra(Intent.EXTRA_TITLE, meta.title)
                    putExtra("title", meta.title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(android.net.Uri.parse(url), "video/*")
                    putExtra(Intent.EXTRA_TITLE, meta.title)
                    putExtra("title", meta.title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }
            startActivity(intent)
            finish()
            return
        }

        val activityClass = when (finalMode) {
            "internal_vlc" -> VlcPlayerActivity::class.java
            "internal_mpv" -> MpvPlayerActivity::class.java
            else           -> ExoPlayerActivity::class.java
        }

        val intent = Intent(this, activityClass).apply {
            putExtra(ServerService.EXTRA_URL, url)
            val fullTitle = if (contentType == "series" && meta.season != null && meta.episode != null) {
                "${meta.title} S${meta.season}E${meta.episode}${if (meta.episode_title != null) " - ${meta.episode_title}" else ""}"
            } else {
                meta.title
            }
            putExtra(ServerService.EXTRA_TITLE, fullTitle)
            putExtra(ServerService.EXTRA_CONTENT_TYPE, contentType)
            putExtra(ServerService.EXTRA_DETECTED_BY, "library")

            preferredAudioLanguage?.let { putExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG, it) }
            preferredSubtitleLanguage?.let { putExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG, it) }
            defaultVideoQuality?.let { putExtra("default_video_quality", it) }
            maxBitrateCapMbps?.let { putExtra(ServerService.EXTRA_MAX_BITRATE_CAP_MBPS, it) }

            val isPlaylist = this@PrePlayActivity.intent.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false)
            if (isPlaylist) {
                putExtra(ServerService.EXTRA_IS_PLAYLIST, true)
                putExtra(ServerService.EXTRA_PLAYLIST_INDEX, this@PrePlayActivity.intent.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0))
            }

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        unregisterReceiver(remoteReceiver)
        launchJob?.cancel()
        super.onDestroy()
    }

    private fun handleRemoteKey(key: String?) {
        val keyCode = when (key?.lowercase()) {
            "up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "enter", "select", "ok" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> android.view.KeyEvent.KEYCODE_BACK
            else -> return
        }
        dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }
}
