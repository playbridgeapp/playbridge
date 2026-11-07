package com.playbridge.sender.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** Compile-only until device interaction testing is authorized. */
class ReceiverPlaybackFailureDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failureShowsTvAndMessageAndDismissesWithOk() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) ReceiverPlaybackFailureDialog("Living room", "The video stopped loading", { visible.value = false })
            }
        }
        compose.onNodeWithText("Couldn't play on Living room").assertIsDisplayed()
        compose.onNodeWithText("The video stopped loading").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("The video stopped loading").assertDoesNotExist()
    }
}
