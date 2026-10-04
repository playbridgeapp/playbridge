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
