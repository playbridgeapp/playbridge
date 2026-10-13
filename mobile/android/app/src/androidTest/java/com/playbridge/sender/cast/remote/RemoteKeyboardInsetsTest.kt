package com.playbridge.sender.cast.remote

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.filters.SdkSuppress
import com.playbridge.sender.cast.remote.ui.KeyboardSheetContent
import com.playbridge.sender.cast.remote.ui.RemoteKeyboardSheet
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compile only for Fix 5; the IME regression requires a phone/emulator with a software keyboard. */
class RemoteKeyboardInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sheetSkipsPartialExpansionAndFocusesTheDisplayedField() {
        compose.setContent { MaterialTheme { RemoteKeyboardSheet(dispatch = {}) } }
        compose.onNodeWithTag("remote.keyboardField").assertIsDisplayed().assertIsFocused()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand), useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun contentWaitsForTheExpandedSheetBeforeRequestingFocus() {
        val ready = mutableStateOf(false)
        compose.setContent { MaterialTheme { KeyboardSheetContent(dispatch = {}, focusReady = ready.value) } }
        compose.onNodeWithTag("remote.keyboardField").assertIsNotFocused()
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithTag("remote.keyboardField").assertIsFocused().assertIsDisplayed()
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun fieldLimitMessageAndActionsStayAboveVisibleImeInEdgeToEdgeResizeWindow() {
        lateinit var view: View
        compose.runOnIdle {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            view = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    KeyboardSheetContent(dispatch = {}, imeKeys = true, execute = { RemoteCommandResult.Submitted })
                }
            }
        }
        val field = compose.onNodeWithTag("remote.keyboardField")
        field.performTextInput("x".repeat(64))
        field.performTextInput("x")
        compose.runOnIdle {
            WindowCompat.getInsetsController(compose.activity.window, view).show(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        val imeTop = compose.runOnIdle {
            view.height - ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        val density = view.resources.displayMetrics.density
        listOf(
            field,
            compose.onNodeWithText("Text is limited to 64 characters."),
            compose.onNodeWithText("Delete"),
            compose.onNodeWithText("Enter"),
            compose.onNodeWithContentDescription("Clear text"),
        ).forEach { node ->
            node.assertIsDisplayed()
            assertTrue("Editing controls must sit above the IME", node.getUnclippedBoundsInRoot().bottom.value * density <= imeTop + 1f)
        }
    }
}
