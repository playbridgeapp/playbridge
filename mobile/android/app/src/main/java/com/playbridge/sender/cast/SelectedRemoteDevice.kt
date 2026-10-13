package com.playbridge.sender.cast

import com.playbridge.sender.model.TvDevice

/** Uses selection intent, not socket/media readiness, to identify the TV Remote controls. */
internal fun selectedRemoteDevice(
    route: CastSessionManager.Route,
    native: TvDevice?,
    external: TvDevice?,
): TvDevice? = when (route) {
    CastSessionManager.Route.ThisDevice -> null
    CastSessionManager.Route.NativeTv -> native
    is CastSessionManager.Route.External -> external?.takeIf { it.endpointKey == route.endpointKey }
}
