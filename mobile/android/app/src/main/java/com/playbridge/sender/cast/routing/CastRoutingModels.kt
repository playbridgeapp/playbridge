package com.playbridge.sender.cast.routing

import com.playbridge.sender.cast.proxy.StreamRouteMode

/** Explicit route selected by the user, after unavoidable local-media normalization. */
data class EffectiveStreamRoute(
    val mode: StreamRouteMode,
    val policyReason: String? = null,
)

/** Media packaged for the selected route. */
data class PreparedCastItem(
    val url: String,
    val headers: Map<String, String>?,
    val contentType: String?,
    val effectiveRoute: EffectiveStreamRoute,
)
