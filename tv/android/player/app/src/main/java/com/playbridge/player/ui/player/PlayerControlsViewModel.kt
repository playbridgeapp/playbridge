package com.playbridge.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.player.player.PlayerEngineAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log
import com.playbridge.player.player.SubtitleManager

class PlayerControlsViewModel : ViewModel() {
    private val _controlsState = MutableStateFlow(PlayerControlsState())
    val controlsState = _controlsState.asStateFlow()

    private var autoHideJob: Job? = null
    private var progressUpdateJob: Job? = null
    private var engine: PlayerEngineAdapter? = null
    private var subtitleManager: SubtitleManager? = null

    fun setEngine(playerEngine: PlayerEngineAdapter, engineType: String) {
        this.engine = playerEngine
        _controlsState.update { it.copy(engineType = engineType) }
        startProgressUpdates()
    }

    fun showControls(full: Boolean = true, playing: Boolean? = null) {
        _controlsState.update { 
            it.copy(
                isVisible = true, 
                isFullControlsVisible = full,
                isPlaying = playing ?: engine?.isPlaying ?: false,
                title = it.title // Keep title
            )
        }
        resetAutoHideTimer()
    }

    fun showSeekUI() {
        showControls(full = false)
    }

    fun hideControls() {
        if (_controlsState.value.activeOverlay != ActiveOverlay.NONE) {
            hideOverlay()
        }
        _controlsState.update { it.copy(isVisible = false) }
        autoHideJob?.cancel()
    }

    fun togglePlayPause() {
        engine?.let {
            if (it.isPlaying) {
                it.pause()
                setPlaying(false)
            } else {
                it.play()
                setPlaying(true)
                hideControls()
            }
        }
        resetAutoHideTimer()
    }

    fun updateMetadata(title: String? = null, subtitle: String? = null, streamInfo: String? = null, hdrFormat: String? = null) {
        _controlsState.update { 
            it.copy(
                title = title ?: it.title,
                subtitle = subtitle ?: it.subtitle,
                streamInfo = streamInfo ?: it.streamInfo,
                hdrFormat = hdrFormat ?: it.hdrFormat
            )
        }
    }

    fun setTitle(title: String) {
        _controlsState.update { it.copy(title = title) }
    }

    fun getTitle(): String = _controlsState.value.title

    fun setPlaying(playing: Boolean) {
        _controlsState.update { it.copy(isPlaying = playing) }
    }

    fun setSubtitleDelay(delayMs: Long) {
        _controlsState.update { it.copy(subtitleDelayMs = delayMs) }
    }

    fun adjustSubtitleDelay(deltaMs: Long) {
        val newDelay = _controlsState.value.subtitleDelayMs + deltaMs
        engine?.setSubtitleDelay(newDelay)
        subtitleManager?.setOffset(newDelay)
        setSubtitleDelay(newDelay)
    }

    fun toggleAudioBoost() {
        val newState = !_controlsState.value.isAudioBoostEnabled
        engine?.setLoudnessEnhancer(newState)
        _controlsState.update { it.copy(isAudioBoostEnabled = newState) }
    }

    fun setPendingSeekTime(time: Long) {
        // This can be used to show a preview value on the seekbar
        _controlsState.update { it.copy(currentPosition = time) }
    }

    fun setSeasonInfo(info: String?) {
        _controlsState.update { it.copy(subtitle = info) }
    }

    fun setPlaylistVisible(visible: Boolean) {
        _controlsState.update { it.copy(hasPlaylist = visible) }
    }

    
    fun setNavigationVisible(visible: Boolean) {
        // In current Compose impl, navigation buttons are shown if hasPlaylist is true
        // For now, we mix them, but we could add a specific flag if needed.
        _controlsState.update { it.copy(hasPlaylist = visible) }
    }

    fun setLooping(enabled: Boolean) {
        _controlsState.update { it.copy(isLooping = enabled) }
    }

    fun setBuffering(isBuffering: Boolean) {
        _controlsState.update { it.copy(isBuffering = isBuffering) }
    }
    
    fun setPrePlay(metadata: playbridge.VisualMetadata?) {
        _controlsState.update { it.copy(prePlayMetadata = metadata) }
    }
    
    fun setPrePlayCountdown(seconds: Int) {
        _controlsState.update { it.copy(prePlayCountdown = seconds) }
    }
    
    fun setPrePlayLaunching(launching: Boolean) {
        _controlsState.update { it.copy(isPrePlayLaunching = launching) }
    }

    private var scrubPosition: Long = 0
    private var isScrubbing = false
    private var commitSeekJob: Job? = null

    fun handleScrubbing(deltaMs: Long) {
        val currentEngine = engine ?: return
        if (!isScrubbing) {
            isScrubbing = true
            scrubPosition = currentEngine.currentPosition
        }

        val duration = currentEngine.duration
        if (duration > 0) {
            scrubPosition = (scrubPosition + deltaMs).coerceIn(0, duration)
            _controlsState.update { it.copy(currentPosition = scrubPosition) }

            commitSeekJob?.cancel()
            commitSeekJob = viewModelScope.launch {
                delay(400)
                commitSeek()
            }
        }
        showSeekUI()
    }

