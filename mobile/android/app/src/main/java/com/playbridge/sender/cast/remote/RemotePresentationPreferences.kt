package com.playbridge.sender.cast.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

interface RemotePresentationPreferenceStore {
    fun load(): RemotePreferences
    fun save(preferences: RemotePreferences)
}

/** Phone presentation only; no receiver identity, playback title or command data is stored. */
class RemotePresentationPreferences internal constructor(private val preferences: SharedPreferences) : RemotePresentationPreferenceStore {
    constructor(context: Context) : this(context.getSharedPreferences("remote_presentation", Context.MODE_PRIVATE))
    override fun load() = RemotePreferences(
        timeDisplay = RemoteTimeDisplay.entries.firstOrNull { it.name == preferences.getString("time_display", null) } ?: RemoteTimeDisplay.Duration,
        learnedGestures = preferences.getStringSet("learned_gestures", emptySet()).orEmpty().toSet(),
        buttonPad = preferences.getBoolean("button_pad_v2", false),
    )
    override fun save(preferences: RemotePreferences) {
        this.preferences.edit {
            putString("time_display", preferences.timeDisplay.name)
            putStringSet("learned_gestures", preferences.learnedGestures.toSet())
            putBoolean("button_pad_v2", preferences.buttonPad)
        }
    }
}
