package com.playbridge.shared.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.playbridge.shared.network.IPv4FirstDns
import com.playbridge.shared.network.MediaNetworkPolicy
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit
import com.playbridge.shared.logging.logger
import com.playbridge.shared.logging.redactUrlForLog
import playbridge.PlayPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val LEGACY_HTTP_DETECTIONS = setOf(
    "body_content_m3u8",
    "content_type",
    "dom_source",
    "dom_video_element",
    "iptv_m3u",
    "link_menu",
    "player_config",
    "unknown",
    "url_extension",
    "url_pattern_m3u8",
    "url_pattern_mpd",
)

internal fun shouldUseLegacyHttpDataSource(detectedBy: String?): Boolean =
    detectedBy?.lowercase()?.let(LEGACY_HTTP_DETECTIONS::contains) == true

internal fun maxVideoBitrateBps(capMbps: Double?): Int? = capMbps
    ?.takeIf { it.isFinite() && it > 0.0 }
    ?.let { (it * 1_000_000).toInt() }

/**
 * TextRenderer advances its internal cue cursor monotonically. Moving the effective subtitle
 * position backwards therefore requires a player position reset; forward movement can be applied
 * on the next render tick. Paused/buffering playback also needs a reset to refresh cues promptly.
 */
internal fun shouldRefreshSubtitleRenderer(
    previousDelayMs: Long,
    newDelayMs: Long,
    isPlaying: Boolean,
): Boolean = previousDelayMs != newDelayMs && (!isPlaying || newDelayMs < previousDelayMs)

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
        private const val EXTERNAL_SUBTITLE_ID = "playbridge-external-subtitle"
    }

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var videoSurface: Surface? = null
    private val externalPlayerListeners = linkedSetOf<Player.Listener>()

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

    override var isTransitioning = false

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var currentPayload: PlayPayload? = null

    /**
     * Subtitle timing offset in milliseconds. Positive values advance subtitles relative to
     * video (same convention as TV SubtitleManager / remote UI). Applied by [OffsetTextRenderer].
     */
    @Volatile
    private var subtitleDelayMs: Long = 0L

    init {
        // Player is initialized lazily or when first loaded if needed,
        // but for now we initialize it immediately as the original code did.
    }

    override suspend fun load(payload: PlayPayload) {
        load(payload, externalSubtitleUrl = null, externalSubtitleLabel = null)
    }

    suspend fun load(
        payload: PlayPayload,
        externalSubtitleUrl: String?,
        externalSubtitleLabel: String?,
    ) {
        logger.i(TAG, "load() called with url: ${redactUrlForLog(payload.url)}")
        currentPayload = payload
        val livePlayer = player
        if (livePlayer != null) {
            // Episode advance / replacement cast: swap the media on the LIVE player.
            // Avoids a full release + rebuild (renderers, surface re-attach, decoder
            // warm-up) per item — the black gap between episodes — and preserves
            // player-level state like trackSelectionParameters.
            reloadOnLivePlayer(livePlayer, payload, externalSubtitleUrl, externalSubtitleLabel)
        } else {
            initializePlayer(payload, externalSubtitleUrl, externalSubtitleLabel)
        }
    }

    /**
     * Retains the renderer surface across player creation so vendor codecs can be configured with
     * a valid output from their first prepare call. Calling this after creation updates the live
     * player as well.
     */
    fun setVideoSurface(surface: Surface?) {
        videoSurface = surface
        player?.setVideoSurface(surface)
    }

    /** Registers a listener immediately and carries it into a player created by the next load. */
    fun addPlayerListener(listener: Player.Listener) {
        if (externalPlayerListeners.add(listener)) player?.addListener(listener)
    }

    fun removePlayerListener(listener: Player.Listener) {
        externalPlayerListeners.remove(listener)
        player?.removeListener(listener)
    }

    /**
     * Per-item media source bundle. Headers, the network-stack choice, and HLS
     * detection all depend on the payload, so they're rebuilt per item — letting
     * the player itself be reused across items.
     */
    private class PerItemSource(
        val mediaSourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory,
        val mediaItem: MediaItem,
    ) {
        fun createMediaSource() = mediaSourceFactory.createMediaSource(mediaItem)
    }

    private fun buildPerItemSource(payload: PlayPayload): PerItemSource {
        val isPageCastStream = payload.detected_by == "page_cast" || payload.detected_by == "linked_page"
        val allowPrivateNetwork = payload.allow_private_network == true
        if (isPageCastStream && !MediaNetworkPolicy.isAllowedUrlSyntax(
                payload.url,
                allowPrivateNetwork,
            )
        ) {
            throw IllegalArgumentException("Page-cast media destination is not allowed")
        }
        // 1. Extract credentials and prepare Headers
        var finalUrl = payload.url
        val requestProperties = HashMap<String, String>()

        payload.headers.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true) && !key.equals("Accept-Encoding", ignoreCase = true)) {
                requestProperties[key] = value
            }
        }

        try {
            val uri = java.net.URI(finalUrl)
            val userInfo = uri.userInfo
            if (!userInfo.isNullOrBlank() && !requestProperties.any { it.key.equals("Authorization", ignoreCase = true) }) {
                val encoded = android.util.Base64.encodeToString(
                    userInfo.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                requestProperties["Authorization"] = "Basic $encoded"
                val cleanUri = java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment)
                finalUrl = cleanUri.toString()
                logger.i(TAG, "Extracted credentials from URL into Basic Auth header")
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to parse URL for Basic Auth extraction", e)
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

        val defaultUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val userAgent = payload.headers["User-Agent"] ?: defaultUserAgent
        requestProperties["User-Agent"] = userAgent

        logger.i(TAG, "Prepared ${requestProperties.size} request header(s)")

        // Browser-captured/live streams use the legacy source for compatibility. Library,
        // history, Stremio, and Debrid resolver URLs are direct sources and use OkHttp.
        val isBrowserStream = !isPageCastStream && shouldUseLegacyHttpDataSource(payload.detected_by)

        val httpDataSourceFactory = if (isBrowserStream) {
            logger.i(TAG, "Using Legacy Network Stack (DefaultHttpDataSource) for browser-captured stream: ${payload.detected_by}")
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(requestProperties)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(20_000)
        } else {
            logger.i(TAG, "Using Modern Network Stack (OkHttp) with IPv4-First DNS")
            val ipv4FirstDns = IPv4FirstDns()
            val okHttpClient = OkHttpClient.Builder()
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                        val addresses = ipv4FirstDns.lookup(hostname)
                        if (isPageCastStream && !MediaNetworkPolicy.areAllowedAddresses(
                                hostname,
                                addresses,
                                allowPrivateNetwork,
                            )
                        ) {
                            throw UnknownHostException("Page-cast media destination is not allowed")
                        }
                        return addresses
                    }
                })
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .apply {
                    if (isPageCastStream) {
                        proxy(Proxy.NO_PROXY)
                        val originalUrl = payload.url
                        val originBoundNames = requestProperties.keys.toSet()
                        addNetworkInterceptor { chain ->
                            val targetUrl = chain.request().url.toString()
                            val targetHost = chain.request().url.host
                            val peerAddress = chain.connection()?.route()?.socketAddress?.address
                            if (!MediaNetworkPolicy.isAllowedUrlSyntax(targetUrl, allowPrivateNetwork) ||
                                peerAddress == null ||
                                !MediaNetworkPolicy.areAllowedAddresses(
                                    targetHost,
                                    listOf(peerAddress),
                                    allowPrivateNetwork,
                                )
                            ) {
                                throw IOException("Page-cast media destination is not allowed")
                            }
                            val request = if (MediaNetworkPolicy.sameOrigin(originalUrl, targetUrl)) {
                                chain.request()
                            } else {
                                chain.request().newBuilder().apply {
                                    originBoundNames.forEach(::removeHeader)
                                    removeHeader("Authorization")
                                    removeHeader("Cookie")
                                    removeHeader("Origin")
                                    removeHeader("Referer")
                                    header("User-Agent", defaultUserAgent)
                                    header(
                                        "Accept",
                                        "application/vnd.apple.mpegurl, application/x-mpegURL, " +
                                            "application/dash+xml, */*;q=0.8",
                                    )
                                }.build()
                            }
                            chain.proceed(request)
                        }
                    }
                }
                .build()

            OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(requestProperties)
        }
        // Route HTTP(S) through the configured authenticated factory while retaining support for
        // file/content URIs. Native external subtitles are staged as private file URIs so their
        // browser headers never need to cross the renderer-process boundary.
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val isHls = (payload.detected_by == "body_content_m3u8") ||
                    (payload.detected_by == "url_pattern_m3u8") ||
                    (payload.content_type == "application/vnd.apple.mpegurl") ||
                    (payload.content_type == "application/x-mpegurl") ||
                    (payload.content_type == MimeTypes.APPLICATION_M3U8) ||
                    (payload.content_type.isNullOrEmpty() && (payload.url.contains(".m3u8") || payload.url.contains(".jpg")))

        // DASH gets the same explicit treatment as HLS. DefaultMediaSourceFactory can
        // only infer DASH from a `.mpd` URL extension; manifests served from query-style
        // URLs with no extension would otherwise fall through to the progressive
        // extractor and fail. Detect via content-type or `.mpd` in the URL and force
        // the DASH source + MIME. (HLS is checked first so it always wins a tie.)
        val isDash = !isHls && (
                    (payload.content_type == MimeTypes.APPLICATION_MPD) ||
                    (payload.content_type?.contains("dash", ignoreCase = true) == true) ||
                    (payload.url.substringBefore('?').contains(".mpd", ignoreCase = true)))

        logger.i(TAG, "Content detection: isHls=$isHls, isDash=$isDash (detectedBy=${payload.detected_by}, contentType=${payload.content_type})")

        val mediaSourceFactory = when {
            isHls -> {
                logger.i(TAG, "Using HlsMediaSource.Factory")
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
            }
            isDash -> {
                logger.i(TAG, "Using DashMediaSource.Factory")
                DashMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
            }
            else -> {
                logger.i(TAG, "Using DefaultMediaSourceFactory")
                val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true)
                    .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
                    .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
            }
        }

        val builder = MediaItem.Builder().setUri(finalUrl)
        if (payload.title != null) {
            builder.setMediaId(payload.title)
        }
        if (isHls) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (isDash) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (!payload.content_type.isNullOrEmpty()) {
            builder.setMimeType(payload.content_type)
        }

        return PerItemSource(mediaSourceFactory, builder.build())
    }

    /**
     * Swap the media on the live player (no rebuild). Per-item track preferences
     * are layered onto the current parameters; stale per-item overrides are
     * cleared (they reference the previous item's track groups anyway).
     */
    private fun reloadOnLivePlayer(
        exoPlayer: ExoPlayer,
        payload: PlayPayload,
        externalSubtitleUrl: String?,
        externalSubtitleLabel: String?,
    ) {
        logger.i(TAG, "Reusing live ExoPlayer for new item (no rebuild)")

        val paramsBuilder = exoPlayer.trackSelectionParameters.buildUpon().clearOverrides()
        payload.preferred_audio_language?.let {
            logger.i(TAG, "Applying preferred audio language: $it")
            paramsBuilder.setPreferredAudioLanguage(it)
        }
        payload.preferred_subtitle_language?.let {
            logger.i(TAG, "Applying preferred subtitle language: $it")
            paramsBuilder.setPreferredTextLanguage(it)
            paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        payload.default_video_quality?.let { quality ->
            val (maxW, maxH) = when (quality.lowercase()) {
                "720p"        -> 1280 to 720
                "1080p"       -> 1920 to 1080
                "2160p", "4k" -> 3840 to 2160
                else          -> null to null
            }
            if (maxW != null && maxH != null) {
                paramsBuilder.setMaxVideoSize(maxW, maxH)
            }
        }
        maxVideoBitrateBps(payload.max_bitrate_cap_mbps)?.let { capBps ->
            paramsBuilder.setMaxVideoBitrate(capBps)
        }
        if (externalSubtitleUrl != null) {
            paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        exoPlayer.trackSelectionParameters = paramsBuilder.build()

        exoPlayer.setMediaSource(
            buildMediaSource(payload, externalSubtitleUrl, externalSubtitleLabel),
        )
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        startProgressTracker()
    }

    private fun initializePlayer(
        payload: PlayPayload,
        externalSubtitleUrl: String?,
        externalSubtitleLabel: String?,
    ) {
        val bufCfg = AndroidBufferConfig.compute(context)
        logger.i(TAG, "Initializing player with buffer config: maxBufferMs=${bufCfg.maxBufferMs}, targetBytes=${bufCfg.targetBytes}")

        val perItem = buildPerItemSource(payload)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, 
                bufCfg.maxBufferMs, 
                2_500, 
                5_000 
            )
            .setTargetBufferBytes(bufCfg.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(bufCfg.prioritizeTime)
            .setBackBuffer(0, false)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            init {
                // PREFER exists for AUDIO: platform decoders frequently misdeclare or
                // botch DTS/TrueHD/E-AC3, so the FFmpeg audio renderer goes first.
                // Video is forced back to hardware-first in buildVideoRenderers below.
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
                // If the primary decoder fails to initialize, let MediaCodecRenderer
                // try the next capable decoder (alternate vendor codec, then software)
                // instead of surfacing a fatal playback error.
                setEnableDecoderFallback(true)
                // "codec_async_blocked" is set by ExoPlayerActivity after a fatal
                // decoder error in async mode: some vendor decoders (MTK TV panels)
                // crash when MediaCodec is operated asynchronously (the API 31+
                // default) yet decode the same stream fine synchronously — observed
                // directly: c2.mtk.avc.decoder dies under ExoPlayer/async but works
                // under MPV/sync on the same file.
                val enginePrefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                if (enginePrefs.getBoolean("codec_async_blocked", false)) {
                    logger.i(TAG, "Async MediaCodec blocked on this device — using synchronous codec mode")
                    forceDisableMediaCodecAsynchronousQueueing()
                }
                // "dv_decoders_blocked" is set by ExoPlayerActivity after a Dolby Vision
                // hardware decoder fatally failed (MTK DV decoders accept dvhe.08 then die
                // with 0xfffffff4). Excluding DV decoders makes media3 select the HEVC/AVC
                // BASE-LAYER decoders it already appends as compatibility fallbacks for DV
                // profiles 8/9 — same content rendered as HDR10, the exact path MPV uses
                // successfully on the same hardware.
                if (enginePrefs.getBoolean("dv_decoders_blocked", false)) {
                    logger.i(TAG, "Dolby Vision decoders blocked on this device — using HEVC/AVC base-layer decoders")
                    setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                        if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                            emptyList()
                        } else {
                            androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                        }
                    }
                }
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                // Hardware MediaCodec must stay first for video: with PREFER, the
                // FFmpeg SOFTWARE video renderer would front-run the hardware decoder
                // for any codec the FFmpeg build supports — software video decode is
                // a stutter/thermal crash on TV silicon. ON keeps the extension
                // renderer available strictly as a last-resort fallback.
                super.buildVideoRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_ON,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out
                )
            }

            override fun buildTextRenderers(
                context: Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                // OffsetTextRenderer wraps final TextRenderer so setSubtitleDelay can shift
                // cue selection for embedded and sideloaded text tracks.
                out.add(OffsetTextRenderer(output, outputLooper) { subtitleDelayMs })
            }
        }

        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        // Tunneling is OPT-IN (default off — matching the Settings toggle's displayed
        // default): it buys smoother A/V sync and Dolby Vision on well-certified boxes,
        // but many vendor decoders (notably MediaTek TV panels: "vendor decode not
        // init", CodecException 0xfffffff4 right after the tunneled configure) crash
        // on tunneled 4K while decoding the same stream fine without it.
        // "tunneling_auto_blocked" is set by ExoPlayerActivity after a fatal decoder
        // error in tunneled mode; an explicit user toggle of the setting clears it.
        val useTunneling = prefs.getBoolean("tunneled_playback", false) &&
            !prefs.getBoolean("tunneling_auto_blocked", false)

        // 2. Track Selector
        trackSelector = DefaultTrackSelector(context).apply {
            val params = buildUponParameters()
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)

            payload.preferred_audio_language?.let {
                logger.i(TAG, "Applying preferred audio language: $it")
                params.setPreferredAudioLanguage(it)
            }
            payload.preferred_subtitle_language?.let {
                logger.i(TAG, "Applying preferred subtitle language: $it")
                params.setPreferredTextLanguage(it)
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            if (externalSubtitleUrl != null) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }

            payload.default_video_quality?.let { quality ->
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

            maxVideoBitrateBps(payload.max_bitrate_cap_mbps)?.let { capBps ->
                logger.i(TAG, "Applying max bitrate cap: $capBps bps")
                params.setMaxVideoBitrate(capBps)
            }

            if (useTunneling) {
                logger.i(TAG, "Enabling Video Tunneling")
                params.setTunnelingEnabled(true)
            }

            setParameters(params)
        }

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(perItem.mediaSourceFactory)
            .setSeekForwardIncrementMs(10_000)
            .setReleaseTimeoutMs(3_000) // Prevent hanging during engine transitions
            // Proxy/debrid VOD often reaches the end without signalling EOS, so Media3's default
            // 60s STUCK_PLAYING_NOT_ENDING timeout delays end-of-episode advance. Keep a short
            // timeout so that case surfaces quickly. Live/mid-stream freezes also raise
            // ERROR_CODE_TIMEOUT; ExoRendererService recovers those (live edge / re-prepare)
            // instead of treating them as fatal engine failures.
            .setStuckPlayingNotEndingTimeoutMs(6_000)
            .build()
            .also { exoPlayer ->
                logger.i(TAG, "ExoPlayer instance created")
                exoPlayer.addListener(playerListener)
                externalPlayerListeners.forEach(exoPlayer::addListener)

                videoSurface?.let(exoPlayer::setVideoSurface)

                exoPlayer.setMediaSource(
                    createMediaSource(perItem, externalSubtitleUrl, externalSubtitleLabel),
                )
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
                Player.STATE_ENDED -> if (isTransitioning) PlaybackState.Buffering else PlaybackState.Ended
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

    /**
     * Apply a subtitle timing offset. Positive advances subtitles (look-ahead in the cue
     * timeline); negative delays them. Moving the effective cue position backwards requires a
     * no-op seek so TextRenderer rewinds its internal cue cursor.
     */
    fun setSubtitleDelay(delayMs: Long) {
        val clamped = delayMs.coerceIn(-120_000L, 120_000L)
        val previousDelayMs = subtitleDelayMs
        if (previousDelayMs == clamped) return
        logger.i(TAG, "setSubtitleDelay($clamped)")
        subtitleDelayMs = clamped
        val exoPlayer = player ?: return
        if (shouldRefreshSubtitleRenderer(previousDelayMs, clamped, exoPlayer.isPlaying)) {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            exoPlayer.seekTo(pos)
        }
    }

    override suspend fun attachExternalSubtitle(url: String, language: String?) {
        logger.i(TAG, "attachExternalSubtitle(${redactUrlForLog(url)})")
        val exoPlayer = player ?: return
        val payload = currentPayload ?: return
        val perItem = buildPerItemSource(payload)
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val shouldPlay = exoPlayer.playWhenReady
        val mediaSource = createMediaSource(perItem, url, language)

        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        exoPlayer.setMediaSource(mediaSource, positionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = shouldPlay
    }

    private fun buildMediaSource(
        payload: PlayPayload,
        externalSubtitleUrl: String?,
        externalSubtitleLabel: String?,
    ): MediaSource = createMediaSource(
        buildPerItemSource(payload),
        externalSubtitleUrl,
        externalSubtitleLabel,
    )

    private fun createMediaSource(
        perItem: PerItemSource,
        externalSubtitleUrl: String?,
        externalSubtitleLabel: String?,
    ): MediaSource {
        if (externalSubtitleUrl == null) return perItem.createMediaSource()
        val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(externalSubtitleUrl))
            .setMimeType(externalSubtitleMimeType(externalSubtitleUrl))
            .setLabel(externalSubtitleLabel)
            .setId(EXTERNAL_SUBTITLE_ID)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        // HlsMediaSource.Factory and DashMediaSource.Factory only create their primary source;
        // unlike DefaultMediaSourceFactory, they do not merge MediaItem sidecar configurations.
        // Build the subtitle source explicitly so browser-cast HLS/DASH streams get the same
        // native sidecar behavior as progressive media. The subtitle has already been staged as
        // a private file URI, so a local-capable DefaultDataSource is sufficient here.
        @Suppress("DEPRECATION")
        val subtitleSource = SingleSampleMediaSource.Factory(DefaultDataSource.Factory(context))
            .setTreatLoadErrorsAsEndOfStream(false)
            .createMediaSource(subtitle, C.TIME_UNSET)
        return MergingMediaSource(perItem.createMediaSource(), subtitleSource)
    }

    private fun externalSubtitleMimeType(url: String): String = when (
        url.substringBefore('?').substringAfterLast('.', missingDelimiterValue = "").lowercase()
    ) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    override fun release() {
        logger.i(TAG, "release()")
        progressJob?.cancel()
        player?.release()
        player = null
        videoSurface = null
        externalPlayerListeners.clear()
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
