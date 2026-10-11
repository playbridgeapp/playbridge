package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastSessionState
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.SessionPhase
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.flow.MutableStateFlow

internal fun externalFixtureCapabilities(kind: TargetKind) = buildSet {
    addAll(setOf(Capability.LOAD, Capability.PLAY_PAUSE, Capability.STOP, Capability.NOW_PLAYING, Capability.VOLUME))
    if (kind == TargetKind.ROKU) add(Capability.REMOTE) else add(Capability.SEEK)
}
internal fun externalFixtureReceiver(kind: TargetKind) = RemoteReceiver(EndpointKey(when (kind) {
    TargetKind.GOOGLE_CAST -> CastProtocol.GOOGLE_CAST
    TargetKind.ROKU -> CastProtocol.ROKU
    TargetKind.DLNA -> CastProtocol.DLNA
    TargetKind.WEB_BROWSER -> CastProtocol.WEB_BROWSER
    else -> CastProtocol.PLAYBRIDGE
}, "fixture"), "External TV", RemoteProfile.External)

internal class FakeExternalCommandOwner : ExternalRemoteCommandOwner {
    var identity = Any()
    var generation = 8L
    var active = true
    var available = true
    val calls = mutableListOf<String>()
    var afterVolume: () -> Unit = {}
    override fun isCurrent(identity: Any, loadGeneration: Long) = active && available && this.identity === identity && generation == loadGeneration
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun stop() { calls += "stop" }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    override fun setVolume(percent: Int) { calls += "setVolume:$percent" }
    override fun adjustVolume(up: Boolean) { calls += if (up) "volume:up" else "volume:down"; afterVolume() }
    override fun rokuKeypress(key: String) { calls += "roku:$key" }
    override var mirroring = false
    override fun stopMirror() { calls += "stopMirror" }
}
internal class FakeExternalRemoteTransport(val kind: TargetKind = TargetKind.GOOGLE_CAST) : ExternalRemoteTransport {
    val owner = FakeExternalCommandOwner()
    val receiver = externalFixtureReceiver(kind)
    val target get() = RemoteExternalTarget(receiver, kind, owner.identity)
    var now = 1L
    private var publication = ExternalRemoteObservation(
        PlaybackStatus(PlaybackState.PLAYING, 10_000L, 120_000L, loadEpoch = owner.generation), owner.generation, 1L, now,
    )
    var capabilities = externalFixtureCapabilities(kind)
    var title: String? = "Episode"
    var phase = SessionPhase.PLAYING
    var queue: RemoteQueueState? = null
    var linked: LinkedWebsiteState? = null
    private fun read() = ExternalRemoteSnapshot(owner.identity, externalReceiverSnapshot(
        receiver, kind, CastSessionState(phase, receiver.endpoint, targetKind = kind, capabilities = capabilities, playback = publication.status, mediaTitle = title), publication, queue, linked,
    ))
    override val snapshot = MutableStateFlow(read())
    fun tick(position: Long = 10_000L, state: PlaybackState = PlaybackState.PLAYING, fresh: Boolean = true, live: Boolean = false, volume: Boolean? = null) {
        publication = publication.copy(
            status = PlaybackStatus(state, position, 120_000L, isLive = live, loadEpoch = owner.generation, volumeSupported = volume),
            loadGeneration = owner.generation, sequence = publication.sequence + if (fresh) 1L else 0L, atMs = if (fresh) now else publication.atMs,
        )
        refresh()
    }
    fun refresh() { snapshot.value = read() }
    fun fail() { publication = publication.copy(controlFailureVersion = publication.controlFailureVersion + 1L); refresh() }
    override fun isCurrent(identity: Any, loadGeneration: Long) = owner.isCurrent(identity, loadGeneration)
    override fun submit(identity: Any, loadGeneration: Long, action: RemoteAction) = submitExternalRemoteCommand(owner, identity, loadGeneration, action)
}
