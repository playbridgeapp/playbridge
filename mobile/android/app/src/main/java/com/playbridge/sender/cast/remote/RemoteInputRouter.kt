package com.playbridge.sender.cast.remote

import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TvRemoteChannelState(
    val matchedEndpoint: EndpointKey? = null, val physicalDeviceId: String? = null,
    val paired: Boolean = false, val connected: Boolean = false, val keys: Set<RemoteKey> = emptySet(),
    val volume: VolumeCapabilities = VolumeCapabilities(), val text: TextCapabilities = TextCapabilities(),
    val needsRepair: Boolean = false, val pinMismatch: Boolean = false, val powerOn: Boolean? = null,
    val serverName: String? = null, val fullControlAvailable: Boolean = false,
)
interface TvRemoteChannel {
    val state: StateFlow<TvRemoteChannelState>
    suspend fun sendKey(key: RemoteKey): RemoteCommandResult
    suspend fun adjustVolume(steps: Int): RemoteCommandResult
    suspend fun replaceText(text: String): RemoteCommandResult
    suspend fun deleteText(): RemoteCommandResult
    suspend fun launchApp(packageId: String): RemoteCommandResult = RemoteCommandResult.Unsupported("Whole-TV control is not available yet")
    suspend fun power(): RemoteCommandResult = RemoteCommandResult.Unsupported("Whole-TV control is not available yet")
    fun setRemoteVisible(visible: Boolean) {}
}
class UnavailableTvRemoteChannel : TvRemoteChannel {
    override val state = MutableStateFlow(TvRemoteChannelState()).asStateFlow()
    private fun unavailable() = RemoteCommandResult.Unsupported("Whole-TV control is not available yet")
    override suspend fun sendKey(key: RemoteKey) = unavailable()
    override suspend fun adjustVolume(steps: Int) = unavailable()
    override suspend fun replaceText(text: String) = unavailable()
    override suspend fun deleteText() = unavailable()
}

class RemoteInputRouter(private val channel: TvRemoteChannel = UnavailableTvRemoteChannel()) {
    suspend fun execute(controller: RemoteController, action: RemoteAction, expectedMedia: RemoteMediaId? = controller.snapshot.value.state.playback?.mediaId): RemoteCommandResult {
        val state = channel.state.value
        val matched = state.paired && state.connected && !state.physicalDeviceId.isNullOrBlank() && state.matchedEndpoint == controller.sessionId.endpoint
        // Choose once, before sending. An ambiguous failure is never resent on the other channel.
        return when {
            action is RemoteAction.LaunchTvApp -> if (matched) channel.launchApp(action.packageId) else RemoteCommandResult.Unsupported("Full control is not connected")
            action is RemoteAction.PowerTv -> if (matched) channel.power() else RemoteCommandResult.Unsupported("Full control is not connected")
            action is RemoteAction.Key && action.key == RemoteKey.Menu -> if (matched && action.key in state.keys) channel.sendKey(action.key) else RemoteCommandResult.Unsupported("Full control is not connected")
            matched && action is RemoteAction.Key && action.key in state.keys -> channel.sendKey(action.key)
            matched && action is RemoteAction.AdjustVolume && state.volume.adjust == Support.Supported -> channel.adjustVolume(action.steps)
            matched && action is RemoteAction.ReplaceText && state.text.replace == Support.Supported -> channel.replaceText(action.text)
            matched && action == RemoteAction.DeleteText && state.text.delete == Support.Supported -> channel.deleteText()
            else -> controller.execute(action, expectedMedia)
        }
    }
}
