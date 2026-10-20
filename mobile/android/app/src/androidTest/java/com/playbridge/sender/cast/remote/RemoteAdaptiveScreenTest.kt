package com.playbridge.sender.cast.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.ui.RemoteScreen
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteAdaptiveScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun player(wholeDevice: Boolean = false) = RemoteUiState(
        receiver = RemoteReceiver(EndpointKey(CastProtocol.PLAYBRIDGE, "adaptive"), "Fixture TV", RemoteProfile.AndroidTv),
        connection = RemoteConnection.Connected("Fixture TV"), context = RemoteContext.Player,
        capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Video).let {
            if (wholeDevice) it.copy(keyScope = ControlScope.WholeDevice) else it
        },
        playback = RemotePlayback(title = "Episode", reportedState = RemotePlaybackState.Paused,
            durationMs = 120_000L, seekable = Support.Supported),
    )

    @Test fun portraitSectionOrderAndInputMinimum() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                MaterialTheme { Box(Modifier.requiredSize(412.dp, 892.dp)) {
                    RemoteScreen(player().copy(queue = RemoteQueueState(items = List(3) { RemoteQueueItem(it, "Episode $it") })), {})
                } }
            }
        }
        val order = listOf("header", "info", "seek", "nav")
            .map { compose.onNodeWithTag("remote.$it").fetchSemanticsNode().boundsInRoot }
        order.zipWithNext().forEach { (before, after) -> assertTrue(before.bottom <= after.top + 1f) }
        compose.onNodeWithTag("remote.input").assertDoesNotExist()
        compose.onNodeWithTag("remote.nav").assertIsDisplayed()
        compose.onNodeWithTag("remote.gesturePad").assertDoesNotExist()
        compose.onNodeWithText("Context").assertDoesNotExist()
        compose.onNodeWithTag("remote.upNext.compact").assertDoesNotExist()
        compose.onNodeWithText("Up Next").assertIsDisplayed()
        compose.onNodeWithText("Episode 0").assertIsDisplayed()
    }

    @Test fun navAlwaysMountedAcrossBrowserVideoNoVideoAndIdle() {
        val state = mutableStateOf(player())
        compose.setContent { MaterialTheme { RemoteScreen(state.value, {}) } }
        for (context in listOf(RemoteContext.Player, RemoteContext.Browser, RemoteContext.Idle)) {
            compose.runOnIdle { state.value = state.value.copy(context = context,
                capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, context, RemoteMediaKind.Video)) }
            compose.onNodeWithTag("remote.nav").assertIsDisplayed()
            if (context == RemoteContext.Browser) {
                compose.onNodeWithText("Context").assertDoesNotExist()
                compose.onNodeWithTag("remote.pointer").assertIsDisplayed()
                compose.onNodeWithContentDescription("TV Left").assertDoesNotExist()
            }
        }
        compose.runOnIdle { state.value = state.value.copy(context = RemoteContext.Browser, playback = null,
            capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, RemoteContext.Browser, RemoteMediaKind.Video)) }
        compose.onNodeWithTag("remote.toolbar").assertIsDisplayed()
        compose.onNodeWithTag("remote.seek").assertDoesNotExist()
        compose.onNodeWithTag("remote.transport").assertDoesNotExist()
        compose.onNodeWithTag("remote.nav").assertIsDisplayed()
        // These profiles have no usable player input; omit the whole card, also in landscape.
        for (profile in listOf(RemoteProfile.AppleTv, RemoteProfile.Desktop, RemoteProfile.Cli, RemoteProfile.External)) {
            compose.runOnIdle {
                state.value = player().copy(receiver = player().receiver?.copy(profile = profile),
                    capabilities = nativeRemoteCapabilities(profile, RemoteContext.Player, RemoteMediaKind.Video))
            }
            compose.onNodeWithTag("remote.input").assertDoesNotExist()
            compose.onNodeWithText("Context").assertDoesNotExist()
            if (profile == RemoteProfile.Desktop) {
                compose.onNodeWithTag("remote.nav").assertIsDisplayed()
                compose.onNodeWithContentDescription("TV volume up").assertIsDisplayed()
                compose.onNodeWithContentDescription("TV Home", substring = true).assertDoesNotExist()
            } else {
                compose.onNodeWithTag("remote.nav").assertDoesNotExist()
            }
        }
    }

    @Test fun landscapeInputIsLeftOfMediaAndNavSpansBothColumns() {
        compose.setContent {
            MaterialTheme { Box(Modifier.requiredSize(800.dp, 420.dp)) { RemoteScreen(player(wholeDevice = true), {}) } }
        }
        compose.onNodeWithTag("remote.columns").assertIsDisplayed()
        val input = compose.onNodeWithTag("remote.input").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("remote.header").fetchSemanticsNode().boundsInRoot
        val nav = compose.onNodeWithTag("remote.nav").fetchSemanticsNode().boundsInRoot
        assertTrue(input.right < header.left)
        assertTrue(nav.width > input.width * 1.5f)
    }

    @Test fun disconnectedChipAndRetryUseTypedActionWhileReceiverControlsDisable() {
        val actions = mutableListOf<RemoteAction>()
        compose.setContent { MaterialTheme { RemoteScreen(player().copy(connection = RemoteConnection.Disconnected), actions::add) } }
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithContentDescription("More player settings").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Playback seek and volume").assertIsNotEnabled()
        compose.onNodeWithContentDescription("TV volume up", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("remote.nav").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf(RemoteAction.RetryConnection, RemoteAction.BackToOrigin), actions) }
    }

    @Test fun shortPortraitCompactUpNextOpensExistingEpisodeList() {
        val state = mutableStateOf(player().copy(queue = RemoteQueueState(items = listOf(RemoteQueueItem(0, "First episode"), RemoteQueueItem(1, "Second episode")))))
        compose.setContent {
            MaterialTheme { Box(Modifier.requiredSize(360.dp, 440.dp)) {
                RemoteScreen(state.value, { action ->
                    state.value = state.value.copy(sheet = when (action) {
                        is RemoteAction.OpenSheet -> action.sheet
                        RemoteAction.CloseSheet -> null
                        else -> state.value.sheet
                    })
                })
            } }
        }
        compose.onNodeWithTag("remote.upNext").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("remote.upNext.compact").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText("Up next (2) ›").performClick()
        compose.onNodeWithText("First episode").assertIsDisplayed()
        compose.onNodeWithText("Second episode").assertIsDisplayed()
        compose.onNodeWithTag("remote.nav").assertExists()
    }

    @Test fun twoHundredPercentFontScrollsUpperWithMinimumInputAndPinnedNav() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme { Box(Modifier.requiredSize(360.dp, 600.dp)) { RemoteScreen(player(), {}) } }
            }
        }
        compose.onNodeWithTag("remote.input").assertDoesNotExist()
        compose.onNodeWithTag("remote.seek").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("remote.nav").assertIsDisplayed()
        compose.onNodeWithTag("remote.transport").assertDoesNotExist()
        compose.onNodeWithTag("remote.seek").assertIsDisplayed()
    }

    @Test fun browserMoreRemainsReachableAtTwoHundredPercentFont() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme { Box(Modifier.requiredSize(320.dp, 600.dp)) {
                    RemoteScreen(player().copy(context = RemoteContext.Browser, playback = null), {})
                } }
            }
        }
        compose.onNodeWithContentDescription("More player settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("remote.nav").assertIsDisplayed()
    }
    @Test fun defaultPlayerHasNoInputAndSeekSitsDirectlyAboveNav() {
        compose.setContent { MaterialTheme { Box(Modifier.requiredSize(412.dp, 892.dp)) {
            RemoteScreen(player(), {})
        } } }
        compose.onNodeWithTag("remote.input").assertDoesNotExist()
        compose.onNodeWithTag("remote.transport").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remote menu").assertDoesNotExist()
        compose.onNodeWithContentDescription("TV Back", substring = true).assertIsDisplayed()
        val seek = compose.onNodeWithTag("remote.seek").fetchSemanticsNode().boundsInRoot
        val nav = compose.onNodeWithTag("remote.nav").fetchSemanticsNode().boundsInRoot
        assertTrue(seek.bottom <= nav.top)
        val density = compose.density.density
        assertTrue(nav.top - seek.bottom <= 9f * density)
    }

}
