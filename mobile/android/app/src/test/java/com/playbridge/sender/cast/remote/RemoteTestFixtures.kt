package com.playbridge.sender.cast.remote

import com.playbridge.sender.connection.QueueCommandResult
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

internal val remoteTestEndpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "fixture-tv")
internal fun testConfiguration(profile: RemoteProfile = RemoteProfile.AndroidTv, features: Set<String> = emptySet()) = NativeRemoteConfiguration(
    RemoteReceiver(remoteTestEndpoint, "Fixture TV", profile),
    when (profile) { RemoteProfile.AndroidTv -> listOf("exo", "mpv"); RemoteProfile.AppleTv -> listOf("avplayer", "mpv"); RemoteProfile.Desktop, RemoteProfile.Cli -> listOf("internal_mpv"); else -> emptyList() },
    features,
)
internal fun testSnapshot(context: RemoteContext = RemoteContext.Player, kind: RemoteMediaKind = RemoteMediaKind.Video) = RemoteReceiverSnapshot(
    state = RemoteUiState(
        connection = RemoteConnection.Connected("Fixture TV", true), context = context,
        playback = RemotePlayback(title = "Episode", mediaKind = kind, reportedState = RemotePlaybackState.Playing, reportedPositionMs = 10_000L, durationMs = 120_000L, seekable = Support.Supported),
        queue = RemoteQueueState("playback", 4L, "one", 3, listOf(RemoteQueueItem(3, "One", "one"), RemoteQueueItem(8, "Two", "two"), RemoteQueueItem(12, "Three", "three"))),
        browser = if (context == RemoteContext.Browser) RemoteBrowserState(RemoteBrowserEngine.WebView) else null,
    ), observation = RemoteObservation(playbackSequence = 1L, playbackAtMs = 1L),
)
internal class FakeNativeRemoteTransport(initial: RemoteReceiverSnapshot = testSnapshot()) : NativeRemoteTransport {
    override val snapshot = MutableStateFlow(initial)
    val commands = mutableListOf<String>()
    val pointers = mutableListOf<Triple<String, Float, Float>>()
    val ordering = mutableListOf<String>()
    val anchorResets = mutableListOf<Boolean>()
    val confirmations = mutableListOf<Triple<String, String, Long>>()
    var accepted = true
    var mirrorStops = 0
    var confirmation: suspend () -> QueueCommandResult? = { QueueCommandResult(true, null) }
    override fun send(command: String): Boolean { commands += command; ordering += command; return accepted }
    override fun pointer(event: String, x: Float, y: Float): Boolean { pointers += Triple(event, x, y); ordering += event; return accepted }
    override fun endPointerGesture(): Boolean { ordering += "final-flush"; return accepted }
    override fun endPointerGesture(resetAnchor: Boolean): Boolean { anchorResets += resetAnchor; return endPointerGesture() }
    override suspend fun confirmed(requestId: String, command: String, timeoutMs: Long): QueueCommandResult? {
        confirmations += Triple(requestId, command, timeoutMs)
        return confirmation()
    }
    override fun stopMirror(): Boolean { mirrorStops++; return accepted }
}
internal fun testController(profile: RemoteProfile, transport: NativeRemoteTransport, scope: CoroutineScope, features: Set<String> = emptySet()): RemoteController {
    val config = testConfiguration(profile, features)
    val id = RemoteSessionId(config.receiver.endpoint, 1L)
    return when (profile) {
        RemoteProfile.AndroidTv -> AndroidTvRemoteController(id, config, transport, scope)
        RemoteProfile.AppleTv -> AppleTvRemoteController(id, config, transport, scope)
        RemoteProfile.Desktop -> DesktopRemoteController(id, config, transport, scope)
        RemoteProfile.Cli -> CliRemoteController(id, config, transport, scope)
        else -> UnknownPlayBridgeRemoteController(id, config, transport, scope)
    }
}
