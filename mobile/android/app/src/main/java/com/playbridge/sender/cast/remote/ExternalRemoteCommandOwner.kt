package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.CastSessionManager

/** Command seam for fixtures; production delegates to the existing session singleton. */
internal interface ExternalRemoteCommandOwner {
    fun isCurrent(identity: Any, loadGeneration: Long): Boolean
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setVolume(percent: Int)
    fun adjustVolume(up: Boolean)
    fun rokuKeypress(key: String)
    val mirroring: Boolean
    fun stopMirror()
}
internal class CastSessionRemoteCommandOwner(private val sessions: CastSessionManager) : ExternalRemoteCommandOwner {
    override fun isCurrent(identity: Any, loadGeneration: Long) = sessions.isCurrentRemoteExternal(identity, loadGeneration)
    override fun play() { sessions.play() }
    override fun pause() { sessions.pause() }
    override fun stop() { sessions.stop() }
    override fun seekTo(positionMs: Long) { sessions.seekTo(positionMs) }
    override fun setVolume(percent: Int) { sessions.setVolume(percent) }
    override fun adjustVolume(up: Boolean) { sessions.adjustVolume(up) }
    override fun rokuKeypress(key: String) { sessions.rokuKeypress(key) }
    override val mirroring get() = sessions.screenMirrorState.value.isActive
    override fun stopMirror() { sessions.stopScreenMirror() }
}
internal fun submitExternalRemoteCommand(owner: ExternalRemoteCommandOwner, identity: Any, generation: Long, action: RemoteAction): RemoteCommandResult {
    if (!owner.isCurrent(identity, generation)) return RemoteCommandResult.Failed("Remote session changed")
    when (action) {
        is RemoteAction.SetPlaying -> if (action.playing) owner.play() else owner.pause()
        is RemoteAction.SeekTo -> owner.seekTo(action.positionMs)
        RemoteAction.StopPlayback -> if (owner.mirroring) owner.stopMirror() else owner.stop()
        is RemoteAction.AdjustVolume -> repeat(kotlin.math.abs(action.steps)) {
            if (!owner.isCurrent(identity, generation)) return RemoteCommandResult.Failed("Remote session changed")
            owner.adjustVolume(action.steps > 0)
        }
        is RemoteAction.SetVolume -> owner.setVolume(action.percent)
        is RemoteAction.Key -> owner.rokuKeypress(action.key.wireValue)
        else -> return RemoteCommandResult.Unsupported("No external owner method for this action")
    }
    return RemoteCommandResult.Submitted
}
