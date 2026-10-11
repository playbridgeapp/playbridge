package com.playbridge.sender.cast.remote

internal data class RemoteNavAvailability(val back: Boolean, val home: Boolean, val keys: Boolean, val volume: Boolean)

internal fun remoteNavAvailability(context: String, external: Boolean, remote: Boolean, volume: Boolean, connected: Boolean): RemoteNavAvailability {
    val nav = resolveRemoteLayout(stage1PolicyState(context, external, remote, volume = volume, connected = connected), RemoteViewport()).nav
    return RemoteNavAvailability(nav.back.enabled, nav.home.enabled, nav.keys.enabled, nav.volume.enabled)
}

internal data class RemoteTransportAvailability(val visible: Boolean, val seek: Boolean, val previous: Int?, val next: Int?)

internal fun remoteTransportAvailability(
    context: String, external: Boolean, seekable: Boolean, live: Boolean, image: Boolean,
    episodeIndices: List<Int>, currentIndex: Int,
): RemoteTransportAvailability {
    val transport = resolveRemoteLayout(stage1PolicyState(context, external, false, seekable = seekable, live = live, image = image, indices = episodeIndices, currentIndex = currentIndex), RemoteViewport()).transport
    return RemoteTransportAvailability(transport.visible, transport.seek, transport.previous, transport.next)
}

internal fun externalRemoteContext(state: String?): String =
    if (state in setOf("playing", "paused", "buffering")) "player" else "idle"

/** Existing Android/desktop routes; internal_mpv alone also identifies CLI, which has no volume keys. */
internal fun nativeRemoteVolumeSupported(players: List<String>, features: Set<String>): Boolean =
    nativeRemoteCapabilities(detectRemoteProfile(players, features), RemoteContext.Idle, RemoteMediaKind.Video).volume.adjust == Support.Supported

internal data class NativeRemoteInputAvailability(val remote: Boolean, val touchpad: Boolean)

/** Only Android has full key input; Desktop pointer input is implemented for images only. */
internal fun nativeRemoteInputAvailability(players: List<String>, features: Set<String>, image: Boolean): NativeRemoteInputAvailability {
    val profile = detectRemoteProfile(players, features)
    val capabilities = nativeRemoteCapabilities(profile, RemoteContext.Player, if (image) RemoteMediaKind.Image else RemoteMediaKind.Video)
    return NativeRemoteInputAvailability(profile == RemoteProfile.AndroidTv, profile == RemoteProfile.AndroidTv || image && capabilities.pointer.move == Support.Supported)
}
