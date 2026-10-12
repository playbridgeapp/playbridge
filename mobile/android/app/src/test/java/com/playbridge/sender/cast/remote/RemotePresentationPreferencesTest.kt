package com.playbridge.sender.cast.remote

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePresentationPreferencesTest {
    private fun store(values: MutableMap<String, Any>): RemotePresentationPreferences {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putString", "putStringSet" -> { values[args!![0] as String] = args[1]; proxy }
                "apply" -> null
                else -> error("Unexpected editor operation ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString", "getStringSet" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference operation ${method.name}")
            }
        } as SharedPreferences
        return RemotePresentationPreferences(prefs)
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
