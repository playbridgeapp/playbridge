package com.playbridge.player.player

import android.content.Context

/** Selects which component renders external subtitles over video. */
internal enum class SubtitleRenderingMode(val preferenceValue: String) {
    AUTO("auto"),
    BUILT_IN("built_in"),
    PLAYBRIDGE_OVERLAY("playbridge_overlay"),
    ;

    companion object {
        const val PREFERENCE_KEY = "subtitle_rendering_mode"

        fun fromPreference(value: String?): SubtitleRenderingMode =
            entries.firstOrNull { it.preferenceValue == value } ?: AUTO

        fun read(context: Context): SubtitleRenderingMode = fromPreference(
            context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                .getString(PREFERENCE_KEY, AUTO.preferenceValue),
        )
    }
}
