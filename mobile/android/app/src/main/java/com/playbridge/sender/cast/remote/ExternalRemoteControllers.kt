package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.TargetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Adapters submit to the singleton owner. Submitted never claims receiver acknowledgement. */
open class ExternalProfileRemoteController(
    override val sessionId: RemoteSessionId,
    private val target: RemoteExternalTarget,
    private val transport: ExternalRemoteTransport,
    scope: CoroutineScope,
    private val current: () -> Boolean = { true },
) : RemoteController {
    private fun decorate(raw: ExternalRemoteSnapshot): RemoteReceiverSnapshot {
        if (raw.identity !== target.identity || raw.receiverSnapshot.state.receiver?.endpoint != target.receiver.endpoint) {
            return RemoteReceiverSnapshot(RemoteUiState(sessionId = sessionId, receiver = target.receiver, context = RemoteContext.Idle))
        }
        val snapshot = raw.receiverSnapshot
        return snapshot.copy(state = snapshot.state.copy(sessionId = sessionId, playback = snapshot.state.playback?.let {
            it.copy(mediaId = RemoteMediaId(sessionId, null, null, snapshot.observation.mediaGeneration))
        }))
    }
    override val snapshot: StateFlow<RemoteReceiverSnapshot> = transport.snapshot.map(::decorate)
        .stateIn(scope, SharingStarted.Eagerly, decorate(transport.snapshot.value))

    override suspend fun execute(action: RemoteAction): RemoteCommandResult =
        execute(action, decorate(transport.snapshot.value).state.playback?.mediaId)

    override suspend fun execute(action: RemoteAction, expectedMedia: RemoteMediaId?): RemoteCommandResult {
        val raw = transport.snapshot.value
        val generation = raw.receiverSnapshot.observation.mediaGeneration
        if (!current() || raw.identity !== target.identity || !transport.isCurrent(target.identity, generation)) return RemoteCommandResult.Failed("Remote session changed")
        val state = decorate(raw).state
        if (state.playback?.mediaId != expectedMedia) return RemoteCommandResult.Failed("Remote media changed")
        val caps = state.capabilities
        fun unsupported() = RemoteCommandResult.Unsupported("This receiver does not support this remote action")
        val command = when (action) {
            is RemoteAction.SetPlaying -> if (caps.transport.playPause == Support.Supported) action else return unsupported()
            RemoteAction.TogglePlayPause -> if (caps.transport.playPause == Support.Supported) RemoteAction.SetPlaying(state.playback?.displayedPlaying != true) else return unsupported()
            is RemoteAction.SeekTo -> {
                if (caps.transport.seekAbsolute != Support.Supported || state.context != RemoteContext.Player || state.playback?.isLive == true) return unsupported()
                RemoteAction.SeekTo(boundedSeek(action.positionMs, state.playback?.durationMs ?: 0L))
            }
            is RemoteAction.SeekBy -> {
                if (caps.transport.seekRelative != Support.Supported || state.context != RemoteContext.Player || state.playback?.isLive == true) return unsupported()
                RemoteAction.SeekTo(relativeRemoteSeek(state.playback?.displayedPositionMs ?: 0L, action.deltaMs, state.playback?.durationMs ?: 0L))
            }
            RemoteAction.StopPlayback -> if (caps.transport.stop == Support.Supported) action else return unsupported()
            is RemoteAction.AdjustVolume -> if (caps.volume.adjust == Support.Supported && action.steps != 0 && action.steps in -100..100) action else return unsupported()
            is RemoteAction.SetVolume -> if (caps.volume.absolute == Support.Supported) RemoteAction.SetVolume(action.percent.coerceIn(0, 100)) else return unsupported()
            is RemoteAction.Key -> if (action.key in caps.keys && target.kind == TargetKind.ROKU) action else return unsupported()
            // External status is already owned/observed; entry never starts another poller or sends a native query.
            RemoteAction.Resync -> return RemoteCommandResult.Submitted
            else -> return unsupported()
        }
        if (!state.connection.ready) return RemoteCommandResult.Failed("Receiver is not ready")
        return transport.submit(target.identity, generation, command)
    }
}

class GoogleCastRemoteController(session: RemoteSessionId, target: RemoteExternalTarget, transport: ExternalRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : ExternalProfileRemoteController(session, target, transport, scope, current)
class RokuRemoteController(session: RemoteSessionId, target: RemoteExternalTarget, transport: ExternalRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : ExternalProfileRemoteController(session, target, transport, scope, current)
class DlnaRemoteController(session: RemoteSessionId, target: RemoteExternalTarget, transport: ExternalRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : ExternalProfileRemoteController(session, target, transport, scope, current)
class WebReceiverRemoteController(session: RemoteSessionId, target: RemoteExternalTarget, transport: ExternalRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : ExternalProfileRemoteController(session, target, transport, scope, current)
