package com.playbridge.sender.cast.remote

/** Pure reducer: the receiver snapshot is never mutated by phone intent. */
internal class RemoteStateReducer(private val clock: () -> Long) {
    var state = RemoteUiState()
        private set
    private var optimistic = RemoteOptimisticState(clock)
    private var observation = RemoteObservation()
    private val volumeOptimism = RemoteVolumeOptimism(clock)
    private var stoppedContextSequence: Long? = null
    var intentVersion = 0L
        private set
    private var sentPlaybackSequence = 0L
    private var sentAt = 0L
    /** Last fullscreen command the TV accepted. A receiver report replaces it; a null report does not. */
    private var localBrowserFullscreen = false
    /** Receiver capabilities before the Full control overlay. Disconnect restores this snapshot. */
    private var baseCapabilities = RemoteCapabilities()
    private var channelState: TvRemoteChannelState? = null

    fun observe(snapshot: RemoteReceiverSnapshot): RemoteUiState {
        var incoming = snapshot.state
        val old = state
        if (!incoming.reportsVolume()) volumeOptimism.clear()
        if (incoming.receiverProtocol.volumeRevision != old.receiverProtocol.volumeRevision ||
            incoming.nativePolicy == null && snapshot.observation.sequence > observation.sequence) volumeOptimism.clear()
        val identityChanged = incoming.sessionId != old.sessionId || incoming.context != old.context && stoppedContextSequence == null ||
            incoming.playback?.mediaId != old.playback?.mediaId || incoming.playback?.mediaKind != old.playback?.mediaKind || incoming.playback?.title != old.playback?.title || incoming.queue?.currentIndex != old.queue?.currentIndex
        val commandFailed = snapshot.observation.controlFailureVersion > observation.controlFailureVersion
        val reset = identityChanged || !incoming.connection.ready || incoming.playback?.reportedState == RemotePlaybackState.Error
        if (reset || commandFailed) { optimistic = optimistic.clear(); volumeOptimism.clear(); intentVersion++ }
        if (incoming.sessionId != old.sessionId || !incoming.connection.ready) stoppedContextSequence = null
        if (stoppedContextSequence != null) {
            if (snapshot.observation.contextSequence > stoppedContextSequence!!) stoppedContextSequence = null
            else incoming = incoming.copy(context = RemoteContext.Idle)
        }
        if (!reset && snapshot.observation.playbackSequence > observation.playbackSequence && snapshot.observation.playbackSequence > sentPlaybackSequence && snapshot.observation.playbackAtMs > sentAt) {
            incoming.playback?.let { optimistic = optimistic.reconcile(it.reportedPositionMs, it.reportedPlaying) }
        }
        adoptBrowserFullscreen(incoming, old)
        observation = snapshot.observation
        val browserBecameActive = incoming.context == RemoteContext.Browser && incoming.playback?.active == true &&
            (old.context != RemoteContext.Browser || old.playback?.active != true)
        val preferred = if (browserBecameActive) RemoteInputMode.Context else old.preferences.modesByContext[incoming.context] ?: RemoteInputMode.Context
        val preferences = if (browserBecameActive) old.preferences.copy(modesByContext = old.preferences.modesByContext + (RemoteContext.Browser to RemoteInputMode.Context)) else old.preferences
        val inputMode = if (identityChanged || browserBecameActive) preferred else old.inputMode
        baseCapabilities = incoming.capabilities
        // Up next stays open as the queue moves to another item on the same connection.
        val keepsQueue = old.sheet == RemoteSheet.Queue && incoming.sessionId == old.sessionId && incoming.connection.ready
        state = incoming.copy(
            inputMode = inputMode,
            preferences = preferences,
            sheet = if (reset && !keepsQueue || old.sheet == RemoteSheet.BrowserMore && inputMode != old.inputMode) null else old.sheet,
            pending = if (reset) RemotePendingState() else old.pending,
            notice = if (reset) null else old.notice,
        )
        return displayWithKeyboardRoute(old)
    }

