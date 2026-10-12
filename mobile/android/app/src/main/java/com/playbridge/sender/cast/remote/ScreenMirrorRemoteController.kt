package com.playbridge.sender.cast.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ScreenMirrorRemoteController(
    private val delegate: RemoteController,
    private val transport: NativeRemoteTransport,
    scope: CoroutineScope,
    private val current: () -> Boolean = { true },
) : RemoteController {
    override val sessionId: RemoteSessionId get() = delegate.sessionId
    private fun decorate(snapshot: RemoteReceiverSnapshot): RemoteReceiverSnapshot {
        val state = snapshot.state
        if (state.mirror?.active != true && state.context != RemoteContext.ScreenMirror) return snapshot
        val no = Support.Unsupported
        return snapshot.copy(state = state.copy(
            context = RemoteContext.ScreenMirror, playback = null, queue = null, tracks = RemoteTracks(), settings = RemotePlayerSettings(), browser = null,
            capabilities = state.capabilities.copy(
                transport = TransportCapabilities(no, Support.Supported, no, no), pointer = PointerCapabilities(no, no, no, no, no, no, no, no),
                text = TextCapabilities(no, no, no), browser = BrowserCapabilities(fullscreen = no, launch = no, managers = no),
                queue = QueueCapabilities(no, no, no, no), tracks = TrackCapabilities(no, no, no, no), settings = emptySet(), engines = emptySet(),
            ),
        ))
    }
    override val snapshot: StateFlow<RemoteReceiverSnapshot> = delegate.snapshot.map(::decorate)
        .stateIn(scope, SharingStarted.Eagerly, decorate(delegate.snapshot.value))

    override suspend fun execute(action: RemoteAction): RemoteCommandResult {
        if (!current()) return RemoteCommandResult.Failed("Remote session changed")
        if (snapshot.value.state.context != RemoteContext.ScreenMirror) return delegate.execute(action)
        return when (action) {
            RemoteAction.StopPlayback -> if (transport.stopMirror()) RemoteCommandResult.Submitted else RemoteCommandResult.Failed("Could not stop screen mirroring")
            is RemoteAction.AdjustVolume, is RemoteAction.Key, RemoteAction.Resync, RemoteAction.PointerGestureEnd -> delegate.execute(action)
            else -> RemoteCommandResult.Unsupported("This action is unavailable while screen mirroring")
        }
    }
}
