package com.playbridge.shared.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import android.content.Context
import android.net.Uri
import android.view.Surface
import com.playbridge.shared.logging.logger
import playbridge.PlayPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Android implementation of [PlaybackEngine] using mpv-android (MPVLib).
 */
class MpvPlayerEngine(private val context: Context) : PlaybackEngine, MPVLib.EventObserver {

    private companion object {
        private const val TAG = "MpvPlayerEngine"
    }

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(-1L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    override var isTransitioning = false


    private val _audioTracks = MutableStateFlow<List<Track>>(emptyList())
    override val audioTracks: StateFlow<List<Track>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<Track>>(emptyList())
    override val subtitleTracks: StateFlow<List<Track>> = _subtitleTracks.asStateFlow()

    // MPV-specific stream info
    private val _videoHeight = MutableStateFlow(0L)
    val videoHeight: StateFlow<Long> = _videoHeight.asStateFlow()

    private val _videoBitrate = MutableStateFlow(0L)
    val videoBitrate: StateFlow<Long> = _videoBitrate.asStateFlow()

    private val _bufferAhead = MutableStateFlow(0L)
    val bufferAhead: StateFlow<Long> = _bufferAhead.asStateFlow()

    private var mpvInitialized = false

    init {
        initializeMpv()
    }

    private fun initializeMpv() {
        logger.i(TAG, "initializeMpv() called")
        try {
            // libmpv config setup
            val filesDir = context.filesDir
            try {
                logger.d(TAG, "Overriding HOME to ${filesDir.absolutePath}")
                android.system.Os.setenv("HOME", filesDir.absolutePath, true)
            } catch (e: Exception) {
                logger.w(TAG, "Failed to setenv HOME", e)
            }

            ensureSubtitleFallbackFont(filesDir)
            Utils.copyAssets(context)
            MPVLib.create(context)

            // Initial options
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("hwdec", "mediacodec") // Direct surface decoding is more stable on many TVs
            MPVLib.setOptionString("hwdec-codecs", "h264,hevc,vp8,vp9,av1")
            MPVLib.setOptionString("tls-verify", "no")
            MPVLib.setOptionString("cache", "yes")
            
            // Streaming optimizations
            MPVLib.setOptionString("demuxer-max-bytes", "150M")
            MPVLib.setOptionString("demuxer-max-back-bytes", "100M")
            MPVLib.setOptionString("ytdl", "yes") // Support non-direct URLs if encountered
            MPVLib.setOptionString("hls-bitrate", "max") // Prefer highest quality for HLS

            // Subtitles (Nuvio-style styling for premium look)
            MPVLib.setOptionString("sub-fonts-dir", File(filesDir, "fonts").absolutePath)
            MPVLib.setOptionString("sub-font", "Roboto")
            MPVLib.setOptionString("sub-font-size", "45")
            MPVLib.setOptionString("sub-color", "#FFFFFF")
            MPVLib.setOptionString("sub-border-size", "2.0")
            MPVLib.setOptionString("sub-border-color", "#000000")
            MPVLib.setOptionString("sub-shadow-offset", "1.0")
            MPVLib.setOptionString("sub-shadow-color", "#000000")
            MPVLib.setOptionString("sub-margin-y", "36")
            MPVLib.setOptionString("sub-visibility", "yes")
            // Ensure external subtitles take precedence over embedded ones if explicitly added
            MPVLib.setOptionString("sub-auto", "fuzzy") 

            MPVLib.init()
            MPVLib.addObserver(this)

            // Register properties to observe
            MPVLib.observeProperty("pause",               3) // Boolean
            MPVLib.observeProperty("time-pos",            4) // Long (seconds)
            MPVLib.observeProperty("duration",            4) // Long (seconds)
            MPVLib.observeProperty("height",              4) // Long (px)
            MPVLib.observeProperty("video-bitrate",       4) // Long (bits/s)
            MPVLib.observeProperty("demuxer-cache-time",  5) // Double
            MPVLib.observeProperty("track-list",          1) // String (JSON)

            mpvInitialized = true
            logger.i(TAG, "MPV initialized successfully")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to initialize MPV", e)
            mpvInitialized = false
        }
    }

    private fun ensureSubtitleFallbackFont(filesDir: File) {
        val candidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
            "/system/fonts/NotoSans-Regular.ttf"
        )
        val src = candidates.map { File(it) }.firstOrNull { it.exists() && it.canRead() } ?: return

        val destinations = listOf(
            File(filesDir, ".mpv/subfont.ttf"),
            File(filesDir, "fonts/${src.name}")
        )
        for (dest in destinations) {
            try {
                dest.parentFile?.mkdirs()
                src.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                logger.d(TAG, "Staged fallback font ${src.name} to ${dest.absolutePath}")
            } catch (e: Exception) {
                logger.w(TAG, "Failed to stage fallback font to ${dest.absolutePath}", e)
            }
        }
    }

