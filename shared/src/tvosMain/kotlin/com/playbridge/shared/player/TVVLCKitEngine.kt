package com.playbridge.shared.player

import com.playbridge.shared.protocol.PlayPayload
// import com.playbridge.shared.vlc.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.darwin.NSObject

/**
 * Apple TV implementation of [PlaybackEngine] using TVVLCKit via cinterop.
 */
class TVVLCKitEngine : PlaybackEngine {

    // private var mediaPlayer: VLCMediaPlayer? = null

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
        // mediaPlayer = VLCMediaPlayer()
        // Setup delegate for events if needed (VLCMediaPlayerDelegateProtocol)
    }

    override suspend fun load(payload: PlayPayload) {
        /*
        val url = NSURL.URLWithString(payload.url) ?: return
        val media = VLCMedia.mediaWithURL(url)

        // Pass headers as options to media
        payload.headers?.forEach { (key, value) ->
            media.addOption(":$key=$value")
        }

        mediaPlayer?.media = media
        mediaPlayer?.play()
        */
    }

    override fun play() {
        // mediaPlayer?.play()
    }

    override fun pause() {
        // mediaPlayer?.pause()
    }

    override fun stop() {
        // mediaPlayer?.stop()
    }

    override fun seek(positionMs: Long) {
        // val time = VLCTime.timeWithInt((positionMs).toInt())
        // mediaPlayer?.setTime(time)
    }

    override fun setRate(rate: Float) {
        // mediaPlayer?.setRate(rate)
    }

    override fun setAudioTrack(id: String?) {
        // if (id == null) return
        // mediaPlayer?.setAudioTrackIndex(id.toInt())
    }

    override fun setSubtitleTrack(id: String?) {
        // if (id == null) return
        // mediaPlayer?.setVideoSubtitleIndex(id.toInt())
    }

    override suspend fun attachExternalSubtitle(url: String, language: String?) {
        // val subUrl = NSURL.URLWithString(url) ?: return
        // mediaPlayer?.addPlaybackSlave(subUrl, type = VLCMediaPlaybackSlaveTypeSubtitle, enforce = true)
    }

    override fun setFilter(filter: VideoFilter, customParams: List<Float>?) {
        // TVVLCKit filters implementation
    }

    override fun release() {
        // mediaPlayer?.stop()
        // mediaPlayer = null
    }
}
