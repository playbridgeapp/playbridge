package com.playbridge.sender.player

import android.content.pm.ActivityInfo

/** Opening policy for website playback; the existing manual rotate button remains available. */
enum class PhonePlayerOpeningOrientation(val wireValue: String, val requestedOrientation: Int, val automatic: Boolean) {
    AUTOMATIC("auto", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, true),
    PORTRAIT("portrait", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, false),
    LANDSCAPE("landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, false);

    companion object {
        fun parse(value: Any?): PhonePlayerOpeningOrientation? = entries.firstOrNull { it.wireValue == value }
    }
}

/** How the player opens: its first requested orientation, and whether it then follows the phone. */
internal data class PlayerOpening(val requestedOrientation: Int, val followPhone: Boolean, val automatic: Boolean)

/**
 * A website's requested orientation wins. Otherwise the Landscape videos setting decides:
 * Fullscreen opens in landscape (turning to portrait for vertical videos); Portrait first
 * follows how the phone is held.
 */
internal fun playerOpening(requestedByWebsite: PhonePlayerOpeningOrientation?, landscapeOpen: PhoneLandscapeOpen): PlayerOpening {
    if (requestedByWebsite == null && landscapeOpen == PhoneLandscapeOpen.PORTRAIT_FIRST) {
        return PlayerOpening(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, followPhone = true, automatic = true)
    }
    val opening = requestedByWebsite ?: PhonePlayerOpeningOrientation.AUTOMATIC
    return PlayerOpening(opening.requestedOrientation, followPhone = false, automatic = opening.automatic)
}
