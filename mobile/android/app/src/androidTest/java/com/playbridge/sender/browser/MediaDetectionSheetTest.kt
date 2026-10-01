package com.playbridge.sender.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.playbridge.sender.data.settings.MediaDetectionSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaDetectionSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mediaSwitchesAreIndependentAndAdvancedOverrideStartsOff() {
        var saved = MediaDetectionSettings()
        compose.setContent {
            MaterialTheme { MediaDetectionSheet(saved, { saved = it }, {}) }
        }
        compose.onNodeWithText("Images").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("Video detection").assertIsOn()
        compose.onNodeWithText("Audio").assertIsOn()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("Detect on bridged sites").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Response scanning").performScrollTo().performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(saved.images)
            assertFalse(saved.responseScanning)
            assertTrue(saved.videos)
            assertTrue(saved.detectInBridgedSites)
        }
    }

    @Test fun masterSwitchKeepsCategoryPreferences() {
        compose.setContent {
            MaterialTheme { MediaDetectionSheet(MediaDetectionSettings(images = false), {}, {}) }
        }
        compose.onNodeWithText("Automatic detection").performClick().assertIsOff()
        compose.onNodeWithText("Images").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Automatic detection").performClick().assertIsOn()
        compose.onNodeWithText("Images").assertIsOff()
        compose.onNodeWithText("Video detection").assertIsOn()
    }
}
