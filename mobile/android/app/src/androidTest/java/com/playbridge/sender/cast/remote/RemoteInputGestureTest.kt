package com.playbridge.sender.cast.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.ui.RemoteGesturePad
import com.playbridge.sender.cast.remote.ui.RemoteInput
import com.playbridge.sender.cast.remote.ui.padModeToggleBounds
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteInputGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modeToggleHasAccessibleLabelsAndTouchTargetAndSendsOnlyPreferenceActions() {
        val actions = mutableListOf<RemoteAction>()
        val current = mutableStateOf(fixture(wholeDevice = true).first)
        compose.setContent { MaterialTheme {
            Box(Modifier.size(320.dp, 360.dp)) {
                RemoteInput(current.value, resolveRemoteLayout(current.value, RemoteViewport())) { action ->
                    actions += action
                    if (action is RemoteAction.SetButtonPad) current.value = current.value.copy(preferences = current.value.preferences.copy(buttonPad = action.enabled))
                }
            }
        } }
        compose.onNodeWithContentDescription("Switch to button pad")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Swipe pad"))
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .performTouchInput { click(Offset(1f, 1f)) }
        compose.onNodeWithTag("remote.buttonPad").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to swipe pad")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Button pad"))
            .performTouchInput { click(center) }
        compose.onNodeWithTag("remote.gesturePad").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(RemoteAction.SetButtonPad(true), RemoteAction.SetButtonPad(false)), actions) }
    }

    @Test fun fullControlPadHasTheToggleWithoutANativePlaybackConnection() {
        val full = fixture(wholeDevice = true).first.copy(
            context = RemoteContext.Unknown, connection = RemoteConnection.Disconnected, playback = null,
            fullControl = FullControlStatus(
                connected = true, paired = true, available = true, powerOn = true,
                keys = setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok),
            ),
        )
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 360.dp)) {
            RemoteInput(full, resolveRemoteLayout(full, RemoteViewport()), actions::add)
        } } }
        compose.onNodeWithContentDescription("Switch to button pad").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(RemoteAction.SetButtonPad(true)), actions) }
    }

    @Test fun pointerModeHasNoPadModeToggleAndBrowserPadToggleDoesNotOverlapModeSwitch() {
        val current = mutableStateOf(fixture(context = RemoteContext.Browser, wholeDevice = true).first)
        compose.setContent { MaterialTheme { Box(Modifier.size(320.dp, 360.dp)) {
            RemoteInput(current.value, resolveRemoteLayout(current.value, RemoteViewport())) { action ->
                if (action is RemoteAction.SelectInputMode) current.value = current.value.copy(inputMode = action.mode)
            }
        } } }
        compose.onNodeWithTag("remote.padModeToggle").assertDoesNotExist()
        compose.onNodeWithText("Pad").performClick()
        val toggle = compose.onNodeWithTag("remote.padModeToggle").fetchSemanticsNode().boundsInRoot
        val modeSwitch = compose.onNodeWithTag("remote.padPointerSwitch").fetchSemanticsNode().boundsInRoot
        assertFalse(toggle.overlaps(modeSwitch))
    }

    @Test fun gestureAdapterRejectsDownsAndCancelsMovesIntoToggleTargetInBothDirections() {
        val keys = mutableListOf<String>()
        val direction = mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                Box(Modifier.size(320.dp, 360.dp)) { RemoteGesturePad(true, keys::add, excludeModeToggle = true) }
            }
        } }
        for (rtl in listOf(false, true)) {
            compose.runOnIdle { direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr }
            val pad = compose.onNodeWithTag("remote.gesturePad")
            val bounds = padModeToggleBounds(pad.fetchSemanticsNode().boundsInRoot.width, compose.density.density, rtl)
            pad.performTouchInput { click(bounds.center) }
            pad.performTouchInput {
                down(bounds.center + Offset(0f, bounds.height))
                moveTo(bounds.center)
                advanceEventTime(600)
                up()
            }
            compose.runOnIdle { assertTrue(keys.isEmpty()) }
        }
    }

    @Test fun gesturePadTapSendsOkAndFastFortyDpSwipeSendsOneArrow() {
        val actions = mutableListOf<RemoteAction>()
        val (state, layout) = fixture(buttonPad = false, wholeDevice = true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 360.dp)) { RemoteInput(state, layout, { actions += it }) }
            }
        }
        compose.onNodeWithTag("remote.gesturePad").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf(RemoteAction.Key(RemoteKey.Ok)), actions.toList())

        actions.clear()
        val swipeDistance = with(compose.density) { 40.dp.toPx() }
        compose.onNodeWithTag("remote.gesturePad").performTouchInput {
            swipe(center, Offset(center.x, center.y - swipeDistance), durationMillis = 40)
        }
        compose.waitForIdle()
        assertEquals(listOf(RemoteAction.Key(RemoteKey.Up)), actions.toList())
    }

    @Test fun gesturePadCustomActionsSendDirectionsWithoutASwipe() {
        val actions = mutableListOf<RemoteAction>()
        val (state, layout) = fixture(buttonPad = false, wholeDevice = true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 360.dp)) { RemoteInput(state, layout, { actions += it }) }
            }
        }
        val custom = compose.onNodeWithContentDescription("Gesture pad").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Up", "Down", "Left", "Right", "OK"), custom.map { it.label })
        assertTrue(custom.first { it.label == "Left" }.action())
        compose.waitForIdle()
        assertEquals(listOf(RemoteAction.Key(RemoteKey.Left)), actions.toList())
    }

    @Test fun switchShowsOnlyWhenPadAndPointerAreBothUseful() {
        val actions = mutableListOf<RemoteAction>()
        val player = fixture(buttonPad = false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 360.dp)) { RemoteInput(player.first, player.second, { actions += it }) }
            }
        }
        compose.onNodeWithTag("remote.padPointerSwitch").assertDoesNotExist()
        compose.onNodeWithTag("remote.gesturePad").assertDoesNotExist()

        val browser = fixture(context = RemoteContext.Browser, buttonPad = false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 420.dp)) { RemoteInput(browser.first, browser.second, { actions += it }) }
            }
        }
        compose.onNodeWithTag("remote.padPointerSwitch").assertDoesNotExist()
        compose.onNodeWithTag("remote.pointer").assertIsDisplayed()

        actions.clear()
        val both = fixture(context = RemoteContext.Browser, buttonPad = false, wholeDevice = true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 420.dp)) { RemoteInput(both.first, both.second, { actions += it }) }
            }
        }
        compose.onNodeWithTag("remote.padPointerSwitch").assertIsDisplayed()
        compose.onNodeWithTag("remote.pointer").assertIsDisplayed()
        compose.onNodeWithText("Pad").performClick()
        assertEquals(RemoteAction.SelectInputMode(RemoteInputMode.Pad), actions.single())
    }

    @Test fun playerVideoHasNoPadEntry() {
        val (state, layout) = fixture()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 360.dp)) { RemoteInput(state, layout, {}) }
            }
        }
        compose.onNodeWithTag("remote.gesturePad").assertDoesNotExist()
        compose.onNodeWithTag("remote.buttonPad").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pad settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open pad").assertDoesNotExist()
        compose.onNodeWithTag("remote.padModeToggle").assertDoesNotExist()
    }

    @Test fun buttonPadScalesSoDownStaysInsideAShortArea() {
        val (state, layout) = fixture(buttonPad = true, wholeDevice = true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(240.dp, 160.dp)) { RemoteInput(state, layout, {}) }
            }
        }
        val root = compose.onNodeWithTag("remote.buttonPad").fetchSemanticsNode().boundsInRoot
        val down = compose.onNodeWithContentDescription("TV Down")
        down.assertIsDisplayed()
        val bounds = down.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= root.top - 1f)
        assertTrue(bounds.bottom <= root.bottom + 1f)
        assertTrue(bounds.left >= root.left - 1f)
        assertTrue(bounds.right <= root.right + 1f)
        compose.onNodeWithTag("remote.gesturePad").assertDoesNotExist()
        val toggle = compose.onNodeWithTag("remote.padModeToggle").fetchSemanticsNode().boundsInRoot
        listOf("TV Up", "TV Left", "TV Right", "TV Down").forEach {
            assertFalse(toggle.overlaps(compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot))
        }
    }

    @Test fun minimumHeightButtonPadKeepsToggleClearOfDirectionsInBothLayouts() {
        val (state, layout) = fixture(buttonPad = true, wholeDevice = true)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                // A 240 x 160 dp input area after the section card's 12 dp padding.
                Box(Modifier.size(216.dp, 136.dp)) { RemoteInput(state, layout, {}) }
            }
        } }
        for (rtl in listOf(false, true)) {
            compose.runOnIdle { direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr }
            val toggle = compose.onNodeWithTag("remote.padModeToggle").fetchSemanticsNode().boundsInRoot
            listOf("TV Up", "TV Left", "TV Right", "TV Down").forEach {
                assertFalse(toggle.overlaps(compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot))
            }
        }
    }

    private fun fixture(
        context: RemoteContext = RemoteContext.Player,
        kind: RemoteMediaKind = RemoteMediaKind.Video,
        buttonPad: Boolean = false,
        wholeDevice: Boolean = false,
    ): Pair<RemoteUiState, RemoteLayoutSpec> {
        val endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "pad")
        val capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, context, kind).let {
            if (wholeDevice) it.copy(keyScope = ControlScope.WholeDevice) else it
        }
        val state = RemoteUiState(
            receiver = RemoteReceiver(endpoint, "TV", RemoteProfile.AndroidTv),
            connection = RemoteConnection.Connected("TV"),
            context = context,
            capabilities = capabilities,
            playback = RemotePlayback(title = "Fixture", mediaKind = kind, reportedState = RemotePlaybackState.Paused),
            preferences = RemotePreferences(buttonPad = buttonPad),
        )
        return state to resolveRemoteLayout(state, RemoteViewport())
    }
}
