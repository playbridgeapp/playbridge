package com.playbridge.sender.cast.remote

import android.os.SystemClock
import com.playbridge.sender.browser.LinkedPageCastCoordinator
import com.playbridge.sender.browser.LinkedPageCastUiState
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.TvPlaybackStatus
import com.playbridge.sender.cast.TvPlayerSettings
import com.playbridge.sender.cast.mirror.ScreenMirrorCoordinator
import com.playbridge.sender.cast.resolveRemoteTimeline
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.QueueCommandResult
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.connection.ReceiverProtocolState
import com.playbridge.sender.library.PlaylistUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class WebSocketNativeRemoteTransport(
    private val client: WebSocketClient,
    private val coordinator: ConnectionCoordinator,
    private val sessions: CastSessionManager,
    linkedWebsite: LinkedPageCastCoordinator,
    scope: CoroutineScope,
) : NativeRemoteTransport {
    private val sources: List<StateFlow<*>> = listOf(
        client.connectionState, coordinator.tvActiveContext, coordinator.tvPlayback, coordinator.tvPlaylistState,
        coordinator.tvVideoTracks, coordinator.tvAudioTracks, coordinator.tvSubtitleTracks,
        coordinator.tvPlayerSettings, coordinator.remoteObservation, sessions.screenMirrorState, linkedWebsite.uiState,
        client.receiverProtocolState,
    )
    override val snapshot = combine<Any?, RemoteReceiverSnapshot>(sources) { read(it.toList()) }
        .stateIn(scope, SharingStarted.Eagerly, read(sources.map { it.value }))

    @Suppress("UNCHECKED_CAST")
    private fun read(values: List<Any?>): RemoteReceiverSnapshot {
        val connection = remoteConnection(values[0] as WebSocketClient.ConnectionState)
        val wireContext = values[1] as String
        val context = remoteContextFromWire(wireContext)
        val playback = values[2] as TvPlaybackStatus?
        val queue = values[3] as PlaylistUiState?
        val settings = values[7] as TvPlayerSettings
        val observation = values[8] as RemoteObservation
        val mirror = values[9] as ScreenMirrorCoordinator.State
        val linked = values[10] as LinkedPageCastUiState
        val timeline = resolveRemoteTimeline(wireContext, playback, settings)
        fun tracks(index: Int) = (values[index] as List<MediaTrack>).map { RemoteTrack(it.id, it.name, it.selected, it.type) }
        fun reported(field: String) = field in observation.settingsFields
        val playerSettings = if (observation.settingsReported) RemotePlayerSettings(
            speed = settings.speed.takeIf { reported("speed") }, scaling = ScalingMode.entries.firstOrNull { reported("scaling") && it.wireValue == settings.scaling },
            audioBoost = settings.audioBoost.takeIf { reported("audioBoost") }, subtitleOffsetMs = settings.subtitleOffsetMs.takeIf { reported("subtitleOffsetMs") },
            engine = settings.engine.takeIf { reported("engine") }, qualityMaxHeight = settings.qualityMaxHeight.takeIf { reported("qualityMaxHeight") }, currentVideoHeight = settings.currentVideoHeight.takeIf { reported("currentVideoHeight") },
            loopEnabled = loopFromPlayerSettings(settings.loop, observation.settingsFields),
            availability = mapOf(
                PlayerSetting.Speed to support(settings.speedAvailable), PlayerSetting.Scaling to support(settings.scalingAvailable),
                PlayerSetting.AudioBoost to support(settings.audioBoostAvailable), PlayerSetting.Quality to support(settings.qualityAvailable),
            ).filterKeys { setting -> when (setting) { PlayerSetting.Speed -> reported("speedAvailable"); PlayerSetting.Scaling -> reported("scalingAvailable"); PlayerSetting.AudioBoost -> reported("audioBoostAvailable"); PlayerSetting.Quality -> reported("qualityAvailable"); else -> false } },
        ) else RemotePlayerSettings()
        return RemoteReceiverSnapshot(
            RemoteUiState(
                connection = connection, context = context,
                receiverProtocol = if (connection.ready) values[11] as ReceiverProtocolState else ReceiverProtocolState(),
                playback = (playback ?: if (context == RemoteContext.Player) TvPlaybackStatus("idle", 0L, 0L, "") else null)?.let {
                    RemotePlayback(
                        title = it.title?.takeIf { title -> title.isNotEmpty() }, mediaKind = remoteMediaKind(it.mediaKind), reportedState = remotePlaybackState(it.state),
                        reportedPositionMs = it.positionMs, durationMs = it.durationMs, isLive = timeline.isLive,
                        seekable = support(timeline.isSeekable), observedAtMs = observation.playbackAtMs,
                    )
                },
                queue = queue?.let {
                    RemoteQueueState(it.playbackId, it.queueRevision, it.currentItemId, it.currentIndex, it.items.map { item ->
                        RemoteQueueItem(item.index, item.title, item.itemId, remoteMediaKind(item.mediaKind), item.season, item.episode, item.imdbId, item.bingeGroup, item.tmdbId)
                    })
                },
                tracks = RemoteTracks(tracks(4), tracks(5), tracks(6)), settings = playerSettings,
                browser = if (context == RemoteContext.Browser) RemoteBrowserState(
                    engine = if (wireContext == "browser_external") RemoteBrowserEngine.Gecko else RemoteBrowserEngine.Unknown,
                    muted = observation.browserMuted,
                    fullscreen = browserFullscreenFromStatus(context, observation.browserFullscreen),
                ) else null,
                mirror = RemoteMirrorState(mirror.isActive, mirror.sessionId, mirror.phase.name, mirror.message, mirror.deviceAudioRequested, mirror.audioStatus.name, mirror.audioMessage),
                linkedWebsite = LinkedWebsiteState(linked.active, linked.controllerName),
            ), observation,
        )
    }

    override fun send(command: String): Boolean = client.send(command)
    override fun pointer(event: String, x: Float, y: Float): Boolean {
        if (client.connectionState.value !is WebSocketClient.ConnectionState.Connected) return false
        client.sendMouseCommand(event, x, y)
        return true // queued/coalesced is submission, not acknowledgement
    }
    override fun endPointerGesture(): Boolean {
        if (client.connectionState.value !is WebSocketClient.ConnectionState.Connected) return false
        client.endPointerGesture() // final coalesced movement precedes the (-1,-1) anchor reset
        return true
    }
    override suspend fun confirmed(requestId: String, command: String, timeoutMs: Long): QueueCommandResult? =
        coordinator.sendConfirmedQueueCommand(requestId, command, timeoutMs)
    override fun stopMirror(): Boolean {
        if (!sessions.screenMirrorState.value.isActive && coordinator.tvActiveContext.value != "screen_mirror") return false
        sessions.stopScreenMirror()
        return true
    }
}

