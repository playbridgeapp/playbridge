package com.playbridge.sender.cast.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playbridge.sender.cast.remote.ui.RemoteQueueRow
import com.playbridge.sender.cast.remote.ui.RemoteQueueSheet
import com.playbridge.sender.cast.remote.ui.RemoteScreen
import com.playbridge.sender.data.library.PlaybackResumeEntity
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RemoteIntegrationFixTest {
    @get:Rule val compose = createComposeRule()
    private fun state(): RemoteUiState {
        val current = RemoteQueueItem(0, "Current", "current", season = 1, episode = 1, tmdbId = "100")
        val next = RemoteQueueItem(1, "Next", "next", season = 1, episode = 2, tmdbId = "100")
        return RemoteUiState(
            receiver = RemoteReceiver(EndpointKey(CastProtocol.PLAYBRIDGE, "integration"), "TV", RemoteProfile.AndroidTv),
            connection = RemoteConnection.Connected("TV"), context = RemoteContext.Player,
            capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Video).copy(keyScope = ControlScope.WholeDevice),
            playback = RemotePlayback(title = "Current", reportedState = RemotePlaybackState.Paused),
            queue = RemoteQueueState(currentItemId = "current", items = listOf(current, next)),
        )
    }
    @Test fun buttonPadChangesDirectlyOnPadAndIsAbsentFromPlayerSheet() {
        val reducer = RemoteStateReducer { 0L }
        val state = mutableStateOf(reducer.observe(RemoteReceiverSnapshot(state())))
        val dispatch: (RemoteAction) -> Unit = { state.value = reducer.local(it) }
        compose.setContent { MaterialTheme { RemoteScreen(state.value, dispatch) } }
        compose.onNodeWithTag("remote.gesturePad").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pad settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Switch to button pad").performClick()
        compose.onNodeWithTag("remote.buttonPad").assertIsDisplayed()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithContentDescription("Button D-pad").assertDoesNotExist()
        compose.onNodeWithContentDescription("Button pad").assertDoesNotExist()
        compose.runOnIdle { dispatch(RemoteAction.CloseSheet) }
        compose.onNodeWithContentDescription("Switch to swipe pad").performClick()
        compose.onNodeWithTag("remote.gesturePad").assertIsDisplayed()
    }
    @Test fun storedWatchedBadgeAppearsInBothUpNextAndAllSheet() {
        val resume = PlaybackResumeEntity("tmdb:100:1:2", 100, "tv", 1, 2, positionMs = 900, durationMs = 1000)
        val state = state().copy(queueProgress = mapOf(resume.contentKey to RemoteQueueProgress(resume)))
        val sheet = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            if (sheet.value) RemoteQueueSheet(state, {}) else RemoteQueueRow(state, false, {})
        } }
        compose.onNodeWithContentDescription("Watched").assertIsDisplayed()
        compose.runOnIdle { sheet.value = true }
        compose.onNodeWithContentDescription("Watched").assertIsDisplayed()
    }
    @Test fun removeOnlyCapabilityNeverOffersPlayNextOrCurrentRemoval() {
        val base = state()
        val state = base.copy(capabilities = base.capabilities.copy(queue = base.capabilities.queue.copy(remove = Support.Supported, move = Support.Unknown)))
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { MaterialTheme { RemoteQueueSheet(state, { actions += it }) } }
        compose.onNodeWithContentDescription("Episode options").performClick()
        compose.onNodeWithText("Play next").assertDoesNotExist()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertEquals(listOf(RemoteAction.RemoveQueueItem("next")), actions) }
    }
}
