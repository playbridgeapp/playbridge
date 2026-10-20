package com.playbridge.sender.cast.remote

import com.playbridge.sender.browser.LinkedPageCastCoordinator
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.CastSessionState
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.SessionPhase
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.connection.ExternalQueueCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Existing session state -> remote state, without reinterpreting idle as paused/player. */
internal fun externalReceiverSnapshot(
    receiver: RemoteReceiver, kind: TargetKind, session: CastSessionState, publication: ExternalRemoteObservation,
    queue: RemoteQueueState? = null, linked: LinkedWebsiteState? = null, mirror: RemoteMirrorState? = null,
): RemoteReceiverSnapshot {
    val status = publication.status
    val reported = when (status?.state) {
        PlaybackState.PLAYING -> RemotePlaybackState.Playing
        PlaybackState.PAUSED -> RemotePlaybackState.Paused
        PlaybackState.BUFFERING -> RemotePlaybackState.Buffering
        PlaybackState.ERROR -> RemotePlaybackState.Error
        else -> RemotePlaybackState.Idle
    }
    val context = if (reported in setOf(RemotePlaybackState.Playing, RemotePlaybackState.Paused, RemotePlaybackState.Buffering)) RemoteContext.Player else RemoteContext.Idle
    val caps = externalRemoteCapabilities(kind, session.capabilities, status?.volumeSupported, status?.volumeLevel, status?.muted)
    val connection = when {
        reported == RemotePlaybackState.Error -> RemoteConnection.Failed(session.error ?: "Receiver operation failed")
        session.isReadyForPlayback -> RemoteConnection.Connected(receiver.name)
        session.phase == SessionPhase.FAILED -> RemoteConnection.Failed(session.error ?: "Receiver operation failed")
        session.phase in setOf(SessionPhase.SELECTED, SessionPhase.CONNECTING) -> RemoteConnection.Connecting(receiver.name)
        else -> RemoteConnection.Disconnected
    }
    val observation = RemoteObservation(
        sequence = publication.sequence, atMs = publication.atMs, playbackSequence = publication.sequence,
        playbackAtMs = publication.atMs, mediaGeneration = publication.loadGeneration,
        contextSequence = publication.sequence, controlFailureVersion = publication.controlFailureVersion,
    )
    return RemoteReceiverSnapshot(RemoteUiState(
        receiver = receiver, context = context, connection = connection, capabilities = caps,
        playback = RemotePlayback(
            title = session.mediaTitle ?: receiver.name, reportedState = reported,
            reportedPositionMs = status?.positionMs ?: 0L, durationMs = status?.durationMs ?: 0L,
            isLive = status?.isLive == true, seekable = if (status?.isLive == true) Support.Unsupported else caps.transport.seekAbsolute,
            observedAtMs = publication.atMs,
        ), queue = queue, linkedWebsite = linked, mirror = mirror,
        // Stage 1 treats external media as video and hides queue/tracks regardless of metadata.
        // Mirror is exposed but retains the external live-player presentation until stage 3.
    ), observation)
}

class CastSessionExternalRemoteTransport(
    private val sessions: CastSessionManager,
    private val queues: ExternalQueueCoordinator,
    private val linked: LinkedPageCastCoordinator,
    scope: CoroutineScope,
) : ExternalRemoteTransport {
    private fun read(): ExternalRemoteSnapshot {
        val route = sessions.route.value as? CastSessionManager.Route.External ?: return ExternalRemoteSnapshot()
        if (sessions.activeExternalDevice.value?.endpointKey != route.endpointKey) return ExternalRemoteSnapshot()
        val target = sessions.remoteExternalTarget ?: return ExternalRemoteSnapshot()
        val receiver = RemoteReceiver(route.endpointKey, route.name, RemoteProfile.External)
        val session = sessions.sessionState.value.takeIf { it.endpointKey == route.endpointKey && it.targetKind == target.kind }
            ?: CastSessionState(endpointKey = route.endpointKey, targetKind = target.kind, phase = SessionPhase.CONNECTING, capabilities = target.capabilities)
        val website = linked.uiState.value
        val mirror = sessions.screenMirrorState.value
        return ExternalRemoteSnapshot(target, externalReceiverSnapshot(
            receiver, target.kind, session, sessions.remoteExternalPlayback.value, queues.remoteQueue.value,
            LinkedWebsiteState(website.active, website.controllerName),
            RemoteMirrorState(mirror.isActive, mirror.sessionId, mirror.phase.name, mirror.message, mirror.deviceAudioRequested, mirror.audioStatus.name, mirror.audioMessage),
        ))
    }
    override val snapshot: StateFlow<ExternalRemoteSnapshot> = combine(
        sessions.route, sessions.activeTarget, sessions.sessionState, sessions.remoteExternalPlayback,
        queues.remoteQueue, sessions.screenMirrorState, linked.uiState,
    ) { read() }.stateIn(scope, SharingStarted.Eagerly, read())

    private val commands: ExternalRemoteCommandOwner = CastSessionRemoteCommandOwner(sessions)
    override fun isCurrent(identity: Any, loadGeneration: Long) = commands.isCurrent(identity, loadGeneration)
    override fun submit(identity: Any, loadGeneration: Long, action: RemoteAction) = submitExternalRemoteCommand(commands, identity, loadGeneration, action)
}
