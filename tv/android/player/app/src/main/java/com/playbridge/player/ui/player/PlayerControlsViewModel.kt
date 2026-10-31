package com.playbridge.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.player.player.PageMediaProxyRouter
import com.playbridge.player.player.PlayerEngineAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.playbridge.player.player.SubtitleManager
import com.playbridge.player.player.SubtitleCueLoader
import com.playbridge.player.player.SkipSegment

private const val END_SEEK_PADDING_MS = 250L

internal fun skipSegmentEndsPlayback(segment: SkipSegment, durationMs: Long): Boolean =
    segment.endMs == com.playbridge.player.player.SkipSegmentFetcher.OPEN_ENDED_MS ||
        (durationMs > 0L && segment.endMs >= durationMs - 1_000L)

internal fun skipTargetMs(segment: SkipSegment, durationMs: Long): Long = when {
    durationMs > 0L && skipSegmentEndsPlayback(segment, durationMs) ->
        (durationMs - END_SEEK_PADDING_MS).coerceAtLeast(0L)
    segment.endMs == com.playbridge.player.player.SkipSegmentFetcher.OPEN_ENDED_MS ->
        segment.startMs.coerceAtLeast(0L)
    else -> segment.endMs + 1_000L
}

class PlayerControlsViewModel @JvmOverloads constructor(
    private val injectedScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    private val _controlsState = MutableStateFlow(PlayerControlsState())
    val controlsState = _controlsState.asStateFlow()

    private var autoHideJob: Job? = null
    private var progressUpdateJob: Job? = null
    private var engine: PlayerEngineAdapter? = null
    private var subtitleManager: SubtitleManager? = null
    private var onlineSubtitleJob: Job? = null
    private var cachedOnlineTracks: List<UnifiedTrack> = emptyList()
    private var lastSkippedSegment: SkipSegment? = null
    private var onEndSegmentSkipped: (() -> Unit)? = null

    /** Request headers for fetching subtitle files (set by the activity when media loads). */
    var subtitleRequestHeaders: Map<String, String>? = null
    var subtitleRequestHeadersByUrl: Map<String, Map<String, String>> = emptyMap()
    var enforcePageSubtitleNetworkPolicy: Boolean = false
    var allowedPrivateSubtitleOrigins: List<String> = emptyList()
    var trustedLocalSubtitleProxyOrigin: String? = null
    var subtitleTargetResolver: ((String, Map<String, String>?) -> SubtitleLoadTarget?)? = null

    data class SubtitleLoadTarget(
        val fetchUrl: String,
        val headers: Map<String, String>?,
        val trustedLocalProxyOrigin: String? = null,
    )

    /**
     * Lazily download + parse the given subtitle URLs (for the live preview in the overlay).
     * Cached in [SubtitleCueLoader], so this no-ops for already-loaded ones. Bumps a version
     * flag on completion so the overlay re-reads the cache.
     */
    fun preloadSubtitleCues(urls: List<String>) {
        urls.forEach { url ->
            if (SubtitleCueLoader.cached(url) != null || SubtitleCueLoader.isLoading(url)) return@forEach
            val fallbackHeaders = subtitleRequestHeadersByUrl[url] ?: subtitleRequestHeaders
            val resolver = subtitleTargetResolver
            val resolved = if (resolver != null) {
                resolver(url, fallbackHeaders) ?: return@forEach
            } else {
                null
            }
            scope.launch {
                SubtitleCueLoader.load(
                    url = url,
                    headers = if (resolved != null) resolved.headers else fallbackHeaders,
                    enforcePageNetworkPolicy = enforcePageSubtitleNetworkPolicy,
                    allowedPrivateOrigins = allowedPrivateSubtitleOrigins,
                    fetchUrl = resolved?.fetchUrl ?: url,
                    trustedLocalProxyOrigin = resolved?.trustedLocalProxyOrigin
                        ?: trustedLocalSubtitleProxyOrigin,
                )
                _controlsState.update { it.copy(subtitleCuesVersion = it.subtitleCuesVersion + 1) }
            }
        }
    }

    private var contextRef: java.lang.ref.WeakReference<android.content.Context>? = null

    fun setEngine(playerEngine: PlayerEngineAdapter, engineType: String, context: android.content.Context) {
        this.engine = playerEngine
        this.contextRef = java.lang.ref.WeakReference(context.applicationContext)
        _controlsState.update { it.copy(engineType = engineType) }
        startProgressUpdates()
    }

    fun setEndSegmentSkipHandler(handler: (() -> Unit)?) {
        onEndSegmentSkipped = handler
    }

    fun showControls(full: Boolean = true, playing: Boolean? = null) {
        _controlsState.update {
            val hasActiveOverlay = it.activeOverlay != ActiveOverlay.NONE
            it.copy(
                isVisible = true,
                isFullControlsVisible = full || hasActiveOverlay,
                isPlaying = playing ?: engine?.isPlaying ?: it.isPlaying,
                lastClosedOverlay = if (hasActiveOverlay) it.lastClosedOverlay else ActiveOverlay.NONE,
                title = it.title // Keep title
            )
        }
        resetAutoHideTimer()
    }

    fun showSeekUI() {
        val current = _controlsState.value
        val keepFullControls = (current.isVisible && current.isFullControlsVisible) ||
            current.activeOverlay != ActiveOverlay.NONE
        showControls(full = keepFullControls)
    }

    fun hideControls() {
        autoHideJob?.cancel()
        _controlsState.update {
            it.copy(
                isVisible = false,
                activeOverlay = ActiveOverlay.NONE,
                lastClosedOverlay = ActiveOverlay.NONE,
            )
        }
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

    /**
     * Set absolute subtitle delay (ms). Positive shows subtitles later (mpv `sub-delay`).
     * Always pushes to the engine and external overlay manager — do not only update UI state.
     */
    fun setSubtitleDelay(delayMs: Long) {
        val clamped = delayMs.coerceIn(-120_000L, 120_000L)
        engine?.setSubtitleDelay(clamped)
        subtitleManager?.setOffset(clamped)
        _controlsState.update { it.copy(subtitleDelayMs = clamped) }
    }

    fun adjustSubtitleDelay(deltaMs: Long) {
        setSubtitleDelay(_controlsState.value.subtitleDelayMs + deltaMs)
    }

    /** Zero the offset (used by the subtitle Sync "Reset" control). */
    fun resetSubtitleDelay() {
        setSubtitleDelay(0L)
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
        _controlsState.update {
            it.copy(
                isBuffering = isBuffering,
                activeSkipSegment = if (isBuffering) null else it.activeSkipSegment,
                isSkipButtonFocused = if (isBuffering) false else it.isSkipButtonFocused,
            )
        }
    }

    fun showPlaybackTransition(message: String) {
        autoHideJob?.cancel()
        _controlsState.update {
            it.copy(
                playbackTransitionMessage = message,
                isVisible = false,
                isFullControlsVisible = false,
                activeOverlay = ActiveOverlay.NONE,
                lastClosedOverlay = ActiveOverlay.NONE,
                activeSkipSegment = null,
                isSkipButtonFocused = false,
            )
        }
    }

    fun clearPlaybackTransition() {
        _controlsState.update { it.copy(playbackTransitionMessage = null) }
    }
    
    private var skipSegmentsJob: kotlinx.coroutines.Job? = null
    private var chapterOsdJob: Job? = null

    fun setChapters(chapters: List<com.playbridge.shared.player.PlayerChapter>) {
        if (chapters.isEmpty()) {
            chapterOsdJob?.cancel()
            _controlsState.update { state ->
                state.copy(
                    chapters = emptyList(),
                    chapterOsd = null,
                    activeOverlay = if (state.activeOverlay == ActiveOverlay.CHAPTERS) {
                        ActiveOverlay.NONE
                    } else {
                        state.activeOverlay
                    },
                )
            }
        } else {
            _controlsState.update { state ->
                state.copy(
                    chapters = chapters,
                    activeOverlay = if (chapters.size < 2 && state.activeOverlay == ActiveOverlay.CHAPTERS) {
                        ActiveOverlay.NONE
                    } else {
                        state.activeOverlay
                    },
                )
            }
        }
    }

    fun showChapters() {
        if (!_controlsState.value.hasSelectableChapters()) return
        showOverlay(ActiveOverlay.CHAPTERS)
    }

    fun showChapterOsd(text: String) {
        chapterOsdJob?.cancel()
        _controlsState.update { it.copy(chapterOsd = text) }
        chapterOsdJob = scope.launch {
            delay(2_000L)
            _controlsState.update { it.copy(chapterOsd = null) }
        }
    }

    fun setPrePlay(
        metadata: playbridge.VisualMetadata?,
        context: android.content.Context? = null,
        clearOnlineSubs: Boolean = true,
        showCountdown: Boolean = true
    ) {
        if (clearOnlineSubs) {
            onlineSubtitleJob?.cancel()
            cachedOnlineTracks = emptyList()
        }
        skipSegmentsJob?.cancel()
        lastSkippedSegment = null
        val clearTimeline = metadata != null || clearOnlineSubs
        if (clearTimeline) {
            chapterOsdJob?.cancel()
        }

        _controlsState.update { state ->
            val nextActiveMetadata = metadata ?: if (!clearOnlineSubs) state.activeMetadata else null
            val nextSkipSegments = if (clearTimeline) emptyList() else state.skipSegments
            val nextActiveSkipSegment = if (clearTimeline) null else state.activeSkipSegment
            val nextChapters = if (clearTimeline) emptyList() else state.chapters
            val nextChapterOsd = if (clearTimeline) null else state.chapterOsd
            state.copy(
                activeMetadata = nextActiveMetadata,
                prePlayMetadata = if (metadata != null && showCountdown) metadata else null,
                skipSegments = nextSkipSegments,
                activeSkipSegment = nextActiveSkipSegment,
                chapters = nextChapters,
                chapterOsd = nextChapterOsd,
            )
        }

        val imdbId = metadata?.imdb_id?.takeIf { it.isNotBlank() }
        if (imdbId != null) {
            onlineSubtitleJob = viewModelScope.launch {
                try {
                    val results = com.playbridge.player.player.SubtitleFetcher.fetchSubtitles(
                        imdbId = imdbId,
                        season = metadata.season,
                        episode = metadata.episode
                    )
                    val fetchedTracks = results.map { sub ->
                        val name = "${sub.lang} · OpenSubtitles #${sub.id}"
                        val urlWithFragment = "${sub.url}#${java.net.URLEncoder.encode(name, "UTF-8")}"
                        UnifiedTrack(
                            id = urlWithFragment,
                            name = name,
                            isSelected = false,
                            type = "external_sub"
                        )
                    }
                    cachedOnlineTracks = fetchedTracks
                    _controlsState.update { s ->
                        val existingIds = s.subtitleTracks.map { it.id }.toSet()
                        val newTracks = fetchedTracks.filter { it.id !in existingIds }
                        s.copy(subtitleTracks = s.subtitleTracks + newTracks)
                    }
                } catch (_: Exception) { }
            }
        }

        val season = metadata?.season
        val episode = metadata?.episode
        val tmdbId = metadata?.tmdb_id?.takeIf { it.isNotBlank() }
        // Episodes need season+episode; when both are absent this is a movie lookup
        // (TheIntroDB covers movies). A half-specified episode is skipped.
        val validShape = (season != null && episode != null) || (season == null && episode == null)
        if ((imdbId != null || tmdbId != null) && context != null && validShape) {
            val appCtx = context.applicationContext
            skipSegmentsJob = viewModelScope.launch {
                try {
                    val segments = com.playbridge.player.player.SkipSegmentFetcher.fetchSegments(
                        appCtx, imdbId = imdbId, tmdbId = tmdbId, season = season, episode = episode
                    )
                    com.playbridge.player.logging.FileLogger.i("PlayerControlsViewModel", "Set skipSegments in state: $segments")
                    _controlsState.update { it.copy(skipSegments = segments) }
                } catch (e: Exception) {
                    com.playbridge.player.logging.FileLogger.e("PlayerControlsViewModel", "Error fetching skip segments: ${e.message}")
                }
            }
        }
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
                    // Safety net for the (full controls / overlay) ⟺ paused invariant: if
                    // playback is running but full controls or an overlay are still up (a missed
                    // play-state callback), dismiss them. Engine callbacks are the primary
                    // mechanism; this only catches the gap. The lightweight seek UI
                    // (isVisible && !isFullControlsVisible) is intentionally shown during
                    // playback and left to its own auto-hide timer, so it's excluded here.
                    val cs = _controlsState.value
                    if (it.isPlaying && !isScrubbing &&
                        cs.activeOverlay == ActiveOverlay.NONE &&
                        cs.isFullControlsVisible) {
                        hideControls()
                    }

                    val currentPos = if (isScrubbing) _controlsState.value.currentPosition else it.currentPosition
                    val duration = it.duration
                    
                    val activeSegment = _controlsState.value
                        .takeUnless { it.isPlaybackObscured() }
                        ?.skipSegments
                        ?.firstOrNull { segment ->
                            currentPos >= segment.startMs &&
                                currentPos <= segment.endMs &&
                                segment != lastSkippedSegment
                        }
                    
                    lastSkippedSegment?.let { skipped ->
                        if (currentPos < skipped.startMs || currentPos > skipped.endMs) {
                            lastSkippedSegment = null
                        }
                    }
                    
                    val context = contextRef?.get()
                    var isAutoSkipTriggered = false
                    if (activeSegment != null && context != null && lastSkippedSegment != activeSegment) {
                        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                        val shouldAutoSkip = when (activeSegment.type) {
                            "intro" -> prefs.getBoolean("auto_skip_intro", false)
                            "recap" -> prefs.getBoolean("auto_skip_recap", false)
                            "outro" -> prefs.getBoolean("auto_skip_outro", false)
                            else -> false
                        }
                        if (shouldAutoSkip) {
                            lastSkippedSegment = activeSegment
                            isAutoSkipTriggered = true
                            performSegmentSkip(activeSegment, duration)
                            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Auto-skipped ${activeSegment.type}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    val uiActiveSegment = if (isAutoSkipTriggered) null else {
                        val isAutoSkipEnabled = if (context != null && activeSegment != null) {
                            val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            when (activeSegment.type) {
                                "intro" -> prefs.getBoolean("auto_skip_intro", false)
                                "recap" -> prefs.getBoolean("auto_skip_recap", false)
                                "outro" -> prefs.getBoolean("auto_skip_outro", false)
                                else -> false
                            }
                        } else false
                        
                        if (isAutoSkipEnabled) null else activeSegment
                    }

                    if (_controlsState.value.activeSkipSegment != uiActiveSegment) {
                        com.playbridge.player.logging.FileLogger.d("PlayerControlsViewModel", "Active segment changed: old=${_controlsState.value.activeSkipSegment}, new=$uiActiveSegment")
                    }

                    _controlsState.update { s ->
                        s.copy(
                            currentPosition = if (isScrubbing) s.currentPosition else it.currentPosition,
                            duration = duration,
                            bufferedPosition = it.bufferedPosition,
                            isPlaying = it.isPlaying,
                            streamInfo = it.streamInfo,
                            hdrFormat = it.hdrFormat,
                            activeSkipSegment = if (s.isPlaybackObscured()) {
                                null
                            } else {
                                uiActiveSegment
                            },
                        )
                    }
                }
                delay(250)
            }
        }
    }

    fun resetAutoHideTimer(durationMs: Long = 5000) {
        autoHideJob?.cancel()
        if (_controlsState.value.shouldAutoHideControls()) {
            autoHideJob = scope.launch {
                delay(durationMs)
                if (_controlsState.value.shouldAutoHideControls()) {
                    hideControls()
                }
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

    /** Open the dedicated subtitle overlay (Language → Options → Sync). */
    fun showSubtitles() {
        showOverlay(ActiveOverlay.SUBTITLES)
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
        if (!_controlsState.value.canSwitchPlayer) return
        showOverlay(ActiveOverlay.SWITCH_PLAYER)
    }

    fun setPlayerSwitchAllowed(allowed: Boolean) {
        _controlsState.update {
            it.copy(
                canSwitchPlayer = allowed,
                activeOverlay = if (!allowed && it.activeOverlay == ActiveOverlay.SWITCH_PLAYER) {
                    ActiveOverlay.NONE
                } else {
                    it.activeOverlay
                },
            )
        }
    }

    private fun showOverlay(overlay: ActiveOverlay) {
        _controlsState.update { 
            it.copy(
                activeOverlay = overlay,
                lastClosedOverlay = ActiveOverlay.NONE,
                isVisible = true, 
                isFullControlsVisible = true 
            ) 
        }
        autoHideJob?.cancel()
    }

    fun hideOverlay() {
        _controlsState.update {
            val closed = it.activeOverlay
            it.copy(
                activeOverlay = ActiveOverlay.NONE,
                lastClosedOverlay = if (closed != ActiveOverlay.NONE) closed else it.lastClosedOverlay,
                isVisible = true,
                isFullControlsVisible = true,
            )
        }
        resetAutoHideTimer()
    }

    fun updateTracks(
        audio: List<UnifiedTrack>,
        subtitles: List<UnifiedTrack>,
        video: List<UnifiedTrack>,
        currentSubtitleUrl: String? = null
    ) {
        _controlsState.update { state ->
            val mergedSubs = subtitles.toMutableList()
            
            // Re-apply selection states to the cached online tracks. An online track is selected
            // if it matches the current active external subtitle URL.
            val selectedSubId = currentSubtitleUrl ?: subtitles.firstOrNull { it.isSelected }?.id
            val onlineTracks = cachedOnlineTracks.map { track ->
                track.copy(isSelected = track.id == selectedSubId)
            }
            
            // Append any online tracks that aren't already present in the list
            val existingIds = mergedSubs.map { it.id }.toSet()
            val newOnlineTracks = onlineTracks.filter { it.id !in existingIds }
            
            val activeTab = if (
                state.activeSettingsTab == SettingsTab.VIDEO &&
                !video.hasSelectableVideoQualities()
            ) {
                SettingsTab.AUDIO
            } else {
                state.activeSettingsTab
            }

            state.copy(
                audioTracks = audio,
                subtitleTracks = mergedSubs + newOnlineTracks,
                videoTracks = video,
                activeSettingsTab = activeTab,
            )
        }
    }
    
    fun setPlaybackSpeed(speed: Float) {
        engine?.setPlaybackSpeed(speed)
        _controlsState.update { it.copy(playbackSpeed = speed) }
    }
    
    fun setVideoScaling(mode: String) {
        val normalized = if (mode == "Fixed Width" || mode == "Fixed Height") "Fit" else mode
        _controlsState.update { it.copy(videoScalingMode = normalized) }
    }

    fun setVideoQuality(maxHeight: Int) {
        _controlsState.update { it.copy(videoQualityMaxHeight = maxHeight.coerceAtLeast(0)) }
    }

    fun updateCapabilities(
        capabilities: PlaybackCapabilities,
        currentVideoHeight: Int,
        qualityMaxHeight: Int,
    ) {
        _controlsState.update { state ->
            val availableTabs = buildList {
                if (capabilities.qualityAvailable && state.videoTracks.hasSelectableVideoQualities()) add(SettingsTab.VIDEO)
                if (state.audioTracks.size > 1 || capabilities.audioBoostAvailable) add(SettingsTab.AUDIO)
                if (capabilities.speedAvailable) add(SettingsTab.SPEED)
                if (capabilities.scalingAvailable) add(SettingsTab.SCALING)
            }
            state.copy(
                capabilities = capabilities,
                currentVideoHeight = currentVideoHeight.coerceAtLeast(0),
                videoQualityMaxHeight = qualityMaxHeight.coerceAtLeast(0),
                activeSettingsTab = state.activeSettingsTab.takeIf { it in availableTabs }
                    ?: availableTabs.firstOrNull()
                    ?: SettingsTab.AUDIO,
            )
        }
    }

    fun resetSessionSettings(defaultQualityMaxHeight: Int) {
        _controlsState.update {
            it.copy(
                playbackSpeed = 1f,
                videoScalingMode = "Fit",
                videoQualityMaxHeight = defaultQualityMaxHeight.coerceAtLeast(0),
                isLooping = false,
                capabilities = PlaybackCapabilities(),
                currentVideoHeight = 0,
            )
        }
    }

    fun loadExternalSubtitle(
        url: String,
        headers: Map<String, String>? = null,
        userAdded: Boolean = false,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val resolver = subtitleTargetResolver
        val resolved = if (resolver != null) {
            val target = resolver(url, headers)
            if (target == null) {
                clearSubtitle()
                onResult?.invoke(false)
                return
            }
            target
        } else {
            null
        }
        if (subtitleManager == null) {
            subtitleManager = SubtitleManager(viewModelScope) { text ->
                _controlsState.update { it.copy(currentSubtitleText = text) }
            }
        }
        subtitleManager?.setPlayer { engine?.currentPosition ?: 0L }
        subtitleManager?.setOffset(_controlsState.value.subtitleDelayMs)
        subtitleManager?.loadSubtitle(
            url = resolved?.fetchUrl ?: url,
            headers = if (resolved != null) resolved.headers else headers,
            enforcePageNetworkPolicy = enforcePageSubtitleNetworkPolicy || userAdded,
            allowedPrivateOrigins = if (userAdded) {
                PageMediaProxyRouter.withUserChosenOrigin(allowedPrivateSubtitleOrigins, url)
            } else {
                allowedPrivateSubtitleOrigins
            },
            trustedLocalProxyOrigin = resolved?.trustedLocalProxyOrigin
                ?: trustedLocalSubtitleProxyOrigin,
            onResult = onResult,
        )
    }

    fun clearSubtitle() {
        subtitleManager?.disable()
        _controlsState.update { it.copy(currentSubtitleText = null) }
    }

    fun skipCurrentSegment() {
        val segment = _controlsState.value.activeSkipSegment ?: return
        lastSkippedSegment = segment
        performSegmentSkip(segment, _controlsState.value.duration)
        _controlsState.update { it.copy(activeSkipSegment = null) }
        resetAutoHideTimer()
    }

    private fun performSegmentSkip(segment: SkipSegment, durationMs: Long) {
        val endHandler = onEndSegmentSkipped
        if (skipSegmentEndsPlayback(segment, durationMs) && endHandler != null) {
            endHandler()
        } else {
            engine?.seekTo(skipTargetMs(segment, durationMs))
        }
    }

    fun setSkipButtonFocused(focused: Boolean) {
        _controlsState.update { it.copy(isSkipButtonFocused = focused) }
    }

    fun detach() {
        autoHideJob?.cancel()
        progressUpdateJob?.cancel()
        commitSeekJob?.cancel()
        onlineSubtitleJob?.cancel()
        skipSegmentsJob?.cancel()
        chapterOsdJob?.cancel()
        cachedOnlineTracks = emptyList()
        trustedLocalSubtitleProxyOrigin = null
        subtitleTargetResolver = null
        onEndSegmentSkipped = null
        engine = null
    }
}
