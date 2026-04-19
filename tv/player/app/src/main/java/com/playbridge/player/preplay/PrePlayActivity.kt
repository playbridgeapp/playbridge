package com.playbridge.player.preplay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.player.ExoPlayerActivity
import com.playbridge.player.player.MpvPlayerActivity
import com.playbridge.player.player.VlcPlayerActivity
import com.playbridge.player.server.ServerService
import com.playbridge.player.stremio.ScoredStremioStream
import com.playbridge.player.stremio.StremioClient
import com.playbridge.protocol.ContentPlayPayload
import com.playbridge.protocol.SeriesContext
import com.playbridge.protocol.SeriesEpisodeRef
import com.playbridge.protocol.protocolJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

private const val TAG = "PrePlayActivity"

class PrePlayActivity : ComponentActivity() {

    private var resolutionJob: Job? = null
    private var payload by mutableStateOf<ContentPlayPayload?>(null)
    private var isLaunching by mutableStateOf(false)
    private var launchCountdown by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            val p = payload
            if (p != null) {
                PrePlayScreen(
                    payload = p,
                    isLaunching = isLaunching,
                    launchCountdown = launchCountdown,
                    onStreamSelected = { stream ->
                        launchPlayer(stream)
                    },
                    onBack = {
                        resolutionJob?.cancel()
                        ServerService.notifyContextIdle()
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {

        val payloadJson = intent.getStringExtra(ServerService.EXTRA_CONTENT_PAYLOAD)
        if (payloadJson == null) {
            if (payload == null) {
                Log.e(TAG, "No payload provided")
                finish()
            }
            return
        }

        try {
            val newPayload = protocolJson.decodeFromString(ContentPlayPayload.serializer(), payloadJson)
            FileLogger.i(TAG, "New intent received for: ${newPayload.title}")
            payload = newPayload
            isLaunching = false // Reset launching state for new content
            startResolution()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payload", e)
            if (payload == null) finish()
        }
    }

    private fun startResolution() {
        val p = payload ?: return
        resolutionJob?.cancel()
        resolutionJob = lifecycleScope.launch {
            try {
                // TV settings for auto-selection
                val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                val autoQuality = p.defaultVideoQuality ?: prefs.getString("auto_stream_quality", "") ?: ""
                val autoMaxMbps = p.maxBitrateCapMbps ?: prefs.getString("auto_stream_max_mbps", "")?.toDoubleOrNull()
                val preferredAddon = p.preferredAddonBaseUrl ?: prefs.getString("auto_stream_addon", "") ?: ""

                FileLogger.i(TAG, "Starting resolution for: ${p.title} (${p.contentId})")
                FileLogger.i(TAG, "  Preferences: quality=$autoQuality, maxMbps=$autoMaxMbps, preferredAddon=$preferredAddon")
                FileLogger.i(TAG, "  Force Picker: ${p.forcePicker}")

                // Source-type preferences: payload wins if present, else fall back to TV prefs.
                val prefSourceTypesCsv = prefs.getString("auto_stream_source_types", "") ?: ""
                val sourceTypes: List<String>? = (p.preferredSourceTypes?.takeIf { it.isNotEmpty() }
                    ?: prefSourceTypesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() })

                val streams = StremioClient.resolveStreamsByContentId(
                    addonBaseUrls = p.addonBaseUrls,
                    addonNames = p.addonNames,
                    contentId = p.contentId,
                    contentType = p.contentType,
                    season = p.season,
                    episode = p.episode,
                    qualityPreference = autoQuality.takeIf { it.isNotEmpty() },
                    preferredAddonBaseUrl = preferredAddon.takeIf { it.isNotEmpty() },
                    preferredAddonName = p.preferredAddonName ?: prefs.getString("auto_stream_addon_name", ""),
                    preferredSourceTypes = sourceTypes,
                    runtimeMinutes = p.episodeRuntimeMinutes,
                    maxBitrateMbps = autoMaxMbps
                )

                if (streams.isEmpty()) {
                    FileLogger.w(TAG, "No streams resolved for ${p.contentId}")
                    return@launch
                }

                FileLogger.i(TAG, "Resolved ${streams.size} streams")
                streams.take(5).forEachIndexed { index, stream ->
                    FileLogger.i(TAG, "  [$index] score=${stream.score} quality=${stream.rank} name=${stream.name} title=${stream.title}")
                }

                if (p.forcePicker) {
                    FileLogger.i(TAG, "Manual selection forced by phone")
                    return@launch
                }

                // Auto-pick logic (port of StreamSelector.selectBest)
                val best = streams.firstOrNull() // resolveStreamsByContentId already sorts by score
                if (best != null && autoQuality.isNotEmpty()) {
                    FileLogger.i(TAG, "Auto-picking best stream: ${best.name} (score ${best.score})")
                    launchPlayer(best)
                } else {
                    FileLogger.i(TAG, "Auto-pick criteria not met (autoQuality is empty or no streams), showing picker")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Resolution failed", e)
            }
        }
    }

    private fun launchPlayer(stream: ScoredStremioStream) {
        val p = payload ?: return
        if (isFinishing) return
        isLaunching = true

        FileLogger.i(TAG, "Preparing player launch for stream: ${stream.name}")
        FileLogger.i(TAG, "  URL: ${stream.url}")

        val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val tvPref = if (prefs.contains("player_mode")) {
            prefs.getString("player_mode", "phone") ?: "phone"
        } else {
            if (prefs.getBoolean("use_external_player", false)) "external" else "phone"
        }

        val finalMode = if (tvPref == "phone") {
            val phoneMode = p.playerMode
            if (phoneMode != null && phoneMode != "tv") {
                phoneMode
            } else {
                "internal"
            }
        } else {
            tvPref
        }

        lifecycleScope.launch {
            // ── The 5 second countdown ──
            // Transition from "Resolving" to "Ready" then countdown
            for (i in 5 downTo 1) {
                launchCountdown = i
                delay(1000)
            }

            if (finalMode == "external" || finalMode == "external_mpv") {

                val intent = if (finalMode == "external_mpv") {
                     Intent(Intent.ACTION_VIEW).apply {
                        setClassName("is.xyz.mpv", "is.xyz.mpv.MPVActivity")
                        data = android.net.Uri.parse(stream.url)
                        putExtra(Intent.EXTRA_TITLE, p.title)
                        putExtra("title", p.title)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                } else {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(android.net.Uri.parse(stream.url), "video/*")
                        putExtra(Intent.EXTRA_TITLE, p.title)
                        putExtra("title", p.title)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                }
                startActivity(intent)
                finish()
                return@launch
            }

            val activityClass = when (finalMode) {
                "internal_vlc" -> VlcPlayerActivity::class.java
                "internal_mpv" -> MpvPlayerActivity::class.java
                else           -> ExoPlayerActivity::class.java
            }

            val intent = Intent(this@PrePlayActivity, activityClass).apply {
                putExtra(ServerService.EXTRA_URL, stream.url)
                val fullTitle = if (p.contentType == "series" && p.season != null && p.episode != null) {
                    "${p.title} S${p.season}E${p.episode}${if (p.episodeTitle != null) " - ${p.episodeTitle}" else ""}"
                } else {
                    p.title
                }
                putExtra(ServerService.EXTRA_TITLE, fullTitle)
                putExtra(ServerService.EXTRA_CONTENT_TYPE, p.contentType)
                putExtra(ServerService.EXTRA_DETECTED_BY, "library")

                p.preferredAudioLanguage?.let { putExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG, it) }
                p.preferredSubtitleLanguage?.let { putExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG, it) }
                p.defaultVideoQuality?.let { putExtra("default_video_quality", it) }
                p.maxBitrateCapMbps?.let { putExtra(ServerService.EXTRA_MAX_BITRATE_CAP_MBPS, it) }

                if (p.contentType == "series") {
                    val seriesContext = SeriesContext(
                        imdbId = p.contentId,
                        season = p.season ?: 1,
                        episode = p.episode ?: 1,
                        seriesTitle = p.title,
                        episodeTitle = p.episodeTitle,
                        addonBaseUrls = p.addonBaseUrls,
                        addonNames = p.addonNames,
                        allEpisodes = p.allEpisodes,
                        preferredAddonBaseUrl = p.preferredAddonBaseUrl,
                        preferredAddonName = p.preferredAddonName
                    )
                    val seriesContextJson = protocolJson.encodeToString(SeriesContext.serializer(), seriesContext)
                    putExtra(ServerService.EXTRA_SERIES_CONTEXT, seriesContextJson)
                }

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        resolutionJob?.cancel()
        super.onDestroy()
    }
}