    fun begin(action: RemoteAction): RemoteUiState {
        when (action) {
            is RemoteAction.SeekTo -> {
                optimistic = optimistic.seek(action.positionMs, state.playback?.durationMs ?: 0L)
                intentVersion++; sentPlaybackSequence = observation.playbackSequence; sentAt = clock()
            }
            is RemoteAction.SetPlaying -> {
                optimistic = optimistic.play(action.playing)
                intentVersion++; sentPlaybackSequence = observation.playbackSequence; sentAt = clock()
            }
            else -> Unit
        }
        if (action is RemoteAction.AdjustVolume || action is RemoteAction.SetVolume || action == RemoteAction.Key(RemoteKey.Mute)) {
            volumeOptimism.begin(action, state.capabilities.volume, state.volumeStep(), state.reportsVolume())
            intentVersion++
        }
        return display()
    }
    fun failure(version: Long, message: String): RemoteUiState {
        if (version == intentVersion) { optimistic = optimistic.clear(); volumeOptimism.clear() }
        state = state.copy(notice = RemoteNotice(message))
        return display()
    }
    fun stopped(): RemoteUiState {
        stoppedContextSequence = observation.contextSequence
        optimistic = optimistic.clear()
        state = state.copy(context = RemoteContext.Idle, sheet = null)
        return display()
    }
    fun expire(): RemoteUiState { optimistic = optimistic.expire(); return display() }
    fun clear(resetPresentation: Boolean = false): RemoteUiState {
        optimistic = optimistic.clear(); volumeOptimism.clear(); intentVersion++
        state = state.copy(pending = RemotePendingState(), sheet = null, notice = null)
        if (resetPresentation) {
            val mode = if (state.context == RemoteContext.Browser && state.playback?.active != true) RemoteInputMode.Pointer else RemoteInputMode.Context
            state = state.copy(inputMode = mode, preferences = state.preferences.copy(modesByContext = RemotePreferences().modesByContext))
        }
        return display()
    }
    fun restorePreferences(preferences: RemotePreferences): RemoteUiState {
        state = state.copy(preferences = preferences)
        return state
    }
    fun applyChannel(channel: TvRemoteChannelState?): RemoteUiState {
        val old = state
        if (channel?.connected != channelState?.connected || channel?.matchedEndpoint != channelState?.matchedEndpoint) volumeOptimism.clear()
        channelState = channel
        return displayWithKeyboardRoute(old)
    }
    fun local(action: RemoteAction): RemoteUiState {
        state = when (action) {
            is RemoteAction.SelectInputMode -> state.copy(inputMode = action.mode, sheet = state.sheet.takeUnless { it == RemoteSheet.BrowserMore && action.mode != state.inputMode }, preferences = state.preferences.copy(modesByContext = state.preferences.modesByContext + (state.context to action.mode)))
            is RemoteAction.OpenSheet -> state.copy(sheet = action.sheet)
            RemoteAction.CloseSheet -> state.copy(sheet = null)
            is RemoteAction.SetTimeDisplay -> state.copy(preferences = state.preferences.copy(timeDisplay = action.display))
            is RemoteAction.SetButtonPad -> state.copy(preferences = state.preferences.copy(buttonPad = action.enabled))
            is RemoteAction.MarkGestureLearned -> state.copy(preferences = state.preferences.copy(learnedGestures = state.preferences.learnedGestures + action.gesture))
            RemoteAction.RequestWholeTvControl -> state.copy(sheet = RemoteSheet.WholeTvControl)
            else -> state
        }
        return state
    }
    /** Applies a browser command only after the transport accepted it. */
    fun browserCommandAccepted(action: RemoteAction): RemoteUiState {
        when (action) {
            is RemoteAction.SetBrowserFullscreen -> localBrowserFullscreen = action.fullscreen
            is RemoteAction.Key -> if (action.key == RemoteKey.Back && localBrowserFullscreen) localBrowserFullscreen = false
            is RemoteAction.Browser -> if (action.action == BrowserAction.Forward || action.action == BrowserAction.Reload) localBrowserFullscreen = false
            is RemoteAction.OpenTvBrowser -> localBrowserFullscreen = false
            else -> return state
        }
        return display()
    }
    fun subtitlePending(token: String, pending: Boolean): RemoteUiState {
        val requests = state.pending.subtitleRequests
        state = state.copy(pending = state.pending.copy(subtitleRequests = if (pending) requests + token else requests - token))
        return state
    }
    private fun adoptBrowserFullscreen(incoming: RemoteUiState, previous: RemoteUiState) {
        val reported = incoming.browser?.fullscreen
        if (incoming.context == RemoteContext.Browser && incoming.connection.ready && reported != null) localBrowserFullscreen = reported
        else if (browserFullscreenResets(incoming, previous)) localBrowserFullscreen = false
    }

