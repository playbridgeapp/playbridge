package com.playbridge.sender.cast.remote

/** Phone-side Full control status. Capabilities stay on the receiver snapshot until this is connected. */
data class FullControlStatus(
    val connected: Boolean = false,
    val paired: Boolean = false,
    val available: Boolean = false,
    val needsRepair: Boolean = false,
    val pinMismatch: Boolean = false,
    val powerOn: Boolean? = null,
    val serverName: String? = null,
    val keys: Set<RemoteKey> = emptySet(),
    val text: TextCapabilities = TextCapabilities(),
    val volume: VolumeCapabilities = VolumeCapabilities(),
)

internal fun projectFullControl(
    state: RemoteUiState,
    base: RemoteCapabilities,
    channel: TvRemoteChannelState?,
): RemoteUiState {
    val endpoint = state.sessionId?.endpoint
    if (channel == null || endpoint == null || channel.matchedEndpoint != endpoint) return state.copy(capabilities = base, fullControl = null)
    val status = FullControlStatus(
        connected = channel.readyFor(endpoint),
        paired = channel.paired,
        available = channel.fullControlAvailable || channel.paired,
        needsRepair = channel.needsRepair,
        pinMismatch = channel.pinMismatch,
        powerOn = channel.powerOn,
        serverName = channel.serverName,
        keys = channel.keys,
        text = channel.text,
        volume = channel.volume,
    )
    val capabilities = if (status.connected) base.withWholeDevice(channel) else base
    return state.copy(capabilities = capabilities, fullControl = status)
}

internal val RemoteUiState.fullControlReady: Boolean get() = fullControl?.connected == true

internal fun RemoteUiState.fullControlSupports(action: RemoteAction): Boolean {
    val channel = fullControl?.takeIf { it.connected } ?: return false
    return when (action) {
        is RemoteAction.Key -> action.key in channel.keys
        is RemoteAction.AdjustVolume -> channel.volume.adjust == Support.Supported
        is RemoteAction.ReplaceText -> channel.text.replace == Support.Supported
        RemoteAction.DeleteText -> channel.text.delete == Support.Supported
        is RemoteAction.EditTvText -> channel.text.replace == Support.Supported && channel.text.delete == Support.Supported
        is RemoteAction.LaunchTvApp, RemoteAction.PowerTv -> true
        else -> false
    }
}

internal fun RemoteUiState.actionReady(action: RemoteAction): Boolean = connection.ready || fullControlSupports(action)

private fun RemoteCapabilities.withWholeDevice(channel: TvRemoteChannelState): RemoteCapabilities {
    val level = channel.volume.level
    val maximum = channel.volume.maximum
    val reported = if (level != null && maximum != null && maximum > 0) {
        (level * 100 / maximum).coerceIn(0, 100)
    } else {
        volume.reportedPercent
    }
    return copy(
        keys = keys + channel.keys,
        keyScope = ControlScope.WholeDevice,
        text = text.copy(replace = Support.Supported, delete = Support.Supported, enter = Support.Supported),
        volume = volume.copy(
            adjust = Support.Supported,
            level = level,
            maximum = maximum,
            muted = channel.volume.muted,
            reportedPercent = reported,
        ),
    )
}
