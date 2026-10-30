package com.playbridge.sender.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.playbridge.sender.browser.BridgedApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compile-only for this task; native UI execution requires an authorized device or simulator. */
class BridgedAppInfoDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun colorChangesStayInTheDraftUntilSavedAndCancelDiscardsThem() {
        val app = BridgedApp("https://app.example", "Streams", "https://app.example/", null, tileColorId = "teal")
        val visible = mutableStateOf(true)
        var saved = app
        var saves = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) BridgedAppInfoDialog(
                    app = saved,
                    onSave = { name, home, color ->
                        saved = saved.editing(name, home, color)!!
                        saves++
                        true
                    },
                    onRemove = {},
                    onDismiss = { visible.value = false },
                )
            }
        }
        compose.onNodeWithContentDescription("Teal tile color").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Plum tile color").performClick().assertIsSelected()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals("teal", saved.tileColorId); visible.value = true }
        compose.onNodeWithContentDescription("Teal tile color").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("Automatic tile color").performClick().assertIsSelected()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(null, saved.tileColorId) }
    }
}
