package com.playbridge.shared.player

import playbridge.PlayPayload
import com.playbridge.shared.resume.ResumeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-double [PlaybackEngine] for unit tests. Exposed as [internal] so it can be reused
 * across multiple test files in the `:shared` module.
 */
internal class FakePlaybackEngine : PlaybackEngine {
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

    var lastLoadedPayload: PlayPayload? = null
        private set
    var released = false
        private set

    fun emitState(newState: PlaybackState) { _state.value = newState }
    fun emitPosition(ms: Long) { _position.value = ms }
    fun emitDuration(ms: Long) { _duration.value = ms }
    fun emitAudioTracks(tracks: List<Track>) { _audioTracks.value = tracks }
    fun emitSubtitleTracks(tracks: List<Track>) { _subtitleTracks.value = tracks }

    override suspend fun load(payload: PlayPayload) {
        lastLoadedPayload = payload
        _state.value = PlaybackState.Buffering
    }

    override fun play() { _state.value = PlaybackState.Playing }
    override fun pause() { _state.value = PlaybackState.Paused }
    override fun stop() { _state.value = PlaybackState.Idle }
    override fun seek(positionMs: Long) { _position.value = positionMs }
    override fun setRate(rate: Float) {}
    override fun setAudioTrack(id: String?) {}
    override fun setSubtitleTrack(id: String?) {}
    override suspend fun attachExternalSubtitle(url: String, language: String?) {}
    override fun setFilter(filter: VideoFilter, customParams: List<Float>?) {}
    override fun release() {
        released = true
        _state.value = PlaybackState.Idle
    }
}

/**
 * Test-double [ResumeStore] for unit tests.
 */
internal class FakeResumeStore : ResumeStore {
    private val store = mutableMapOf<String, Long>()
    override suspend fun loadPosition(url: String): Long = store[url] ?: 0L
    override suspend fun savePosition(url: String, positionMs: Long) {
        store[url] = positionMs
    }
}
