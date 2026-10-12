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
)

internal fun projectFullControl(
    state: RemoteUiState,
    base: RemoteCapabilities,
    channel: TvRemoteChannelState?,
): RemoteUiState {
    val endpoint = state.sessionId?.endpoint
    val same = channel != null && endpoint != null && channel.matchedEndpoint == endpoint
    if (!same || channel == null) return state.copy(capabilities = base, fullControl = null)
    val status = FullControlStatus(
        connected = channel.connected,
        paired = channel.paired,
        available = channel.fullControlAvailable || channel.paired,
        needsRepair = channel.needsRepair,
        pinMismatch = channel.pinMismatch,
        powerOn = channel.powerOn,
        serverName = channel.serverName,
    )
    val capabilities = if (channel.connected) base.withWholeDevice(channel) else base
    return state.copy(capabilities = capabilities, fullControl = status)
}

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
