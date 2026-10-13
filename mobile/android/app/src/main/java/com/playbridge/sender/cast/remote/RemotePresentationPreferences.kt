package com.playbridge.sender.cast.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface RemotePresentationPreferenceStore {
    val state: StateFlow<RemotePreferences>
    fun load(): RemotePreferences
    fun save(preferences: RemotePreferences)
    fun update(transform: (RemotePreferences) -> RemotePreferences): RemotePreferences {
        val updated = transform(load())
        save(updated)
        return updated
    }
}

internal fun RemotePreferences.withPresentationAction(action: RemoteAction): RemotePreferences = when (action) {
    is RemoteAction.SetButtonPad -> copy(buttonPad = action.enabled)
    is RemoteAction.SetTimeDisplay -> copy(timeDisplay = action.display)
    is RemoteAction.MarkGestureLearned -> copy(learnedGestures = learnedGestures + action.gesture)
    else -> this
}

/** Phone presentation only; no receiver identity, playback title or command data is stored. */
class RemotePresentationPreferences internal constructor(private val preferences: SharedPreferences) : RemotePresentationPreferenceStore {
    constructor(context: Context) : this(context.getSharedPreferences("remote_presentation", Context.MODE_PRIVATE))
    private val mutableState = MutableStateFlow(load())
    override val state = mutableState.asStateFlow()
    // SharedPreferences keeps listeners weakly; the process singleton owns this strong reference.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutableState.value = load() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }
    override fun load() = RemotePreferences(
        timeDisplay = RemoteTimeDisplay.entries.firstOrNull { it.name == preferences.getString("time_display", null) } ?: RemoteTimeDisplay.Duration,
        learnedGestures = preferences.getStringSet("learned_gestures", emptySet()).orEmpty().toSet(),
        buttonPad = preferences.getBoolean("button_pad_v2", false),
        hapticsEnabled = preferences.getBoolean("haptics_enabled", true),
        hapticsLevel = RemoteHapticLevel.fromStoredValue(preferences.getString("haptics_level", null)),
    )
    @Synchronized
    override fun update(transform: (RemotePreferences) -> RemotePreferences): RemotePreferences = super.update(transform)

    @Synchronized
    override fun save(preferences: RemotePreferences) {
        this.preferences.edit {
            putString("time_display", preferences.timeDisplay.name)
            putStringSet("learned_gestures", preferences.learnedGestures.toSet())
            putBoolean("button_pad_v2", preferences.buttonPad)
            putBoolean("haptics_enabled", preferences.hapticsEnabled)
            putString("haptics_level", preferences.hapticsLevel.storedValue)
        }
        mutableState.value = load()
    }
}
