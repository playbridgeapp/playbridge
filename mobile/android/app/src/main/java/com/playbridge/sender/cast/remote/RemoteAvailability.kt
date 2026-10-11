package com.playbridge.sender.cast.remote

internal data class RemoteNavAvailability(val back: Boolean, val home: Boolean, val keys: Boolean, val volume: Boolean)

internal fun remoteNavAvailability(context: String, external: Boolean, remote: Boolean, volume: Boolean, connected: Boolean) =
    RemoteNavAvailability(
        back = connected && remote && (external || context == "player" || context == "browser"),
        home = connected && remote && (external || context == "browser"),
        keys = connected && !external && remote && context == "browser",
        volume = connected && volume,
    )

internal data class RemoteTransportAvailability(val visible: Boolean, val seek: Boolean, val previous: Int?, val next: Int?)

internal fun remoteTransportAvailability(
    context: String, external: Boolean, seekable: Boolean, live: Boolean, image: Boolean,
    episodeIndices: List<Int>, currentIndex: Int,
): RemoteTransportAvailability {
    val current = episodeIndices.indexOf(currentIndex)
    return RemoteTransportAvailability(
        visible = context == "player",
        seek = context == "player" && seekable && !live && !image,
        previous = if (!external && current > 0) episodeIndices[current - 1] else null,
        next = if (!external && current >= 0) episodeIndices.getOrNull(current + 1) else null,
    )
}

internal fun externalRemoteContext(state: String?): String =
    if (state in setOf("playing", "paused", "buffering")) "player" else "idle"

/** Existing Android/desktop routes; internal_mpv alone also identifies CLI, which has no volume keys. */
internal fun nativeRemoteVolumeSupported(players: List<String>, features: Set<String>): Boolean =
    "exo" in players || isDesktopRemoteProfile(players, features)

internal data class NativeRemoteInputAvailability(val remote: Boolean, val touchpad: Boolean)

/** Only Android has full key input; Desktop pointer input is implemented for images only. */
internal fun nativeRemoteInputAvailability(players: List<String>, features: Set<String>, image: Boolean) =
    NativeRemoteInputAvailability(
        remote = "exo" in players,
        touchpad = "exo" in players || (image && isDesktopRemoteProfile(players, features)),
    )

private fun isDesktopRemoteProfile(players: List<String>, features: Set<String>): Boolean =
    "internal_mpv" in players && "subtitle_resource_add_v1" in features
