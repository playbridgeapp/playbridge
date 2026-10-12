package com.playbridge.sender.cast.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.ui.RemoteInput
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteInputGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun gesturePadTapSendsOkAndSwipeSendsOneArrow() {
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
        compose.onNodeWithTag("remote.gesturePad").performTouchInput {
            swipe(center, Offset(center.x, center.y - 160f), durationMillis = 240)
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
