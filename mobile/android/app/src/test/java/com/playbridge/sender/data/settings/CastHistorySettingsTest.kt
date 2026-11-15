package com.playbridge.sender.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.playbridge.sender.data.backup.castHistoryOver
import com.playbridge.sender.data.backup.withCastHistory
import com.playbridge.sender.history.CastHistorySettings
import com.playbridge.sender.settings.ExportedAppSettings
import com.playbridge.sender.settings.SettingsBackupCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHistorySettingsTest {
    private fun store(): Pair<DataStore<Preferences>, MutableStateFlow<Preferences>> {
        val values = MutableStateFlow<Preferences>(emptyPreferences())
        return object : DataStore<Preferences> {
            override val data = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                values.value = transform(values.value)
                return values.value
            }
        } to values
    }

    @Test
    fun `first run defaults`() = runTest {
        val s = SettingsRepository(store().first).castHistory.first()
        assertEquals(CastHistorySettings(), s)
        assertTrue(s.enabled && s.rememberPosition && s.keepPreviews && s.excludeBridgedApps)
        assertFalse(s.checkLinks)
        assertEquals(50, s.limit)
    }

    @Test
    fun `every choice persists independently`() = runTest {
        val repo = SettingsRepository(store().first)
        repo.setCastHistoryEnabled(false)
        repo.setCastHistoryLimit(200)
        repo.setCastHistoryRememberPosition(false)
        repo.setCastHistoryKeepPreviews(false)
        repo.setCastHistoryExcludeBridgedApps(false)
        repo.setCastHistoryCheckLinks(true)
        assertEquals(CastHistorySettings(false, 200, false, false, false, true), repo.castHistory.first())
    }

    @Test
    fun `a limit outside the steps snaps to the nearest one`() = runTest {
        val (dataStore, values) = store()
        val repo = SettingsRepository(dataStore)
        repo.setCastHistoryLimit(1_000_000)
        assertEquals(500, repo.castHistory.first().limit)
        repo.setCastHistoryLimit(-5)
        assertEquals(25, repo.castHistory.first().limit)
        // A stored value from a hand-edited or future file is coerced on read too.
        values.value = values.value.toMutablePreferences().apply { this[intPreferencesKey("cast_history_limit")] = 60 }
        assertEquals(50, repo.castHistory.first().limit)
        assertEquals(listOf(25, 50, 100, 200, 500), CastHistorySettings.LIMITS)
    }

    @Test
    fun `backup carries the choices and round-trips through the file format`() {
        val chosen = CastHistorySettings(false, 100, false, false, false, true)
        val json = Json { encodeDefaults = false }.encodeToString(ExportedAppSettings().withCastHistory(chosen))
        val restored = SettingsBackupCodec.decode("""{"appSettings":$json}""").appSettings!!
        assertEquals(chosen, restored.castHistoryOver(CastHistorySettings()))
    }

    @Test
    fun `an older backup without the choices leaves the current ones and a bad limit is coerced`() {
        val current = CastHistorySettings(enabled = false, limit = 25)
        val old = SettingsBackupCodec.decode("""{"appSettings":{"maxAliveTabs":4}}""").appSettings!!
        assertEquals(current, old.castHistoryOver(current))
        val wild = SettingsBackupCodec.decode("""{"appSettings":{"castHistoryLimit":999999}}""").appSettings!!
        assertEquals(500, wild.castHistoryOver(current).limit)
    }
}
