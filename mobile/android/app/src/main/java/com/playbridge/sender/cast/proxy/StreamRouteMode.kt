package com.playbridge.sender.cast.proxy

/**
 * Where the TV (or other receiver) should fetch media bytes from when casting.
 *
 * Seeded from [StreamProxySettings.defaultRoute]; the cast sheet can override per open.
 */
enum class StreamRouteMode(val label: String, val prefsValue: String) {
    DIRECT("Direct", "direct"),
    VIA_PHONE("Via phone", "via_phone"),
    VIA_PROXY("Via proxy", "via_proxy");

    companion object {
        fun fromPrefs(value: String?): StreamRouteMode =
            entries.firstOrNull { it.prefsValue.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: DIRECT
    }
}
