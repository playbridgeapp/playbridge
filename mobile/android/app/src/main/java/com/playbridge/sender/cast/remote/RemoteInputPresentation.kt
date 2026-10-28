package com.playbridge.sender.cast.remote

enum class RemoteInputPresentation { Absent, Full }

/** Native navigation follows the active surface; whole-device control remains an independent route. */
internal fun RemoteUiState.hasPadRoute(): Boolean =
    if (capabilities.keyScope == ControlScope.WholeDevice)
        capabilities.keys.containsAll(setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok))
    else nativePolicy != null && capabilities.keys.any { it in setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok) }

/** Receiver capability decides input size, never the viewport or a second UI owner. */
fun resolveInputPresentation(state: RemoteUiState): RemoteInputPresentation {
    val player = state.context == RemoteContext.Player
    val browser = state.context == RemoteContext.Browser
    val image = state.playback?.mediaKind == RemoteMediaKind.Image
    val pad = state.hasPadRoute()
    val pointer = state.receiver?.profile != RemoteProfile.External &&
        state.hasPointerRoute()
    return when {
        // Video and audio duplicate the wave, so they have no pad unless the TV is whole-device.
        player && !image -> if (pad) RemoteInputPresentation.Full else RemoteInputPresentation.Absent
        // Browser and images keep the full pointer. A whole-device pad can share that slot.
        player || browser -> if (pointer || pad) RemoteInputPresentation.Full else RemoteInputPresentation.Absent
        else -> RemoteInputPresentation.Full // Working idle pad if present, otherwise idle content.
    }
}
