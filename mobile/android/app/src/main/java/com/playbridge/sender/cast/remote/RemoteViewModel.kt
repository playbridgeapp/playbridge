package com.playbridge.sender.cast.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class RemoteViewModel(
    private val registry: RemoteControllerRegistry,
    private val router: RemoteInputRouter,
    clock: () -> Long = ::remoteMonotonicTime,
    scope: CoroutineScope? = null,
    private val presentationPreferences: RemotePresentationPreferenceStore? = null,
    private val queueProgressSource: RemoteQueueProgressSource? = null,
    private val tvRemote: TvRemoteChannel? = null,
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private val reducer = RemoteStateReducer(clock)
    private val mutableState = MutableStateFlow(RemoteUiState())
    val state = mutableState.asStateFlow()
    private data class EffectEntry(val session: RemoteSessionId?, val media: RemoteMediaId?, val activation: Long, val effect: RemoteEffect)
    private val effectChannel = Channel<EffectEntry>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow().filter { active && it.activation == activation && (it.effect is RemoteEffect.Navigate || it.session == state.value.sessionId && it.media == state.value.playback?.mediaId) }.map { it.effect }
    private var expiry: Job? = null
    private val commands = mutableSetOf<Job>()
    private var sequence = 0L
    private var active = true
    private var activation = 0L

    init {
        presentationPreferences?.let { reducer.restorePreferences(it.load()) }
        mutableState.value = reducer.observe(registry.controller.value?.snapshot?.value ?: RemoteReceiverSnapshot())
        presentationPreferences?.let { store ->
            this.scope.launch {
                store.state.collect {
                    // Input modes remain session-local; settings changes only replace persisted fields.
                    mutableState.value = reducer.restorePreferences(store.load().copy(modesByContext = state.value.preferences.modesByContext))
                }
            }
        }
        this.scope.launch {
            registry.controller.collectLatest { controller ->
                if (controller == null) mutableState.value = reducer.observe(RemoteReceiverSnapshot())
                else controller.snapshot.collect { snapshot ->
                    if (registry.controller.value === controller) mutableState.value = reducer.observe(snapshot)
                }
            }
        }
        tvRemote?.let { channel ->
            this.scope.launch {
                channel.state.collect { snapshot ->
                    mutableState.value = reducer.applyChannel(snapshot)
                }
            }
        }
        queueProgressSource?.let { source ->
            this.scope.launch {
                state.map { it.queue.progressKeys() }.distinctUntilChanged().collectLatest { keys ->
                    mutableState.value = reducer.queueProgress(emptyMap())
                    source.observe(keys).collect { progress ->
                        mutableState.value = reducer.queueProgress(progress)
                    }
                }
            }
        }
    }

    fun activate() {
        active = true
        tvRemote?.setRemoteVisible(true)
        tvRemote?.let { mutableState.value = reducer.applyChannel(it.state.value) }
    }
    fun deactivate() {
        tvRemote?.let { channel ->
            channel.setRemoteVisible(false)
            reducer.applyChannel(channel.state.value)
        }
        val controller = registry.controller.value
        scope.launch(start = CoroutineStart.UNDISPATCHED) { controller?.execute(RemoteAction.PointerGestureEnd) }
        active = false
        activation++
        commands.toList().forEach { it.cancel() }; commands.clear()
        expiry?.cancel(); expiry = null
        mutableState.value = reducer.clear(resetPresentation = true)
    }

    fun dispatch(action: RemoteAction) {
        if (local(action)) return
        val ticket = prepare(action) ?: return
        val job = scope.launch { submit(ticket) }
        commands += job
        job.invokeOnCompletion { commands.remove(job) }
    }

    suspend fun execute(action: RemoteAction): RemoteCommandResult {
        if (local(action)) return RemoteCommandResult.Submitted
        val ticket = prepare(action) ?: return RemoteCommandResult.Failed("No active remote session")
        return submit(ticket)
    }

    private fun local(action: RemoteAction): Boolean {
        if (!active) return false
        val effect = when (action) {
            RemoteAction.BackToOrigin -> RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.Origin)
            RemoteAction.OpenConnections -> RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.Connections)
            RemoteAction.UnlinkWebsite -> RemoteEffect.UnlinkWebsite
            RemoteAction.RetryConnection -> RemoteEffect.RetryConnection
            else -> null
        }
        if (effect != null) { emit(effect); return true }
        if (action is RemoteAction.SelectInputMode || action is RemoteAction.OpenSheet || action == RemoteAction.CloseSheet ||
            action is RemoteAction.SetTimeDisplay || action is RemoteAction.SetButtonPad || action is RemoteAction.MarkGestureLearned || action == RemoteAction.RequestWholeTvControl) {
            mutableState.value = reducer.local(action)
            if (action is RemoteAction.SetTimeDisplay || action is RemoteAction.SetButtonPad || action is RemoteAction.MarkGestureLearned) persistPreferenceAction(action)
            return true
        }
        return false
    }

    private fun persistPreferenceAction(action: RemoteAction) {
        presentationPreferences?.let { store ->
            val updated = store.update { it.withPresentationAction(action) }
            mutableState.value = reducer.restorePreferences(updated.copy(modesByContext = state.value.preferences.modesByContext))
        }
    }

    private data class Ticket(val controller: RemoteController, val action: RemoteAction, val version: Long, val media: RemoteMediaId?, val token: String, val subtitleConfirmation: Boolean, val activation: Long, val learnedGesture: String?)
    private fun prepare(action: RemoteAction): Ticket? {
        if (!active) return null
        val controller = registry.controller.value ?: return null
        if (state.value.sessionId != controller.sessionId) mutableState.value = reducer.observe(controller.snapshot.value)
        val playback = state.value.playback
        val resolved = when (action) {
            RemoteAction.TogglePlayPause -> RemoteAction.SetPlaying(playback?.displayedPlaying != true)
            is RemoteAction.SeekBy -> RemoteAction.SeekTo(relativeRemoteSeek(playback?.displayedPositionMs ?: 0L, action.deltaMs, playback?.durationMs ?: 0L))
            is RemoteAction.SeekTo -> action.copy(positionMs = boundedSeek(action.positionMs, playback?.durationMs ?: 0L))
            else -> action
        }
        mutableState.value = reducer.begin(resolved)
        val learnedGesture = when (action) {
            RemoteAction.TogglePlayPause -> RemoteLearnedGesture.TAP
            is RemoteAction.SeekTo -> action.learnedGesture
            is RemoteAction.AdjustVolume -> action.learnedGesture
            else -> null
        }
        val ticket = Ticket(controller, resolved, reducer.intentVersion, playback?.mediaId, (++sequence).toString(), state.value.connection.ready && state.value.capabilities.tracks.addResource == Support.Supported, activation, learnedGesture)
        if (resolved is RemoteAction.AddSubtitle) {
            mutableState.value = reducer.subtitlePending(ticket.token, true)
            if (resolved.legacyUrlOnly || ticket.subtitleConfirmation) mutableState.value = reducer.local(RemoteAction.CloseSheet)
        }
        if (state.value.pending.seek || state.value.pending.playing) startExpiry()
        return ticket
    }

    private suspend fun submit(ticket: Ticket): RemoteCommandResult {
        if (!active || activation != ticket.activation || registry.controller.value !== ticket.controller || state.value.playback?.mediaId != ticket.media || ticket.controller.snapshot.value.state.playback?.mediaId != ticket.media) return RemoteCommandResult.Failed("Remote session changed")
        val result = try { router.execute(ticket.controller, ticket.action, ticket.media) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RemoteCommandResult.Failed("Could not send remote command") }
        if (!active || activation != ticket.activation || registry.controller.value !== ticket.controller || state.value.sessionId != ticket.controller.sessionId || state.value.playback?.mediaId != ticket.media || ticket.controller.snapshot.value.state.playback?.mediaId != ticket.media) return result
        val subtitle = ticket.action as? RemoteAction.AddSubtitle
        if (subtitle != null) mutableState.value = reducer.subtitlePending(ticket.token, false)
        // Only the command families tracked by stage 1 can invalidate a playback intent.
        val playbackFailure = when (ticket.action) {
            is RemoteAction.SeekTo, is RemoteAction.SetPlaying, RemoteAction.StopPlayback, is RemoteAction.SetLoop,
            is RemoteAction.Browser, is RemoteAction.SetBrowserFullscreen -> true
            else -> false
        }
        val failureVersion = if (playbackFailure) ticket.version else -1L
        when (result) {
            RemoteCommandResult.Submitted, RemoteCommandResult.Confirmed -> {
                ticket.learnedGesture?.let {
                    mutableState.value = reducer.local(RemoteAction.MarkGestureLearned(it))
                    persistPreferenceAction(RemoteAction.MarkGestureLearned(it))
                }
                mutableState.value = reducer.browserCommandAccepted(ticket.action)
                if (ticket.action == RemoteAction.StopPlayback && state.value.receiver?.profile != RemoteProfile.External) mutableState.value = reducer.stopped()
                if (subtitle != null && !ticket.subtitleConfirmation && !subtitle.legacyUrlOnly && state.value.sheet == RemoteSheet.AddSubtitle) mutableState.value = reducer.local(RemoteAction.CloseSheet)
            }
            is RemoteCommandResult.Failed -> {
                mutableState.value = reducer.failure(failureVersion, result.message)
                emit(RemoteEffect.Notice(if (subtitle != null && !subtitle.legacyUrlOnly && !ticket.subtitleConfirmation) "Could not add subtitle. Check the TV connection or update the receiver." else result.message, subtitle != null && !subtitle.legacyUrlOnly))
            }
            is RemoteCommandResult.Unsupported -> {
                mutableState.value = reducer.failure(failureVersion, result.reason)
                if (subtitle != null && !subtitle.legacyUrlOnly) {
                    // Stage 1 closed URL-only submissions accepted by the socket even on a
                    // receiver with no subtitle handler. Keep that presentation, while the
                    // typed result remains Unsupported and no unsupported command is sent.
                    if (state.value.connection.ready && subtitle.resource.headers.isEmpty() && !ticket.subtitleConfirmation) {
                        if (state.value.sheet == RemoteSheet.AddSubtitle) mutableState.value = reducer.local(RemoteAction.CloseSheet)
                    } else emit(RemoteEffect.Notice("Could not add subtitle. Check the TV connection or update the receiver.", true))
                }
            }
        }
        // Stage 1 opens these phone managers even if their refresh query cannot be sent.
        when (ticket.action) {
            RemoteAction.ManageUserScripts -> emit(RemoteEffect.OpenManager(RemoteEffect.OpenManager.Manager.UserScripts))
            RemoteAction.ManageUserAgent -> emit(RemoteEffect.OpenManager(RemoteEffect.OpenManager.Manager.UserAgent))
            else -> Unit
        }
        return result
    }

    private fun startExpiry() {
        if (expiry?.isActive == true) return
        expiry = scope.launch {
            while (active && (state.value.pending.seek || state.value.pending.playing)) {
                delay(100L)
                mutableState.value = reducer.expire()
            }
        }
    }
    private fun emit(effect: RemoteEffect) {
        effectChannel.trySend(EffectEntry(state.value.sessionId, state.value.playback?.mediaId, activation, effect))
    }
}
