package com.playbridge.sender.cast.remote

/** Pure reducer: the receiver snapshot is never mutated by phone intent. */
internal class RemoteStateReducer(private val clock: () -> Long) {
    var state = RemoteUiState()
        private set
    private var optimistic = RemoteOptimisticState(clock)
    private var observation = RemoteObservation()
    private var stoppedContextSequence: Long? = null
    var intentVersion = 0L
        private set
    private var sentPlaybackSequence = 0L
    private var sentAt = 0L

    fun observe(snapshot: RemoteReceiverSnapshot): RemoteUiState {
        var incoming = snapshot.state
        val old = state
        val identityChanged = incoming.sessionId != old.sessionId || incoming.context != old.context && stoppedContextSequence == null ||
            incoming.playback?.mediaId != old.playback?.mediaId || incoming.playback?.title != old.playback?.title || incoming.queue?.currentIndex != old.queue?.currentIndex
        val commandFailed = snapshot.observation.controlFailureVersion > observation.controlFailureVersion
        val reset = identityChanged || !incoming.connection.ready || incoming.playback?.reportedState == RemotePlaybackState.Error
        if (reset || commandFailed) { optimistic = optimistic.clear(); intentVersion++ }
        if (incoming.sessionId != old.sessionId || !incoming.connection.ready) stoppedContextSequence = null
        if (stoppedContextSequence != null) {
            if (snapshot.observation.contextSequence > stoppedContextSequence!!) stoppedContextSequence = null
            else incoming = incoming.copy(context = RemoteContext.Idle)
        }
        if (!reset && snapshot.observation.playbackSequence > observation.playbackSequence && snapshot.observation.playbackSequence > sentPlaybackSequence && snapshot.observation.playbackAtMs > sentAt) {
            incoming.playback?.let { optimistic = optimistic.reconcile(it.reportedPositionMs, it.reportedPlaying) }
        }
        observation = snapshot.observation
        val browserBecameActive = incoming.context == RemoteContext.Browser && incoming.playback?.active == true &&
            (old.context != RemoteContext.Browser || old.playback?.active != true)
        val preferred = if (browserBecameActive) RemoteInputMode.Context else old.preferences.modesByContext[incoming.context] ?: RemoteInputMode.Context
        val preferences = if (browserBecameActive) old.preferences.copy(modesByContext = old.preferences.modesByContext + (RemoteContext.Browser to RemoteInputMode.Context)) else old.preferences
        val inputMode = if (identityChanged || browserBecameActive) preferred else old.inputMode
        state = incoming.copy(
            inputMode = inputMode,
            preferences = preferences, sheet = if (reset || old.sheet == RemoteSheet.BrowserMore && inputMode != old.inputMode) null else old.sheet,
            pending = if (reset) RemotePendingState() else old.pending,
            notice = if (reset) null else old.notice,
        )
        return display()
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
        return display()
    }
    fun failure(version: Long, message: String): RemoteUiState {
        if (version == intentVersion) optimistic = optimistic.clear()
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
        optimistic = optimistic.clear(); intentVersion++
        state = state.copy(pending = RemotePendingState(), sheet = null, notice = null)
        if (resetPresentation) {
            val mode = if (state.context == RemoteContext.Browser && state.playback?.active != true) RemoteInputMode.Pointer else RemoteInputMode.Context
            state = state.copy(inputMode = mode, preferences = RemotePreferences())
        }
        return display()
    }
    fun local(action: RemoteAction): RemoteUiState {
        state = when (action) {
            is RemoteAction.SelectInputMode -> state.copy(inputMode = action.mode, sheet = state.sheet.takeUnless { it == RemoteSheet.BrowserMore && action.mode != state.inputMode }, preferences = state.preferences.copy(modesByContext = state.preferences.modesByContext + (state.context to action.mode)))
            is RemoteAction.OpenSheet -> state.copy(sheet = action.sheet)
            RemoteAction.CloseSheet -> state.copy(sheet = null)
            is RemoteAction.SetTimeDisplay -> state.copy(preferences = state.preferences.copy(timeDisplay = action.display))
            is RemoteAction.MarkGestureLearned -> state.copy(preferences = state.preferences.copy(learnedGestures = state.preferences.learnedGestures + action.gesture))
            RemoteAction.RequestWholeTvControl -> state.copy(sheet = RemoteSheet.WholeTvControl)
            else -> state
        }
        return state
    }
    fun subtitlePending(token: String, pending: Boolean): RemoteUiState {
        val requests = state.pending.subtitleRequests
        state = state.copy(pending = state.pending.copy(subtitleRequests = if (pending) requests + token else requests - token))
        return state
    }
    private fun display(): RemoteUiState {
        state = state.copy(
            playback = state.playback?.let {
                it.copy(displayedPositionMs = optimistic.position(it.reportedPositionMs), displayedState =
                    if (optimistic.hasPendingPlaying) { if (optimistic.playing(it.reportedPlaying)) RemotePlaybackState.Playing else RemotePlaybackState.Paused } else it.reportedState)
            },
            pending = state.pending.copy(seek = optimistic.hasPendingSeek, playing = optimistic.hasPendingPlaying),
        )
        return state
    }
}