internal fun loopFromPlayerSettings(loop: Boolean?, fields: Set<String>): Boolean? =
    if ("loop" in fields) loop else null

internal fun browserFullscreenFromStatus(context: RemoteContext, reported: Boolean?): Boolean? =
    if (context == RemoteContext.Browser) reported else null

internal fun support(value: Boolean) = if (value) Support.Supported else Support.Unsupported
internal fun remoteMediaKind(value: String) = when (value) { "audio" -> RemoteMediaKind.Audio; "image" -> RemoteMediaKind.Image; else -> RemoteMediaKind.Video }
internal fun remotePlaybackState(value: String?) = when (value) {
    "playing" -> RemotePlaybackState.Playing; "paused" -> RemotePlaybackState.Paused; "buffering" -> RemotePlaybackState.Buffering
    "idle", "stopped" -> RemotePlaybackState.Idle; "error" -> RemotePlaybackState.Error; else -> RemotePlaybackState.Unknown
}
internal fun remoteConnection(state: WebSocketClient.ConnectionState): RemoteConnection = when (state) {
    WebSocketClient.ConnectionState.Disconnected -> RemoteConnection.Disconnected
    is WebSocketClient.ConnectionState.Connected -> RemoteConnection.Connected(state.serverName, state.secure)
    is WebSocketClient.ConnectionState.Connecting -> RemoteConnection.Connecting(state.serverName)
    is WebSocketClient.ConnectionState.Retrying -> RemoteConnection.Reconnecting(state.attempt, state.maxAttempts, state.nextRetrySeconds)
    is WebSocketClient.ConnectionState.WaitingForCodeInput -> RemoteConnection.Pairing(state.serverName, RemoteConnection.Pairing.Phase.CodeInput, state.attemptsLeft, state.lastCodeWrong)
    is WebSocketClient.ConnectionState.VerifyingCode -> RemoteConnection.Pairing(state.serverName, RemoteConnection.Pairing.Phase.Verifying)
    is WebSocketClient.ConnectionState.WaitingForApproval -> RemoteConnection.Pairing(state.serverName, RemoteConnection.Pairing.Phase.Approval)
    is WebSocketClient.ConnectionState.PairingDenied -> RemoteConnection.Pairing(state.serverName, RemoteConnection.Pairing.Phase.Denied)
    is WebSocketClient.ConnectionState.Error -> RemoteConnection.Failed(state.message)
    WebSocketClient.ConnectionState.AuthFailed -> RemoteConnection.Failed("Authentication failed", RemoteConnection.Failed.Kind.Auth)
    is WebSocketClient.ConnectionState.PinMismatch -> RemoteConnection.Failed("Certificate mismatch", RemoteConnection.Failed.Kind.Pin, state.serverName)
}

internal fun remoteMonotonicTime(): Long = SystemClock.uptimeMillis()
