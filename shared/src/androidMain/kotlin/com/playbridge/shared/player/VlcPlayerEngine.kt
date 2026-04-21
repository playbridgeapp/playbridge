package com.playbridge.shared.player

import android.content.Context
import android.net.Uri
import com.playbridge.shared.logging.logger
import com.playbridge.shared.protocol.PlayPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * Android implementation of [PlaybackEngine] using LibVLC.
 */
class VlcPlayerEngine(private val context: Context) : PlaybackEngine {

    private companion object {
        private const val TAG = "VlcPlayerEngine"
    }

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

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

    init {
        logger.i(TAG, "Initializing VlcPlayerEngine")
        val args = ArrayList<String>().apply {
            // Network stability
            add("--http-reconnect")
            add("--network-caching=5000")
            add("--clock-jitter=500")
            add("--clock-synchro=0")
        }
        libVlc = LibVLC(context.applicationContext, args)
        mediaPlayer = MediaPlayer(libVlc)
        mediaPlayer?.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    logger.d(TAG, "Event: Playing")
                    _state.value = PlaybackState.Playing
                }
                MediaPlayer.Event.Paused -> {
                    logger.d(TAG, "Event: Paused")
                    _state.value = PlaybackState.Paused
                }
                MediaPlayer.Event.Stopped -> {
                    logger.d(TAG, "Event: Stopped")
                    _state.value = PlaybackState.Idle
                }
                MediaPlayer.Event.EndReached -> {
                    logger.d(TAG, "Event: EndReached")
                    _state.value = PlaybackState.Ended
                }
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering < 100f) {
                        _state.value = PlaybackState.Buffering
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    logger.e(TAG, "Event: EncounteredError")
                    _state.value = PlaybackState.Error("VLC_ERROR", "An error occurred")
                }
                MediaPlayer.Event.PositionChanged -> {
                    mediaPlayer?.let { _position.value = it.time }
                }
                MediaPlayer.Event.LengthChanged -> {
                    mediaPlayer?.let { _duration.value = it.length }
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> {
                    logger.d(TAG, "Event: Tracks changed (ESAdded/Deleted)")
                    updateTracks()
                }
            }
        }
    }

    private fun updateTracks() {
        val player = mediaPlayer ?: return

        val audio = mutableListOf<Track>()
        player.getTracks(IMedia.Track.Type.Audio)?.forEach { track ->
            audio.add(Track(track.id, track.name ?: "Audio", track.language))
        }
        _audioTracks.value = audio

        val subtitles = mutableListOf<Track>()
        player.getTracks(IMedia.Track.Type.Text)?.forEach { track ->
            subtitles.add(Track(track.id, track.name ?: "Subtitle", track.language))
        }
        _subtitleTracks.value = subtitles
        logger.d(TAG, "Tracks updated: audio=${audio.size}, subtitles=${subtitles.size}")
    }

    override suspend fun load(payload: PlayPayload) {
        logger.i(TAG, "load() called with url: ${payload.url}")
        val media = Media(libVlc, Uri.parse(payload.url)).apply {
            setHWDecoderEnabled(true, true)

            val extraHeaders = mutableListOf<String>()
            var userAgentSet = false
            payload.headers?.forEach { (key, value) ->
                when (key.lowercase()) {
                    "user-agent" -> {
                        addOption(":http-user-agent=$value")
                        userAgentSet = true
                    }
                    "referer" -> addOption(":http-referrer=$value")
                    "cookie" -> addOption(":http-cookies=$value")
                    else -> extraHeaders.add("$key: $value")
                }
            }
            if (!userAgentSet) {
                addOption(":http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            }
            if (extraHeaders.isNotEmpty()) {
                val headerString = extraHeaders.joinToString("\r\n")
                logger.d(TAG, "Adding extra headers: $headerString")
                addOption(":http-extra-headers=$headerString")
            }
        }

        mediaPlayer?.let { player ->
            logger.i(TAG, "Setting media and starting playback")
            player.media = media
            media.release()
            player.play()
        }
    }

    override fun play() {
        logger.d(TAG, "play()")
        mediaPlayer?.play()
    }

    override fun pause() {
        logger.d(TAG, "pause()")
        mediaPlayer?.pause()
    }

    override fun stop() {
        logger.d(TAG, "stop()")
        mediaPlayer?.stop()
    }

    override fun seek(positionMs: Long) {
        logger.d(TAG, "seek($positionMs)")
        mediaPlayer?.time = positionMs
    }

    override fun setRate(rate: Float) {
        logger.i(TAG, "setRate($rate)")
        mediaPlayer?.rate = rate
    }

    override fun setAudioTrack(id: String?) {
        logger.i(TAG, "setAudioTrack($id)")
        if (id == null) {
            mediaPlayer?.unselectTrackType(IMedia.Track.Type.Audio)
        } else {
            mediaPlayer?.selectTrack(id)
        }
    }

    override fun setSubtitleTrack(id: String?) {
        logger.i(TAG, "setSubtitleTrack($id)")
        if (id == null) {
            mediaPlayer?.unselectTrackType(IMedia.Track.Type.Text)
        } else {
            mediaPlayer?.selectTrack(id)
        }
    }

    override suspend fun attachExternalSubtitle(url: String, language: String?) {
        logger.i(TAG, "attachExternalSubtitle($url)")
        mediaPlayer?.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
    }

    override fun setFilter(filter: VideoFilter, customParams: List<Float>?) {
        logger.i(TAG, "setFilter($filter, customParams=$customParams)")
        // VLC filters are not yet implemented in common way
    }

    fun setAspectRatio(aspectRatio: String?) {
        logger.i(TAG, "setAspectRatio($aspectRatio)")
        mediaPlayer?.aspectRatio = aspectRatio
    }

    fun setScale(scale: Float) {
        logger.i(TAG, "setScale($scale)")
        mediaPlayer?.scale = scale
    }

    override fun release() {
        logger.i(TAG, "release()")
        mediaPlayer?.setEventListener(null)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        libVlc?.release()
        mediaPlayer = null
        libVlc = null
    }

    fun getMediaPlayer(): MediaPlayer? = mediaPlayer
}
