package com.playbridge.shared.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import android.content.Context
import android.view.Surface
import com.playbridge.shared.logging.logger
import com.playbridge.shared.logging.redactUrlForLog
import playbridge.PlayPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android implementation of [PlaybackEngine] using mpv-android (MPVLib).
 */
class MpvPlayerEngine(private val context: Context) : PlaybackEngine, MPVLib.EventObserver {

    private companion object {
        private const val TAG = "MpvPlayerEngine"
        private const val CONTROL_TIMEOUT_MS = 5_000L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val processOwnerLock = Any()
        private var processOwner: MpvPlayerEngine? = null
    }

    /**
     * MPVLib's command/property JNI methods are synchronous. Every call is therefore
     * serialized on this worker so a wedged libmpv core cannot block Android's main looper.
     */
    private val controlQueue = MpvControlQueue(
        timeoutMs = CONTROL_TIMEOUT_MS,
        threadName = "PlayBridge-MPV",
        onTimeout = { name, commandId ->
            val message =
                "MPV control '$name' (#$commandId) did not return within ${CONTROL_TIMEOUT_MS}ms"
            logger.e(TAG, message)
            _state.value = PlaybackState.Error("mpv_control_timeout", message)
        },
        onFailure = { name, error ->
            logger.e(TAG, "MPV control '$name' failed", error)
        }
    )
    private val releaseRequested = AtomicBoolean(false)
    private val observerRegistrations = ConcurrentHashMap<MPVLib.EventObserver, ObserverProxy>()

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(-1L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    @Volatile
    override var isTransitioning = false


    private val _audioTracks = MutableStateFlow<List<Track>>(emptyList())
    override val audioTracks: StateFlow<List<Track>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<Track>>(emptyList())
    override val subtitleTracks: StateFlow<List<Track>> = _subtitleTracks.asStateFlow()

    private val _videoTracks = MutableStateFlow<List<Track>>(emptyList())
    val videoTracks: StateFlow<List<Track>> = _videoTracks.asStateFlow()

    // MPV-specific stream info
    private val _videoHeight = MutableStateFlow(0L)
    val videoHeight: StateFlow<Long> = _videoHeight.asStateFlow()

    private val _videoBitrate = MutableStateFlow(0L)
    val videoBitrate: StateFlow<Long> = _videoBitrate.asStateFlow()

    private val _bufferAhead = MutableStateFlow(0L)
    val bufferAhead: StateFlow<Long> = _bufferAhead.asStateFlow()

    private var mpvInitialized = false
    private var mpvCreated = false
    private var observerRegistered = false
    private var ownsProcessMpv = false
    private var surfaceAttached = false
    @Volatile
    private var playbackSpeed = 1.0f

    init {
        if (claimProcessMpv()) {
            enqueueControl("initialize", requireInitialized = false, allowWhenUnhealthy = true) {
                try {
                    initializeMpv()
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to initialize MPV", e)
                    _state.value = PlaybackState.Error(
                        "mpv_initialization_failed",
                        e.message ?: "MPV initialization failed",
                    )
                    if (cleanupFailedInitialization()) {
                        releaseProcessMpv()
                    } else {
                        logger.e(TAG, "Native MPV cleanup failed; retaining process ownership to prevent reuse")
                    }
                }
            }
        } else {
            val message = "A previous process-global MPV instance is still shutting down"
            logger.e(TAG, message)
            _state.value = PlaybackState.Error("mpv_instance_busy", message)
        }
    }

    private fun enqueueControl(
        name: String,
        requireInitialized: Boolean = true,
        allowWhenUnhealthy: Boolean = false,
        watchForStall: Boolean = true,
        block: () -> Unit,
    ): Boolean {
        if (controlQueue.isUnhealthy && !allowWhenUnhealthy) {
            logger.w(TAG, "Ignoring MPV control '$name' because the control thread is unhealthy")
            return false
        }

        return controlQueue.submit(
            name = name,
            allowWhenUnhealthy = allowWhenUnhealthy,
            allowAfterClose = name == "release",
            watchForStall = watchForStall,
        ) {
            if (!requireInitialized || mpvInitialized) {
                block()
            }
        }
    }

    private class ObserverProxy(
        private val delegate: MPVLib.EventObserver,
    ) : MPVLib.EventObserver {
        private val active = AtomicBoolean(true)

        fun deactivate() {
            active.set(false)
        }

        override fun event(eventId: Int, data: MPVNode) {
            if (active.get()) delegate.event(eventId, data)
        }

        override fun eventProperty(property: String) {
            if (active.get()) delegate.eventProperty(property)
        }

        override fun eventProperty(property: String, value: Boolean) {
            if (active.get()) delegate.eventProperty(property, value)
        }

        override fun eventProperty(property: String, value: Long) {
            if (active.get()) delegate.eventProperty(property, value)
        }

        override fun eventProperty(property: String, value: Double) {
            if (active.get()) delegate.eventProperty(property, value)
        }

        override fun eventProperty(property: String, value: String) {
            if (active.get()) delegate.eventProperty(property, value)
        }

        override fun eventProperty(property: String, value: MPVNode) {
            if (active.get()) delegate.eventProperty(property, value)
        }
    }

    private fun initializeMpv() {
        logger.i(TAG, "initializeMpv() called")
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
        mpvCreated = true

        // Initial options
        // Keep libmpv/FFmpeg from dumping authenticated stream URLs and routine verbose
        // decoder chatter to logcat. Warnings and errors remain available for diagnosis.
        MPVLib.setOptionString("msg-level", "all=warn")
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        // Match Nuvio's stable default. auto-safe lets mpv avoid direct Android decoder paths
        // that are known to wedge ImageReader/BufferQueue teardown on some MediaTek TVs.
        MPVLib.setOptionString("hwdec", "auto-safe")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("tls-verify", "no")
        MPVLib.setOptionString("cache", "yes")

        // Keep the cache bounded for 32-bit Android TV processes. The old 250 MiB total
        // budget left too little native address-space headroom for MediaCodec and GPU buffers
        // during repeated 4K decoder replacement.
        MPVLib.setOptionString("demuxer-max-bytes", "64M")
        MPVLib.setOptionString("demuxer-max-back-bytes", "16M")
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
        observerRegistered = true

        // Register properties to observe
        MPVLib.observeProperty("pause",               3) // Boolean
        MPVLib.observeProperty("time-pos",            5) // Double (seconds)
        MPVLib.observeProperty("duration",            4) // Long (seconds)
        MPVLib.observeProperty("height",              4) // Long (px)
        MPVLib.observeProperty("video-bitrate",       4) // Long (bits/s)
        MPVLib.observeProperty("demuxer-cache-time",  5) // Double
        MPVLib.observeProperty("track-list",          1) // String (JSON)

        mpvInitialized = true
        logger.i(TAG, "MPV initialized successfully")
    }

    private fun claimProcessMpv(): Boolean = synchronized(processOwnerLock) {
        if (processOwner != null) {
            false
        } else {
            processOwner = this
            ownsProcessMpv = true
            true
        }
    }

    private fun cleanupFailedInitialization(): Boolean {
        if (observerRegistered) {
            runCatching { MPVLib.removeObserver(this) }
                .onFailure { logger.w(TAG, "Failed to remove MPV observer during cleanup", it) }
            observerRegistered = false
        }
        var nativeReleased = !mpvCreated
        if (mpvCreated) {
            runCatching { MPVLib.destroy() }
                .onSuccess {
                    mpvCreated = false
                    nativeReleased = true
                }
                .onFailure { logger.e(TAG, "Failed to destroy partially initialized MPV", it) }
        }
        mpvInitialized = false
        surfaceAttached = false
        return nativeReleased
    }

    private fun releaseProcessMpv() {
        synchronized(processOwnerLock) {
            if (processOwner === this) {
                processOwner = null
            }
            ownsProcessMpv = false
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

    private fun attachSurfaceOnControlThread(surface: Surface) {
        if (surfaceAttached || !surface.isValid) return
        MPVLib.attachSurface(surface)
        surfaceAttached = true

        // These are runtime changes: use properties rather than mpv_set_option_string().
        // Restore the VO because detachSurface() disables it before releasing the surface.
        MPVLib.setPropertyString("force-window", "immediate")
        MPVLib.setPropertyString("vo", "gpu")
    }

    private fun detachSurfaceOnControlThread() {
        if (!surfaceAttached) return

        // mpv-android requires the VO to stop using the Android Surface before wid is
        // cleared. In particular, do not call setOptionString("force-window", "no") here:
        // after initialization that synchronous option call can wait indefinitely on the
        // player core and block Android's main thread from surfaceDestroyed().
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
        surfaceAttached = false
    }

    fun attachSurface(surface: Surface) {
        logger.i(TAG, "attachSurface() queued")
        enqueueControl("attachSurface") {
            attachSurfaceOnControlThread(surface)
        }
    }

    fun detachSurface() {
        logger.i(TAG, "detachSurface() queued")
        enqueueControl("detachSurface") {
            detachSurfaceOnControlThread()
        }
    }

    fun setSurfaceSize(w: Int, h: Int) {
        logger.d(TAG, "setSurfaceSize(${w}x${h}) queued")
        enqueueControl("setSurfaceSize") {
            MPVLib.setPropertyString("android-surface-size", "${w}x${h}")
        }
    }

    fun registerObserver(observer: MPVLib.EventObserver, properties: Map<String, Int>) {
        val proxy = ObserverProxy(observer)
        val previous = observerRegistrations.putIfAbsent(observer, proxy)
        if (previous != null) return

        enqueueControl("registerObserver") {
            MPVLib.addObserver(proxy)
            properties.forEach { (property, format) ->
                MPVLib.observeProperty(property, format)
            }
        }
    }

    fun unregisterObserver(observer: MPVLib.EventObserver) {
        val proxy = observerRegistrations.remove(observer) ?: return
        proxy.deactivate()
        enqueueControl("unregisterObserver", allowWhenUnhealthy = true) {
            MPVLib.removeObserver(proxy)
        }
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
        if (controlQueue.isUnhealthy) return

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
        if (controlQueue.isUnhealthy) return
        if (property == "pause") {
            _state.value = if (value) PlaybackState.Paused else PlaybackState.Playing
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
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
            val video = mutableListOf<Track>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getInt("id").toString()
                val type = obj.getString("type")
                val title = obj.optString("title").takeIf { it.isNotBlank() }
                val lang = if (obj.has("lang")) obj.getString("lang") else null

                when (type) {
                    "audio" -> audio += Track(id, title ?: lang ?: "Track $id", lang)
                    "sub" -> subtitles += Track(id, title ?: lang ?: "Track $id", lang)
                    "video" -> {
                        val height = obj.optInt("demux-h", 0)
                        val bitrate = obj.optLong("demux-bitrate", 0L)
                        val quality = if (height > 0) "${height}p" else title ?: "Video $id"
                        val bitrateLabel = bitrate.takeIf { it > 0L }?.let {
                            String.format(java.util.Locale.US, "%.1f Mbps", it / 1_000_000f)
                        }
                        video += Track(
                            id = id,
                            label = listOfNotNull(quality, bitrateLabel).joinToString(" • "),
                            language = lang,
                        )
                    }
                }
            }
            _audioTracks.value = audio
            _subtitleTracks.value = subtitles
            _videoTracks.value = video
            logger.d(
                TAG,
                "Tracks updated: video=${video.size}, audio=${audio.size}, subtitles=${subtitles.size}",
            )
        } catch (e: Exception) {
            logger.e(TAG, "Failed to parse track-list JSON", e)
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {}
    override fun eventProperty(property: String) {}

    override suspend fun load(payload: PlayPayload) {
        logger.i(TAG, "load() queued for ${redactUrlForLog(payload.url)}")
        enqueueControl("load") {
            var userAgentSet = false
            payload.headers.forEach { (k, v) ->
                if (k.equals("user-agent", true)) {
                    MPVLib.setOptionString("user-agent", v)
                    userAgentSet = true
                }
            }
            if (!userAgentSet) {
                MPVLib.setOptionString("user-agent", DEFAULT_USER_AGENT)
            }
            // http-header-fields is a comma-separated string-list option in mpv.
            // Header values that contain commas (e.g. Accept) must be escaped as \,.
            val headerString = payload.headers
                .filterKeys { !it.equals("user-agent", ignoreCase = true) }
                .entries
                .joinToString(",") { "${it.key}: ${it.value.replace("\\", "\\\\").replace(",", "\\,")}" }
            logger.d(TAG, "Setting ${payload.headers.size} HTTP header field(s)")
            MPVLib.setOptionString("http-header-fields", headerString)

            // Be explicit about replacement semantics. Android TV callers wait for any prior
            // stop/decoder release before invoking load(), while other consumers still replace
            // rather than append if they load over an active file.
            MPVLib.command("loadfile", payload.url, "replace")
        }
    }

    override fun play() {
        logger.d(TAG, "play() queued")
        enqueueControl("play") {
            MPVLib.setPropertyBoolean("pause", false)
        }
    }

    override fun pause() {
        logger.d(TAG, "pause() queued")
        enqueueControl("pause") {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }

    override fun stop() {
        logger.d(TAG, "stop() queued")
        enqueueControl("stop") {
            MPVLib.command("stop")
        }
    }

    override fun seek(positionMs: Long) {
        logger.d(TAG, "seek($positionMs) queued")
        enqueueControl("seek") {
            MPVLib.command("seek", (positionMs / 1000.0).toString(), "absolute")
        }
    }

    override fun setRate(rate: Float) {
        logger.i(TAG, "setRate($rate) queued")
        playbackSpeed = rate
        enqueueControl("setRate") {
            MPVLib.setPropertyDouble("speed", rate.toDouble())
        }
    }

    fun setPlaybackSpeed(speed: Float) = setRate(speed)
    fun getPlaybackSpeed(): Float = playbackSpeed

    fun setVideoTrack(id: String?) {
        logger.i(TAG, "setVideoTrack($id) queued")
        enqueueControl("setVideoTrack") {
            MPVLib.setPropertyString("vid", id ?: "no")
        }
    }

    fun setVideoScale(mode: String) {
        logger.i(TAG, "setVideoScale($mode) queued")
        enqueueControl("setVideoScale") {
            // mpv 0.41 deprecated the old -1 sentinel. Explicitly preserve the container
            // aspect so portrait video is letterboxed instead of stretched to the TV surface.
            MPVLib.setPropertyString("video-aspect-method", "container")
            MPVLib.setPropertyString("video-unscaled", "no")
            MPVLib.setPropertyString("keepaspect", "yes")
            when (mode) {
                "Fill" -> {
                    MPVLib.setPropertyString("video-aspect-override", "no")
                    MPVLib.setPropertyString("keepaspect", "no")
                    MPVLib.setPropertyDouble("panscan", 0.0)
                }
                "Zoom" -> {
                    MPVLib.setPropertyString("video-aspect-override", "no")
                    MPVLib.setPropertyDouble("panscan", 1.0)
                }
                "16:9" -> {
                    MPVLib.setPropertyString("video-aspect-override", "16:9")
                    MPVLib.setPropertyDouble("panscan", 0.0)
                }
                "4:3" -> {
                    MPVLib.setPropertyString("video-aspect-override", "4:3")
                    MPVLib.setPropertyDouble("panscan", 0.0)
                }
                else -> {
                    MPVLib.setPropertyString("video-aspect-override", "no")
                    MPVLib.setPropertyDouble("panscan", 0.0)
                }
            }
        }
    }

    fun getVideoScale(): String = "Fit"

    fun setLooping(enabled: Boolean) {
        logger.i(TAG, "setLooping($enabled) queued")
        enqueueControl("setLooping") {
            MPVLib.setPropertyString("loop-file", if (enabled) "inf" else "no")
        }
    }

    override fun setAudioTrack(id: String?) {
        logger.i(TAG, "setAudioTrack($id) queued")
        enqueueControl("setAudioTrack") {
            MPVLib.setPropertyString("aid", id ?: "no")
        }
    }

    override fun setSubtitleTrack(id: String?) {
        logger.i(TAG, "setSubtitleTrack($id) queued")
        enqueueControl("setSubtitleTrack") {
            MPVLib.command("set", "sid", id ?: "no")
            MPVLib.command("set", "sub-visibility", "yes")
        }
    }

    override suspend fun attachExternalSubtitle(url: String, language: String?) {
        logger.i(TAG, "attachExternalSubtitle(${redactUrlForLog(url)}) queued")
        enqueueControl("attachExternalSubtitle") {
            MPVLib.command("sub-add", url, "select", language ?: "External Subtitle")
        }
    }

    fun setAudioFilter(filter: String) {
        enqueueControl("setAudioFilter") {
            MPVLib.setPropertyString("af", filter)
        }
    }

    fun setSubtitleDelay(delayMs: Long) {
        enqueueControl("setSubtitleDelay") {
            MPVLib.setPropertyDouble("sub-delay", delayMs / 1000.0)
        }
    }

    fun setDemuxerFormat(format: String?) {
        enqueueControl("setDemuxerFormat") {
            MPVLib.setOptionString("demuxer-lavf-format", format.orEmpty())
        }
    }

    override fun release() {
        if (!releaseRequested.compareAndSet(false, true)) return
        logger.i(TAG, "release() queued")
        controlQueue.stopAccepting()

        val proxies = observerRegistrations.values.toList()
        observerRegistrations.clear()
        proxies.forEach(ObserverProxy::deactivate)

        enqueueControl(
            name = "release",
            requireInitialized = false,
            allowWhenUnhealthy = true,
            watchForStall = false,
        ) {
            if (ownsProcessMpv) {
                if (mpvInitialized && surfaceAttached) {
                    runCatching { detachSurfaceOnControlThread() }
                        .onFailure { logger.w(TAG, "Failed to detach MPV surface during release", it) }
                }
                proxies.forEach { proxy ->
                    runCatching { MPVLib.removeObserver(proxy) }
                        .onFailure { logger.w(TAG, "Failed to remove MPV observer proxy", it) }
                }
                if (observerRegistered) {
                    runCatching { MPVLib.removeObserver(this) }
                        .onFailure { logger.w(TAG, "Failed to remove MPV observer during release", it) }
                    observerRegistered = false
                }

                var nativeReleased = !mpvCreated
                if (mpvCreated) {
                    runCatching { MPVLib.destroy() }
                        .onSuccess {
                            mpvCreated = false
                            nativeReleased = true
                        }
                        .onFailure { logger.e(TAG, "Failed to destroy MPV", it) }
                }
                mpvInitialized = false
                surfaceAttached = false

                if (nativeReleased) {
                    releaseProcessMpv()
                } else {
                    logger.e(TAG, "Native MPV destroy failed; retaining process ownership to prevent reuse")
                }
            }
            controlQueue.shutdownWatchdog()
        }
        controlQueue.shutdownAfterQueuedTasks()
    }
}
