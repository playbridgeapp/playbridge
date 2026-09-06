package com.playbridge.sender.cast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read synchronously at send time so a newly enabled setting applies to the next cast. */
class CastHistorySettings(context: Context) {
    private val prefs = context.getSharedPreferences("cast_history_settings", Context.MODE_PRIVATE)
    private val mutablePreventHistory = MutableStateFlow(prefs.getBoolean("prevent_tv_history", false))
    val preventHistory = mutablePreventHistory.asStateFlow()

    fun setPreventHistory(value: Boolean) {
        prefs.edit().putBoolean("prevent_tv_history", value).apply()
        mutablePreventHistory.value = value
    }
}
