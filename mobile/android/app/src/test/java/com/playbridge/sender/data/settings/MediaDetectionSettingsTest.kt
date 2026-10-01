package com.playbridge.sender.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.playbridge.sender.cast.DetectedMediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDetectionSettingsTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun legacyMasterSwitchIsPreservedAndBridgedOverrideDefaultsOff() = runBlocking {
        val repo = SettingsRepository(MemoryStore(preferencesOf(SettingsRepository.Keys.DETECT_VIDEOS to false)))
        val settings = repo.mediaDetectionSettings.first()
        assertFalse(settings.enabled)
        assertTrue(settings.images)
        assertTrue(settings.responseScanning)
        assertFalse(settings.detectInBridgedSites)
        assertFalse(settings.allows(DetectedMediaKind.IMAGE))
    }

    @Test fun optionsSurviveMasterSwitchChangesAndRepositoryRecreation() = runBlocking {
        val store = MemoryStore()
        val repo = SettingsRepository(store)
        val chosen = MediaDetectionSettings(images = false, audio = false, responseScanning = false,
            detectInBridgedSites = true)
        repo.setMediaDetectionSettings(chosen)
        repo.setDetectVideos(false)
        assertEquals(chosen.copy(enabled = false), SettingsRepository(store).mediaDetectionSettings.first())
        repo.setDetectVideos(true)
        assertEquals(chosen, SettingsRepository(store).mediaDetectionSettings.first())
        assertTrue(chosen.allows(DetectedMediaKind.VIDEO))
        assertFalse(chosen.allows(DetectedMediaKind.IMAGE))
        assertTrue(chosen.allows(DetectedMediaKind.SUBTITLE))
    }

    @Test fun backupRoundTripAndOlderOptionsKeepDefaults() {
        val chosen = MediaDetectionSettings(videos = false, subtitles = false, domScanning = false)
        assertEquals(chosen, MediaDetectionSettings.decode(MediaDetectionSettings.json.encodeToString(chosen)))
        val old = MediaDetectionSettings.decode("{\"images\":false,\"futureSetting\":true}")
        assertFalse(old.images)
        assertTrue(old.audio)
        assertFalse(old.detectInBridgedSites)
        assertEquals(MediaDetectionSettings(), MediaDetectionSettings.decode("broken"))
    }
}
