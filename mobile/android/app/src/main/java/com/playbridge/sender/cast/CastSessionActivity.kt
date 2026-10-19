package com.playbridge.sender.cast

internal data class ExternalCastServiceActivity(val phonePathActive: Boolean, val mediaLoaded: Boolean)

/** Notification/service ownership is independent of the data-path wake-lock policy. */
internal fun castSessionNeedsService(
    external: ExternalCastServiceActivity,
    route: CastSessionManager.Route,
    nativeLinked: Boolean,
    nativePlayer: Boolean,
    reconnecting: Boolean,
    mirroring: Boolean,
): Boolean = external.phonePathActive || external.mediaLoaded ||
    (route !is CastSessionManager.Route.External && (nativeLinked || nativePlayer)) ||
    (route == CastSessionManager.Route.NativeTv && reconnecting) || mirroring
