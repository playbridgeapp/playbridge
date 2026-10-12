package com.playbridge.sender.cast.remote

enum class RemoteInputPresentation { Absent, Full }

/** A pad exists only for whole-device navigation (stage 4, and Roku ECP). PlayBridge playback keys are not a pad. */
internal fun RemoteUiState.hasPadRoute(): Boolean =
    capabilities.keyScope == ControlScope.WholeDevice &&
        capabilities.keys.containsAll(setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok))

/** Receiver capability decides input size, never the viewport or a second UI owner. */
fun resolveInputPresentation(state: RemoteUiState): RemoteInputPresentation {
    val player = state.context == RemoteContext.Player
    val browser = state.context == RemoteContext.Browser
    val image = state.playback?.mediaKind == RemoteMediaKind.Image
    val pad = state.hasPadRoute()
    val pointer = state.receiver?.profile != RemoteProfile.External &&
        state.capabilities.pointer.move == Support.Supported
    return when {
        // Video and audio duplicate the wave, so they have no pad unless the TV is whole-device.
        player && !image -> if (pad) RemoteInputPresentation.Full else RemoteInputPresentation.Absent
        // Browser and images keep the full pointer. A whole-device pad can share that slot.
        player || browser -> if (pointer || pad) RemoteInputPresentation.Full else RemoteInputPresentation.Absent
        else -> RemoteInputPresentation.Full // Working idle pad if present, otherwise idle content.
    }
}
