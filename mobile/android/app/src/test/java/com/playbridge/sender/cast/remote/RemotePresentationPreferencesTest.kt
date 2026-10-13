package com.playbridge.sender.cast.remote

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePresentationPreferencesTest {
    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val pending = mutableMapOf<String, Any>()
        lateinit var prefs: SharedPreferences
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putString", "putStringSet" -> { pending[args!![0] as String] = args[1]; proxy }
                "apply" -> {
                    val changed = pending.keys.toList()
                    values.putAll(pending); pending.clear()
                    changed.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(prefs, key) } }
                    null
                }
                else -> error("Unexpected editor operation ${method.name}")
            }
        } as SharedPreferences.Editor
        prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString", "getStringSet" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                "registerOnSharedPreferenceChangeListener" -> { listeners += args!![0] as SharedPreferences.OnSharedPreferenceChangeListener; null }
                else -> error("Unexpected preference operation ${method.name}")
            }
        } as SharedPreferences
        return prefs
    }
    private fun store(values: MutableMap<String, Any>) = RemotePresentationPreferences(preferences(values))

    @Test fun missingHapticKeysDefaultToEnabledAndLight() {
        val prefs = store(mutableMapOf())
        assertTrue(prefs.load().hapticsEnabled)
        assertEquals(RemoteHapticLevel.Light, prefs.load().hapticsLevel)
        assertEquals(prefs.load(), prefs.state.value)
    }

    @Test fun hapticChoicesRoundTripWithoutLosingButtonPadTimeOrGestures() {
        val values = mutableMapOf<String, Any>()
        val prefs = store(values)
        RemoteHapticLevel.entries.forEach { level ->
            val expected = RemotePreferences(buttonPad = true, timeDisplay = RemoteTimeDisplay.Remaining,
                learnedGestures = setOf("seek"), hapticsEnabled = false, hapticsLevel = level)
            prefs.save(expected)
            assertEquals(expected, store(values).load())
            assertEquals(expected, prefs.state.value)
            assertEquals(level.storedValue, values["haptics_level"])
            assertEquals(false, values["haptics_enabled"])
        }
    }

    @Test fun unknownHapticLevelFallsBackToLight() {
        listOf("future", "LIGHT", "").forEach { value ->
            assertEquals(RemoteHapticLevel.Light, store(mutableMapOf("haptics_level" to value)).load().hapticsLevel)
        }
    }

    @Test fun sharedPreferenceEditsAndStoreUpdatesRefreshEveryObserver() {
        val backing = preferences(mutableMapOf())
        val first = RemotePresentationPreferences(backing)
        val second = RemotePresentationPreferences(backing)
        backing.edit().putBoolean("haptics_enabled", false).putString("haptics_level", "strong").apply()
        assertFalse(first.state.value.hapticsEnabled)
        assertEquals(RemoteHapticLevel.Strong, second.state.value.hapticsLevel)
        first.update { it.withPresentationAction(RemoteAction.SetButtonPad(true)) }
        assertEquals(first.state.value, second.state.value)
        assertTrue(second.state.value.buttonPad)
        assertFalse(second.state.value.hapticsEnabled)
        backing.edit().putString("haptics_level", "unknown").apply()
        assertEquals(RemoteHapticLevel.Light, first.state.value.hapticsLevel)
    }

    @Test fun missingAndIncidentalLegacyKeysDoNotEnableButtonPad() {
        assertFalse(store(mutableMapOf()).load().buttonPad)
        val values = mutableMapOf<String, Any>("button_pad" to true, "time_display" to "Remaining", "learned_gestures" to setOf("tap"))
        val loaded = store(values).load()
        assertFalse(loaded.buttonPad)
        assertEquals(RemoteTimeDisplay.Remaining, loaded.timeDisplay)
        assertEquals(setOf("tap"), loaded.learnedGestures)
        store(values).save(loaded)
        assertFalse(store(values).load().buttonPad)
    }
    @Test fun explicitButtonPadChoiceSurvivesReloadAndOtherPreferenceWrites() {
        val values = mutableMapOf<String, Any>()
        val prefs = store(values)
        prefs.save(prefs.load().copy(buttonPad = true))
        assertTrue(store(values).load().buttonPad)
        prefs.save(prefs.load().copy(timeDisplay = RemoteTimeDisplay.Remaining, learnedGestures = setOf("seek")))
        assertTrue(store(values).load().buttonPad)
        prefs.save(prefs.load().copy(buttonPad = false))
        assertFalse(store(values).load().buttonPad)
        assertEquals(true, values.containsKey("button_pad_v2"))
    }
}