    fun attachSurface(surface: Surface) {
        logger.i(TAG, "attachSurface()")
        if (!mpvInitialized) return
        MPVLib.attachSurface(surface)
        MPVLib.setOptionString("force-window", "immediate")
    }

    fun detachSurface() {
        logger.i(TAG, "detachSurface()")
        if (!mpvInitialized) return
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
    }

    fun setSurfaceSize(w: Int, h: Int) {
        logger.d(TAG, "setSurfaceSize(${w}x${h})")
        if (!mpvInitialized) return
        MPVLib.setPropertyString("android-surface-size", "${w}x${h}")
    }

    override fun event(eventId: Int, data: MPVNode) {
        val eventName = when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> "START_FILE"
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> "FILE_LOADED"
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> "PLAYBACK_RESTART"
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> "END_FILE"
            MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($eventId)"
        }
        logger.d(TAG, "MPV Event: $eventName")

        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> _state.value = PlaybackState.Buffering
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> _state.value = PlaybackState.Ready
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> _state.value = PlaybackState.Playing
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (!isTransitioning) {
                    _state.value = PlaybackState.Ended
                } else {
                    logger.d(TAG, "Ignoring END_FILE state change while isTransitioning=true")
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> _state.value = PlaybackState.Idle
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        logger.d(TAG, "MPV Property changed: $property = $value")
        if (property == "pause") {
            _state.value = if (value) PlaybackState.Paused else PlaybackState.Playing
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos" -> _position.value = value * 1000
            "duration" -> _duration.value = value * 1000
            "height" -> _videoHeight.value = value
            "video-bitrate" -> _videoBitrate.value = value
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _position.value = (value * 1000).toLong()
            "duration" -> _duration.value = (value * 1000).toLong()
            "demuxer-cache-time" -> _bufferAhead.value = (value * 1000).toLong()
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "track-list") {
            updateTracks(value)
        }
    }

