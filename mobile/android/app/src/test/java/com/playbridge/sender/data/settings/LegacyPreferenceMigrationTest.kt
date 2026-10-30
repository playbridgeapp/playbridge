package com.playbridge.sender.data.settings

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy

class LegacyPreferenceMigrationTest {
    @Test
    fun `generic cast defaults migrate before retired stream preferences are deleted`() = runTest {
        val legacy = mutableMapOf<String, Any>("auto_stream_max_mbps" to "48.5", "preferred_audio_lang" to "fr")
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { legacy[args!![0] as String] = args[1]!!; proxy }
                "commit" -> true
                else -> error("Unexpected preference edit")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean", "getString", "getInt" -> legacy[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference read")
            }
        } as SharedPreferences
        val values = MutableStateFlow<Preferences>(emptyPreferences())
        val store = object : DataStore<Preferences> {
            override val data = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                values.value = transform(values.value)
                return values.value
            }
        }
        val settings = SettingsRepository(store)
        settings.migrateLegacyPreferences(prefs)
        RetiredContentCleanup.removedKeys("browser_prefs", legacy.keys).forEach(legacy::remove)
        RetiredContentCleanup.clearDataStore(store)
        settings.migrateLegacyPreferences(prefs)
        assertEquals(48.5, values.value[doublePreferencesKey("max_bitrate_cap_mbps")]!!, 0.0)
        assertEquals("fr", values.value[stringPreferencesKey("preferred_audio_language")])
        assertFalse(legacy.containsKey("auto_stream_max_mbps"))
        assertEquals(false, legacy["needs_datastore_migration"])
    }
}