    fun commitSeek() {
        if (isScrubbing) {
            engine?.seekTo(scrubPosition)
            isScrubbing = false
            resetAutoHideTimer()
        }
    }

    private fun startProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch {
            while (true) {
                engine?.let {
                    _controlsState.update { s ->
                        s.copy(
                            currentPosition = if (isScrubbing) s.currentPosition else it.currentPosition,
                            duration = it.duration,
                            bufferedPosition = it.bufferedPosition,
                            isPlaying = it.isPlaying,
                            streamInfo = it.streamInfo,
                            hdrFormat = it.hdrFormat
                        )
                    }
                }
                delay(1000)
            }
        }
    }

    fun resetAutoHideTimer(durationMs: Long = 5000) {
        autoHideJob?.cancel()
        val state = _controlsState.value
        // Hide if:
        // 1. It's just the seek UI (not full)
        // 2. OR it's full controls and we're playing
        val shouldHide = !state.isFullControlsVisible || state.isPlaying
        
        if (shouldHide) {
            autoHideJob = viewModelScope.launch {
                delay(durationMs)
                hideControls()
            }
        }
    }

    fun showSettings(tab: SettingsTab) {
        showOverlay(ActiveOverlay.SETTINGS)
        _controlsState.update { it.copy(activeSettingsTab = tab) }
    }

    fun hideSettings() {
        hideOverlay()
    }


    fun showVideoFilter(
        filter: com.playbridge.shared.player.VideoFilter,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        preview: android.graphics.Bitmap?
    ) {
        _controlsState.update {
            it.copy(
                currentFilter = filter,
                customBrightness = brightness,
                customContrast = contrast,
                customSaturation = saturation,
                previewFrame = preview
            )
        }
        showOverlay(ActiveOverlay.VIDEO_FILTER)
    }

    /** Refresh the playlist data (picker contents + current index) WITHOUT opening the picker. */
    fun updatePlaylistData(items: List<playbridge.PlayPayload>, index: Int) {
        _controlsState.update {
            it.copy(
                playlistItems = items,
                playlistIndex = index
            )
        }
    }

    /** Refresh the playlist data AND open the picker overlay (explicit user action). */
    fun showPlaylist(items: List<playbridge.PlayPayload>, index: Int) {
        updatePlaylistData(items, index)
        showOverlay(ActiveOverlay.PLAYLIST_PICKER)
    }

    fun showSwitchPlayer() {
        showOverlay(ActiveOverlay.SWITCH_PLAYER)
    }

    private fun showOverlay(overlay: ActiveOverlay) {
        _controlsState.update { 
            it.copy(
                activeOverlay = overlay, 
                isVisible = true, 
                isFullControlsVisible = true 
            ) 
        }
        autoHideJob?.cancel()
    }

    fun hideOverlay() {
        _controlsState.update { it.copy(activeOverlay = ActiveOverlay.NONE) }
        resetAutoHideTimer()
    }

    fun updateTracks(audio: List<UnifiedTrack>, subtitles: List<UnifiedTrack>, video: List<UnifiedTrack>) {
        _controlsState.update { 
            it.copy(
                audioTracks = audio,
                subtitleTracks = subtitles,
                videoTracks = video
            )
        }
    }
    
    fun setPlaybackSpeed(speed: Float) {
        engine?.setPlaybackSpeed(speed)
        _controlsState.update { it.copy(playbackSpeed = speed) }
    }
    
    fun setVideoScaling(mode: String) {
        _controlsState.update { it.copy(videoScalingMode = mode) }
    }

    /** Update the current filter state without opening the filter overlay (used for phone-driven changes). */
    fun setVideoFilterState(
        filter: com.playbridge.shared.player.VideoFilter,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ) {
        _controlsState.update {
            it.copy(
                currentFilter = filter,
                customBrightness = brightness,
                customContrast = contrast,
                customSaturation = saturation
            )
        }
    }

    fun loadExternalSubtitle(url: String, headers: Map<String, String>? = null) {
        if (subtitleManager == null) {
            subtitleManager = SubtitleManager(viewModelScope) { text ->
                _controlsState.update { it.copy(currentSubtitleText = text) }
            }
        }
        subtitleManager?.setPlayer { engine?.currentPosition ?: 0L }
        subtitleManager?.setOffset(_controlsState.value.subtitleDelayMs)
        subtitleManager?.loadSubtitle(url, headers)
    }

    fun clearSubtitle() {
        subtitleManager?.disable()
        _controlsState.update { it.copy(currentSubtitleText = null) }
    }

    fun detach() {
        autoHideJob?.cancel()
        progressUpdateJob?.cancel()
        commitSeekJob?.cancel()
        engine = null
    }
}
