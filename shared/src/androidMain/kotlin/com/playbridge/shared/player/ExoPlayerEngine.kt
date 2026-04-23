package com.playbridge.shared.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.playbridge.shared.logging.logger
import com.playbridge.shared.protocol.PlayPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android implementation of [PlaybackEngine] using Media3 ExoPlayer.
 *
 * This implementation includes the complex logic for:
 * - Buffer configuration based on device memory
 * - Header handling and Referer/Origin fallback
 * - HLS vs Progressive content detection
 * - Track selection preferences (language, quality, bitrate)
 * - Custom load error handling
 */
class ExoPlayerEngine(private val context: Context) : PlaybackEngine {

    private companion object {
        private const val TAG = "ExoPlayerEngine"
    }

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private val videoFilterManager = VideoFilterManager()

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(-1L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<Track>>(emptyList())
    override val audioTracks: StateFlow<List<Track>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<Track>>(emptyList())
    override val subtitleTracks: StateFlow<List<Track>> = _subtitleTracks.asStateFlow()

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var currentPayload: PlayPayload? = null

    init {
        // Player is initialized lazily or when first loaded if needed,
        // but for now we initialize it immediately as the original code did.
    }

    override suspend fun load(payload: PlayPayload) {
        logger.i(TAG, "load() called with url: ${payload.url}")
        currentPayload = payload
        release() // Release previous instance if any
        initializePlayer(payload)
    }

    private fun initializePlayer(payload: PlayPayload) {
        val bufCfg = AndroidBufferConfig.compute(context)
        logger.i(TAG, "Initializing player with buffer config: maxBufferMs=${bufCfg.maxBufferMs}, targetBytes=${bufCfg.targetBytes}")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, bufCfg.maxBufferMs, 1000, 2500)
            .setTargetBufferBytes(bufCfg.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(bufCfg.prioritizeTime)
            .setBackBuffer(0, false)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildTextRenderers(
                context: Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                out.forEach {
                    if (it is androidx.media3.exoplayer.text.TextRenderer) {
                        it.experimentalSetLegacyDecodingEnabled(true)
                    }
                }
            }
        }

        // 1. Prepare Headers
        val requestProperties = HashMap<String, String>()
        payload.headers?.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true) && !key.equals("Accept-Encoding", ignoreCase = true)) {
                requestProperties[key] = value
            } else {
                logger.i(TAG, "Stripping header to prevent ExoPlayer buffering issues: $key: $value")
            }
        }

        if (!requestProperties.containsKey("Referer")) {
            try {
                val uri = Uri.parse(payload.url)
                val scheme = uri.scheme ?: "https"
                val host = uri.host
                if (host != null) {
                    val referer = "$scheme://$host/"
                    requestProperties["Referer"] = referer
                    logger.i(TAG, "Added fallback Referer: $referer")
                    if (!requestProperties.containsKey("Origin")) {
                        requestProperties["Origin"] = "$scheme://$host"
                        logger.i(TAG, "Added fallback Origin: ${requestProperties["Origin"]}")
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "Error parsing URL for Referer fallback", e)
            }
        }

        val userAgent = payload.headers?.get("User-Agent")
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        requestProperties["User-Agent"] = userAgent

        logger.i(TAG, "Final Request Headers: $requestProperties")

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(requestProperties)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)

        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        val useTunneling = prefs.getBoolean("tunneled_playback", false)

        // 2. Track Selector
        trackSelector = DefaultTrackSelector(context).apply {
            val params = buildUponParameters()
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)

            payload.preferredAudioLanguage?.let {
                logger.i(TAG, "Applying preferred audio language: $it")
                params.setPreferredAudioLanguage(it)
            }
            payload.preferredSubtitleLanguage?.let {
                logger.i(TAG, "Applying preferred subtitle language: $it")
                params.setPreferredTextLanguage(it)
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }

            payload.defaultVideoQuality?.let { quality ->
                val (maxW, maxH) = when (quality.lowercase()) {
                    "720p"        -> 1280 to 720
                    "1080p"       -> 1920 to 1080
                    "2160p", "4k" -> 3840 to 2160
                    else          -> null to null
                }
                if (maxW != null && maxH != null) {
                    logger.i(TAG, "Applying video quality preference: $quality -> maxSize=${maxW}x${maxH}")
                    params.setMaxVideoSize(maxW, maxH)
                }
            }

            payload.maxBitrateCapMbps?.let { cap ->
                val capBps = (cap * 1_000_000).toInt()
                logger.i(TAG, "Applying max bitrate cap: $cap Mbps -> $capBps bps")
                params.setMaxVideoBitrate(capBps)
            }

            if (useTunneling) {
                logger.i(TAG, "Enabling Video Tunneling")
                params.setTunnelingEnabled(true)
            }

            setParameters(params)
        }

        // 3. Media Source Factory
        val isHls = (payload.detectedBy == "body_content_m3u8") ||
                    (payload.detectedBy == "url_pattern_m3u8") ||
                    (payload.contentType == "application/vnd.apple.mpegurl") ||
                    (payload.contentType == "application/x-mpegurl") ||
                    (payload.contentType == MimeTypes.APPLICATION_M3U8) ||
                    (payload.contentType.isNullOrEmpty() && (payload.url.contains(".m3u8") || payload.url.contains(".jpg")))

        logger.i(TAG, "Content detection: isHls=$isHls (detectedBy=${payload.detectedBy}, contentType=${payload.contentType})")

        val mediaSourceFactory = if (isHls) {
            logger.i(TAG, "Using HlsMediaSource.Factory")
            HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
        } else {
            logger.i(TAG, "Using DefaultMediaSourceFactory")
            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
            DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory)
                .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
        }

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { exoPlayer ->
                logger.i(TAG, "ExoPlayer instance created")
                exoPlayer.addListener(playerListener)
                videoFilterManager.setPlayer(exoPlayer)

                val builder = MediaItem.Builder().setUri(payload.url)
                if (payload.title != null) {
                    builder.setMediaId(payload.title)
                }
                if (isHls) {
                    builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                } else if (!payload.contentType.isNullOrEmpty()) {
                    builder.setMimeType(payload.contentType)
                }

                val mediaItem = builder.build()
                val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                }
        startProgressTracker()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            logger.d(TAG, "onPlaybackStateChanged: $stateName")

            _state.value = when (playbackState) {
                Player.STATE_IDLE -> PlaybackState.Idle
                Player.STATE_BUFFERING -> PlaybackState.Buffering
                Player.STATE_READY -> if (player?.playWhenReady == true) PlaybackState.Playing else PlaybackState.Ready
                Player.STATE_ENDED -> PlaybackState.Ended
                else -> PlaybackState.Idle
            }
            _duration.value = player?.duration ?: -1L
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            logger.d(TAG, "onPlayWhenReadyChanged: $playWhenReady, reason=$reason")
            if (_state.value is PlaybackState.Ready || _state.value is PlaybackState.Playing || _state.value is PlaybackState.Paused) {
                _state.value = if (playWhenReady) PlaybackState.Playing else PlaybackState.Paused
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            logger.e(TAG, "onPlayerError: ${error.errorCodeName} (${error.errorCode}): ${error.message}", error)
            _state.value = PlaybackState.Error(error.errorCodeName, error.message ?: "Unknown error")
        }

        override fun onTracksChanged(tracks: Tracks) {
            logger.d(TAG, "onTracksChanged")
            updateTracks(tracks)
        }
    }

    private fun updateTracks(tracks: Tracks) {
        val audio = mutableListOf<Track>()
        val subtitles = mutableListOf<Track>()

        tracks.groups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val track = Track(
                    id = format.id ?: "${group.hashCode()}-$i",
                    label = format.label ?: format.language ?: "Unknown",
                    language = format.language
                )
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    audio.add(track)
                } else if (group.type == C.TRACK_TYPE_TEXT) {
                    subtitles.add(track)
                }
            }
        }
        _audioTracks.value = audio
        _subtitleTracks.value = subtitles
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                player?.let {
                    _position.value = it.currentPosition
                }
                delay(500)
            }
        }
    }

    override fun play() {
        logger.d(TAG, "play()")
        player?.play()
    }
    override fun pause() {
        logger.d(TAG, "pause()")
        player?.pause()
    }
    override fun stop() {
        logger.d(TAG, "stop()")
        player?.stop()
    }
    override fun seek(positionMs: Long) {
        logger.d(TAG, "seek($positionMs)")
        player?.seekTo(positionMs)
    }
    override fun setRate(rate: Float) {
        logger.i(TAG, "setRate($rate)")
        player?.setPlaybackSpeed(rate)
    }

    override fun setAudioTrack(id: String?) {
        logger.i(TAG, "setAudioTrack($id)")
        // Implementation for track selection
    }

    override fun setSubtitleTrack(id: String?) {
        logger.i(TAG, "setSubtitleTrack($id)")
        val selector = trackSelector ?: return
        if (id == null) {
            logger.i(TAG, "Disabling subtitles")
            val params = selector.parameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            selector.parameters = params
        } else {
            // Implementation for specific track selection
        }
    }

    override suspend fun attachExternalSubtitle(url: String, language: String?) {
        logger.i(TAG, "attachExternalSubtitle($url, $language)")
        // Implementation for external subtitles
    }

    override fun setFilter(filter: VideoFilter, customParams: List<Float>?) {
        logger.i(TAG, "setFilter($filter, customParams=$customParams)")
        if (filter == VideoFilter.CUSTOM && customParams != null && customParams.size >= 3) {
            videoFilterManager.applyCustom(customParams[0], customParams[1], customParams[2])
        } else {
            videoFilterManager.applyFilter(filter)
        }
    }

    override fun release() {
        logger.i(TAG, "release()")
        progressJob?.cancel()
        videoFilterManager.setPlayer(null)
        player?.release()
        player = null
    }

    fun getExoPlayer(): ExoPlayer? = player

    private class CustomLoadErrorHandlingPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val exception = loadErrorInfo.exception
            if (exception is androidx.media3.common.ParserException && exception.contentIsMalformed) {
                return C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }
}