    private fun updateTracks(json: String) {
        try {
            val arr = org.json.JSONArray(json)
            val audio = mutableListOf<Track>()
            val subtitles = mutableListOf<Track>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getInt("id").toString()
                val type = obj.getString("type")
                val title = obj.optString("title", "Track $id")
                val lang = if (obj.has("lang")) obj.getString("lang") else null

                val track = Track(id, title, lang)
                if (type == "audio") audio.add(track)
                else if (type == "video" == false && type == "sub") subtitles.add(track)
            }
            _audioTracks.value = audio
            _subtitleTracks.value = subtitles
            logger.d(TAG, "Tracks updated: audio=${audio.size}, subtitles=${subtitles.size}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to parse track-list JSON", e)
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {}
    override fun eventProperty(property: String) {}

    override suspend fun load(payload: PlayPayload) {
        logger.i(TAG, "load() called with url: ${payload.url}")
        if (!mpvInitialized) {
            logger.e(TAG, "load() failed: MPV not initialized")
            return
        }

        var userAgentSet = false
        payload.headers.forEach { (k, v) ->
            if (k.equals("user-agent", true)) {
                MPVLib.setOptionString("user-agent", v)
                userAgentSet = true
            }
        }
        if (!userAgentSet) {
            MPVLib.setOptionString("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }
        // http-header-fields is a comma-separated string-list option in mpv.
        // Header values that contain commas (e.g. Accept) must be escaped as \,.
        val headerString = payload.headers
            .filterKeys { !it.equals("user-agent", ignoreCase = true) }
            .entries
            .joinToString(",") { "${it.key}: ${it.value.replace("\\", "\\\\").replace(",", "\\,")}" }
        if (headerString.isNotEmpty()) {
            logger.d(TAG, "Setting http-header-fields: $headerString")
            MPVLib.setOptionString("http-header-fields", headerString)
        }

        MPVLib.command("loadfile", payload.url)
    }

    override fun play() {
        logger.d(TAG, "play()")
        if (!mpvInitialized) return
        MPVLib.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        logger.d(TAG, "pause()")
        if (!mpvInitialized) return
        MPVLib.setPropertyBoolean("pause", true)
    }

    override fun stop() {
        logger.d(TAG, "stop()")
        if (!mpvInitialized) return
        MPVLib.command("stop")
    }

    override fun seek(positionMs: Long) {
        logger.d(TAG, "seek($positionMs)")
        if (!mpvInitialized) return
        MPVLib.command("seek", (positionMs / 1000.0).toString(), "absolute+keyframes")
    }

    override fun setRate(rate: Float) {
        logger.i(TAG, "setRate($rate)")
        if (!mpvInitialized) return
        MPVLib.setPropertyDouble("speed", rate.toDouble())
    }

    fun setPlaybackSpeed(speed: Float) = setRate(speed)
    fun getPlaybackSpeed(): Float = if (mpvInitialized) MPVLib.getPropertyDouble("speed")?.toFloat() ?: 1.0f else 1.0f

    fun setVideoTrack(id: String?) {
        logger.i(TAG, "setVideoTrack($id)")
        if (!mpvInitialized) return
        MPVLib.setPropertyString("vid", id ?: "no")
    }

    fun setVideoScale(mode: String) {
        logger.i(TAG, "setVideoScale($mode)")
        // MPV video scaling / aspect ratio control
        if (!mpvInitialized) return
        when (mode) {
            "Fit" -> MPVLib.setPropertyString("video-aspect-override", "-1")
            "Fill", "16:9" -> MPVLib.setPropertyString("video-aspect-override", "1.777")
            "4:3" -> MPVLib.setPropertyString("video-aspect-override", "1.333")
            "Center" -> MPVLib.setPropertyString("video-aspect-override", "-1")
        }
    }

    fun getVideoScale(): String = "Fit"

    override fun setAudioTrack(id: String?) {
        logger.i(TAG, "setAudioTrack($id)")
        if (!mpvInitialized) return
        MPVLib.setPropertyString("aid", id ?: "no")
    }

    override fun setSubtitleTrack(id: String?) {
        logger.i(TAG, "setSubtitleTrack($id)")
        if (!mpvInitialized) return
        MPVLib.command("set", "sid", id ?: "no")
        MPVLib.command("set", "sub-visibility", "yes")
    }

    override suspend fun attachExternalSubtitle(url: String, language: String?) {
        logger.i(TAG, "attachExternalSubtitle($url)")
        if (!mpvInitialized) return
        MPVLib.command("sub-add", url, "select")
    }

    override fun setFilter(filter: VideoFilter, customParams: List<Float>?) {
        logger.i(TAG, "setFilter($filter, customParams=$customParams)")
    }

    override fun release() {
        logger.i(TAG, "release()")
        if (!mpvInitialized) return
        MPVLib.removeObserver(this)
        MPVLib.destroy()
        mpvInitialized = false
    }
}