    private fun browserFullscreenResets(incoming: RemoteUiState, previous: RemoteUiState): Boolean {
        if (incoming.context != RemoteContext.Browser || !incoming.connection.ready) return true
        if (previous.sessionId != null && incoming.sessionId != previous.sessionId) return true
        val previousEndpoint = previous.receiver?.endpoint
        if (previousEndpoint != null && incoming.receiver?.endpoint != previousEndpoint) return true
        return pageUrlChanged(previous.browser?.url, incoming.browser?.url)
    }

    /** A missing URL is not a page change. Native TV status does not send one. */
    private fun pageUrlChanged(previous: String?, next: String?): Boolean {
        val before = previous?.trim()?.takeIf { it.isNotEmpty() }
        val after = next?.trim()?.takeIf { it.isNotEmpty() }
        return before != null && after != null && before != after
    }

    /** Closing drops local drafts and disposes the ATV worker; reopening establishes a new baseline. */
    private fun displayWithKeyboardRoute(previous: RemoteUiState): RemoteUiState {
        display()
        val advertisementOnly = previous.sessionId == state.sessionId && previous.context == state.context &&
            previous.connection == state.connection && previous.fullControl?.connected == state.fullControl?.connected &&
            (previous.receiverProtocol.descriptor != state.receiverProtocol.descriptor || previous.receiverProtocol.malformedV1 != state.receiverProtocol.malformedV1)
        if (previous.sheet == RemoteSheet.Keyboard && previous.keyboardRoute() != state.keyboardRoute() && !advertisementOnly) {
            state = state.copy(sheet = null)
        }
        if (advertisementOnly && state.nativePolicy != null) {
            val layout = resolveRemoteLayout(state, RemoteViewport())
            val supported = when (state.sheet) {
                RemoteSheet.More -> layout.tracks.more
                RemoteSheet.Subtitles -> layout.tracks.subtitles
                RemoteSheet.AddSubtitle -> state.capabilities.tracks.addUrl == Support.Supported || state.capabilities.tracks.addResource == Support.Supported
                RemoteSheet.Queue -> layout.showQueue
                RemoteSheet.BrowserMore -> listOf(
                    RemoteAction.Browser(BrowserAction.CycleSource), RemoteAction.Browser(BrowserAction.Unmute),
                    RemoteAction.ManageUserScripts, RemoteAction.ManageUserAgent,
                ).any(state::receiverActionSupported)
                // The keyboard retains its draft and captured route; its existing
                // canReplace/canEnter gates disable removed receiver operations.
                else -> true
            }
            if (!supported) state = state.copy(sheet = null)
        }
        return state
    }

    private fun display(): RemoteUiState {
        if (state.context != RemoteContext.Browser || !state.connection.ready) localBrowserFullscreen = false
        val showFullscreen = state.context == RemoteContext.Browser && state.connection.ready && localBrowserFullscreen
        state = state.copy(
            playback = state.playback?.let {
                it.copy(displayedPositionMs = optimistic.position(it.reportedPositionMs), displayedState =
                    if (optimistic.hasPendingPlaying) { if (optimistic.playing(it.reportedPlaying)) RemotePlaybackState.Playing else RemotePlaybackState.Paused } else it.reportedState)
            },
            pending = state.pending.copy(seek = optimistic.hasPendingSeek, playing = optimistic.hasPendingPlaying),
            browser = state.browser?.copy(fullscreen = showFullscreen),
        )
        state = projectFullControl(state, baseCapabilities, channelState)
        if (!state.fullControlSupports(RemoteAction.AdjustVolume(1))) {
            state = state.copy(capabilities = state.capabilities.copy(volume = volumeOptimism.display(baseCapabilities.volume)))
        }
        state = state.copy(pending = state.pending.copy(volume = volumeOptimism.pending))
        return state
    }
}
