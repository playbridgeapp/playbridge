package com.playbridge.shared.player

import com.playbridge.shared.logging.logger
import com.playbridge.shared.resume.ResumeStore
import playbridge.PlayPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PlayerViewModel"

/**
 * Cross-platform playback state machine.
 *
 * Owns:
 * - Playlist queue and auto-advance
 * - Series navigation (prev / next episode)
 * - Pre-play stream resolution
 * - Resume-position load
 * - Mapping of [PlaybackEngine] events to [PlayerUiState]
 *
 * Does **not** own:
 * - Low-level engine retry logic (audio discontinuity, decoder init, etc.)
 *   — that stays inside each [PlaybackEngine] actual for Step 5a.
 * - UI rendering (controls overlay, dialogs, surface attachment).
 */
class PlayerViewModel(
    val engine: PlaybackEngine,
    private val resumeStore: ResumeStore,
    private val scope: CoroutineScope,
) {

    private val _ui = MutableStateFlow<PlayerUiState>(PlayerUiState.Idle)
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    val position: StateFlow<Long> = engine.position
    val duration: StateFlow<Long> = engine.duration
    val audioTracks: StateFlow<List<Track>> = engine.audioTracks
    val subtitleTracks: StateFlow<List<Track>> = engine.subtitleTracks

    /** The current playlist, if any. */
    private var playlist: List<PlayPayload> = emptyList()
    private var playlistIndex: Int = 0

    /** Whether single-video loop is enabled. */
    private var isLooping: Boolean = false


    /** The payload most recently passed to [engine.load]. */
    private var currentPayload: PlayPayload? = null

    /** Resume position to seek to once the engine reaches [PlaybackState.Ready]. */
    private var pendingResumePosition: Long = 0L

    private var prePlayJob: Job? = null
    private var engineStateJob: Job? = null
    private var navigationJob: Job? = null

    init {
        observeEngineState()
    }

    // ------------------------------------------------------------------
    // Engine observation
    // ------------------------------------------------------------------

    private fun observeEngineState() {
        engineStateJob?.cancel()
        engineStateJob = scope.launch {
            engine.state.collect { state ->
                when (state) {
                    is PlaybackState.Ready -> {
                        if (pendingResumePosition > 0) {
                            engine.seek(pendingResumePosition)
                            pendingResumePosition = 0
                        }
                    }

                    is PlaybackState.Playing -> {
                        val payload = currentPayload
                        if (payload != null) {
                            _ui.value = PlayerUiState.Playing(
                                payload = payload,
                                isPaused = false,
                                isPlaylist = playlist.isNotEmpty(),
                                playlistIndex = playlistIndex,
                                playlistSize = playlist.size,
                            )
                        }
                    }

                    is PlaybackState.Paused -> {
                        val payload = currentPayload
                        if (payload != null && _ui.value is PlayerUiState.Playing) {
                            _ui.value = (_ui.value as PlayerUiState.Playing).copy(isPaused = true)
                        }
                    }

                    is PlaybackState.Ended -> {
                        handlePlaybackEnded()
                    }

                    is PlaybackState.Error -> {
                        _ui.value = PlayerUiState.Error(
                            code = state.code,
                            message = state.msg,
                        )
                    }

                    is PlaybackState.Idle,
                    is PlaybackState.Buffering -> {
                        // No-op — Idle is expected during teardown; Buffering is
                        // an intermediate step toward Ready/Playing.
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /**
     * Play a single direct URL. If the URL points to an M3U playlist it is
     * expanded into [playlist] and the first item is loaded.
     */
    fun onPayload(payload: PlayPayload, settings: PlaybackSettings = PlaybackSettings()) {
        logger.i(TAG, "onPayload: ${payload.url}")
        currentPayload = payload

        scope.launch {
            // Attempt M3U expansion
            val urlWithoutQuery = payload.url.substringBefore("?")
            val isM3u = payload.content_type == "application/vnd.apple.mpegurl"
                || payload.content_type == "application/x-mpegurl"
                || urlWithoutQuery.endsWith(".m3u")
                || urlWithoutQuery.endsWith(".m3u8")

            if (isM3u) {
                val parsed = M3uParser.fetchAndParseM3u(payload.url, payload.headers)
                if (parsed != null && parsed.isNotEmpty()) {
                    logger.i(TAG, "M3U playlist parsed: ${parsed.size} items")
                    setPlaylist(parsed, 0)
                    val first = parsed[0]
                    loadPayloadInternal(first, isPlaylist = true, playlistIndex = 0)
                    return@launch
                }
            }

            playlist = emptyList()
            playlistIndex = 0
            loadPayloadInternal(payload, isPlaylist = false, playlistIndex = 0)
        }
    }

    /**
     * Load an explicit playlist and start at [startIndex].
     */
    fun onPlaylistPayload(payloads: List<PlayPayload>, startIndex: Int = 0) {
        if (payloads.isEmpty()) return
        setPlaylist(payloads, startIndex)
        val payload = payloads[playlistIndex]
        currentPayload = payload

        scope.launch {
            loadPayloadInternal(payload, isPlaylist = true, playlistIndex = playlistIndex)
        }
    }


    // ------------------------------------------------------------------
    // Playback controls (delegated to engine)
    // ------------------------------------------------------------------

    fun togglePlayPause() {
        when (engine.state.value) {
            is PlaybackState.Playing -> engine.pause()
            is PlaybackState.Paused,
            is PlaybackState.Ready -> engine.play()
            else -> { /* no-op */ }
        }
    }

    fun seek(positionMs: Long) {
        engine.seek(positionMs)
    }

    fun setPlaybackSpeed(rate: Float) {
        engine.setRate(rate)
    }

    fun setAudioTrack(id: String?) {
        engine.setAudioTrack(id)
    }

    fun setSubtitleTrack(id: String?) {
        engine.setSubtitleTrack(id)
    }

    fun attachExternalSubtitle(url: String, language: String?) {
        scope.launch {
            engine.attachExternalSubtitle(url, language)
        }
    }

    fun setVideoFilter(filter: VideoFilter, customParams: List<Float>? = null) {
        engine.setFilter(filter, customParams)
    }

    fun setLooping(enabled: Boolean) {
        isLooping = enabled
    }

    // ------------------------------------------------------------------
    // Playlist / Series navigation
    // ------------------------------------------------------------------

    fun setPlaylist(items: List<PlayPayload>, startIndex: Int = 0) {
        playlist = items
        playlistIndex = startIndex.coerceIn(0, items.size.coerceAtLeast(1) - 1)
    }


    fun next() {
        navigationJob?.cancel()
        navigationJob = scope.launch {
            saveCurrentProgress()
            advance(1)
        }
    }

    fun previous() {
        navigationJob?.cancel()
        navigationJob = scope.launch {
            saveCurrentProgress()
            advance(-1)
        }
    }

    fun jumpToPlaylistIndex(index: Int) {
        if (index < 0 || index >= playlist.size) return
        navigationJob?.cancel()
        navigationJob = scope.launch {
            saveCurrentProgress()
            playlistIndex = index
            loadPlaylistItem(index)
        }
    }

    fun retry() {
        val current = currentPayload
        if (current != null) {
            onPayload(current)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun dispose() {
        prePlayJob?.cancel()
        navigationJob?.cancel()
        engineStateJob?.cancel()
        engine.release()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun loadPayloadInternal(payload: PlayPayload, isPlaylist: Boolean, playlistIndex: Int) {
        currentPayload = payload
        pendingResumePosition = resumeStore.loadPosition(payload.url)
        _ui.value = PlayerUiState.Loading(
            payload = payload,
            isPlaylist = isPlaylist,
            playlistIndex = playlistIndex,
            playlistSize = playlist.size,
        )
        engine.load(payload)
        engine.play()
    }

    private suspend fun advance(direction: Int) {
        if (playlist.isNotEmpty()) {
            val newIndex = playlistIndex + direction
            if (newIndex in playlist.indices) {
                playlistIndex = newIndex
                loadPlaylistItem(playlistIndex)
                return
            }
        }

        // No playlist or at boundary

        // Nothing to advance to
        if (direction == 1) {
            _ui.value = PlayerUiState.Ended(null)
        }
    }

    private suspend fun loadPlaylistItem(index: Int) {
        val item = playlist.getOrNull(index) ?: return
        loadPayloadInternal(item, isPlaylist = true, playlistIndex = index)
    }


    private fun handlePlaybackEnded() {
        if (isLooping) {
            engine.seek(0)
            engine.play()
            return
        }

        scope.launch {
            saveCurrentProgress()
            if (playlist.isNotEmpty() && playlistIndex < playlist.lastIndex) {
                playlistIndex++
                loadPlaylistItem(playlistIndex)
            } else {
                _ui.value = PlayerUiState.Ended(null)
            }
        }
    }

    private suspend fun saveCurrentProgress() {
        val payload = currentPayload ?: return
        val pos = position.value
        val dur = duration.value
        if (dur > 0 && pos > 0) {
            resumeStore.savePosition(payload.url, pos)
        }
    }
}
