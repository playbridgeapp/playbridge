package com.playbridge.sender.cast

/**
 * Timeline capabilities for the active remote surface.
 *
 * Player settings belong only to the native player. The TV browser reports its own
 * position and duration through [TvPlaybackStatus], so it must not inherit live or
 * seekability flags left behind by native playback.
 */
internal data class RemoteTimelineState(
    val isLive: Boolean,
    val isSeekable: Boolean,
)

internal fun resolveRemoteTimeline(
    activeContext: String,
    playback: TvPlaybackStatus?,
    playerSettings: TvPlayerSettings,
): RemoteTimelineState = when (activeContext) {
    "player" -> RemoteTimelineState(
        isLive = playerSettings.isLive,
        isSeekable = playerSettings.isSeekable && !playerSettings.isLive,
    )
    "browser" -> RemoteTimelineState(
        isLive = false,
        isSeekable = (playback?.durationMs ?: 0L) > 0L,
    )
    else -> RemoteTimelineState(
        isLive = false,
        isSeekable = false,
    )
}
