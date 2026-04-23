package com.playbridge.shared.player

import com.playbridge.shared.protocol.PlayPayload
import com.playbridge.shared.logging.logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.darwin.*

/**
 * Apple (tvOS/iOS) implementation of [PlaybackEngine] using AVPlayer.
 */
@OptIn(ExperimentalForeignApi::class)
class AVPlayerEngine : PlaybackEngine {

    private companion object {
        private const val TAG = "AVPlayerEngine"
    }

    private var player: AVPlayer? = null
    private var playerItem: AVPlayerItem? = null

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

    private var timeObserver: Any? = null

    init {
        logger.i(TAG, "Initializing AVPlayerEngine")
        player = AVPlayer()
        setupTimeObserver()
    }

    private fun setupTimeObserver() {
        val interval = CMTimeMake(value = 1, timescale = 2) // 0.5s
        timeObserver = player?.addPeriodicTimeObserverForInterval(interval, null) { time ->
            val seconds = CMTimeGetSeconds(time)
            if (!seconds.isNaN()) {
                _position.value = (seconds * 1000).toLong()
            }
        }
    }

    override suspend fun load(payload: PlayPayload) {
        logger.i(TAG, "load() called with url: ${payload.url}")
        val url = NSURL.URLWithString(payload.url) ?: return
        val asset = AVURLAsset.assetWithURL(url)

        playerItem = AVPlayerItem.playerItemWithAsset(asset)
        player?.replaceCurrentItemWithPlayerItem(playerItem)

        _state.value = PlaybackState.Buffering
        player?.play()
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
        player?.replaceCurrentItemWithPlayerItem(null)
    }

    override fun seek(positionMs: Long) {
        logger.d(TAG, "seek($positionMs)")
        val time = CMTimeMake(value = positionMs, timescale = 1000)
        player?.seekToTime(time = time)
    }

    override fun setRate(rate: Float) {
        logger.i(TAG, "setRate($rate)")
        player?.setRate(rate)
    }

    override fun setAudioTrack(id: String?) {
        logger.i(TAG, "setAudioTrack($id)")
        // Implementation for AVPlayer track selection
    }

    override fun setSubtitleTrack(id: String?) {
        logger.i(TAG, "setSubtitleTrack($id)")
        // Implementation for AVPlayer track selection
    }

    override suspend fun attachExternalSubtitle(url: String, language: String?) {
        logger.i(TAG, "attachExternalSubtitle($url)")
        // Implementation for AVPlayer external subtitles
    }

    override fun setFilter(filter: VideoFilter, customParams: List<Float>?) {
        logger.i(TAG, "setFilter($filter)")
        // Implementation for AVPlayer filters (AVVideoComposition)
    }

    override fun release() {
        logger.i(TAG, "release()")
        timeObserver?.let { player?.removeTimeObserver(it) }
        player?.replaceCurrentItemWithPlayerItem(null)
        player = null
        playerItem = null
    }
}
