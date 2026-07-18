package com.playbridge.player.data

import android.content.Context

enum class HistoryThumbnailMode(val preferenceValue: String) {
    SMART("smart"),
    LIVE("live"),
    ARTWORK_ONLY("artwork_only");

    companion object {
        const val PREFERENCE_KEY = "history_thumbnail_mode"

        fun fromPreference(value: String?): HistoryThumbnailMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SMART

        fun read(context: Context): HistoryThumbnailMode = fromPreference(
            context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                .getString(PREFERENCE_KEY, SMART.preferenceValue),
        )
    }
}
