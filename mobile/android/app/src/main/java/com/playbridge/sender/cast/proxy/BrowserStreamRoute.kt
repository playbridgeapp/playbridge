package com.playbridge.sender.cast.proxy

/**
 * Stream-route rules for casting to a phone-hosted browser receiver.
 *
 * - Local media always Via phone.
 * - Direct is not a good browser default (CORS/auth); map to Via phone.
 * - Via proxy is honored when configured; callers fall back to Via phone on failure.
 */
object BrowserStreamRoute {
    fun effectiveMode(
        requested: StreamRouteMode,
        isLocalMedia: Boolean,
    ): StreamRouteMode = when {
        isLocalMedia -> StreamRouteMode.VIA_PHONE
        requested == StreamRouteMode.DIRECT -> StreamRouteMode.VIA_PHONE
        else -> requested
    }

    fun isLocalMediaUrl(url: String): Boolean =
        url.startsWith("content://") ||
            url.startsWith("file://") ||
            url.startsWith("data:")

    fun overrideReason(
        requested: StreamRouteMode,
        effective: StreamRouteMode,
        isLocalMedia: Boolean,
    ): String? = when {
        requested == effective -> null
        isLocalMedia -> "Local files cast via this phone."
        requested == StreamRouteMode.DIRECT -> "Browsers cast Via phone by default."
        else -> null
    }
}
