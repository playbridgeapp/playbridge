package com.playbridge.sender.cast.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.playbridge.sender.cast.RemoteSettingsGroup
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RemoteSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    private class Preferences : RemotePresentationPreferenceStore {
        override val state = MutableStateFlow(RemotePreferences())
        override fun load() = state.value
        override fun save(preferences: RemotePreferences) { state.value = preferences }
    }

    @Test fun intensitySamplesOnceAndDisablesWithSwitchInLightTheme() {
        val store = Preferences()
        val samples = mutableListOf<RemoteHapticLevel>()
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { RemoteSettingsGroup(store, samples::add) } }
        compose.onNodeWithContentDescription("Haptic feedback").assertIsOn()
        compose.onNodeWithContentDescription("Haptic intensity Light").assertIsSelected()
        compose.onNodeWithContentDescription("Haptic intensity Strong").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(RemoteHapticLevel.Strong), samples) }
        compose.onNodeWithContentDescription("Haptic feedback").performClick().assertIsOff()
        RemoteHapticLevel.entries.forEach { compose.onNodeWithContentDescription("Haptic intensity ${it.name}").assertIsNotEnabled() }
        compose.onNodeWithContentDescription("Haptic intensity Medium").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(RemoteHapticLevel.Strong, store.load().hapticsLevel)
            assertEquals(listOf(RemoteHapticLevel.Strong), samples)
        }
        compose.onNodeWithContentDescription("Haptic feedback").performClick()
        compose.onNodeWithContentDescription("Haptic intensity Medium").performClick()
        compose.runOnIdle { assertEquals(listOf(RemoteHapticLevel.Strong, RemoteHapticLevel.Medium), samples) }
    }

    @Test fun settingsKeepHapticsButHaveNoButtonPadWhileExternalChangesRefreshInDarkTheme() {
        val store = Preferences()
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { RemoteSettingsGroup(store, {}) } }
        compose.onNodeWithContentDescription("Button pad").assertDoesNotExist()
        compose.runOnIdle {
            store.update { it.copy(buttonPad = true, hapticsEnabled = false, hapticsLevel = RemoteHapticLevel.Medium) }
        }
        compose.onNodeWithContentDescription("Haptic feedback").assertIsOff()
        compose.onNodeWithContentDescription("Haptic intensity Medium").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Button pad").assertDoesNotExist()
    }
}
