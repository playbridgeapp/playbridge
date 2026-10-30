package com.playbridge.sender.cast.remote

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.remote.ui.RemoteInput
import com.playbridge.sender.connection.QueueCommandResult
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteRouteTest {
    private fun performWaveAction(label: String) {
        val action = compose.onNodeWithContentDescription("Playback seek and volume").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnIdle { assertTrue(action.action()) }
    }

    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun dispose() { scope.cancel() }

    private class Transport : NativeRemoteTransport {
        override val snapshot = MutableStateFlow(RemoteReceiverSnapshot(RemoteUiState(
            connection = RemoteConnection.Connected("Fixture TV", true), context = RemoteContext.Player,
            playback = RemotePlayback(title = "Episode", reportedState = RemotePlaybackState.Playing, reportedPositionMs = 10_000L, durationMs = 120_000L, seekable = Support.Supported),
        )))
        val commands = mutableListOf<String>()
        override fun send(command: String): Boolean { commands += command; return true }
        override fun pointer(event: String, x: Float, y: Float) = true
        override fun endPointerGesture() = true
        override fun stopMirror() = true
        override suspend fun confirmed(requestId: String, command: String, timeoutMs: Long) = QueueCommandResult(true, null)
    }

    @Test fun padTogglePersistsThroughTheViewModelAndReloadsWithoutSendingKeys() {
        val backing = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("remote_pad_toggle_fixture", Context.MODE_PRIVATE)
        assertTrue(backing.edit().clear().commit())
        val store = RemotePresentationPreferences(backing)
        val transport = Transport()
        val registry = RemoteControllerRegistry(MutableStateFlow<RemoteNativeTarget?>(null), transport, scope)
        val vm = RemoteViewModel(registry, RemoteInputRouter(), scope = scope, presentationPreferences = store)
        val actions = mutableListOf<RemoteAction>()
        val fixture = RemoteUiState(
            connection = RemoteConnection.Connected("Fixture TV"), context = RemoteContext.Player,
            capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Video)
                .copy(keyScope = ControlScope.WholeDevice),
        )
        compose.setContent { MaterialTheme {
            val observed by vm.state.collectAsState()
            val state = fixture.copy(preferences = observed.preferences)
            Box(Modifier.size(320.dp, 360.dp)) {
                RemoteInput(state, resolveRemoteLayout(state, RemoteViewport())) { action ->
                    actions += action
                    vm.dispatch(action)
                }
            }
        } }
        compose.onNodeWithContentDescription("Switch to button pad").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("Switch to swipe pad").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(backing.getBoolean("button_pad_v2", false))
            val reloaded = RemotePresentationPreferences(backing)
            assertTrue(reloaded.load().buttonPad)
            val recreated = RemoteViewModel(registry, RemoteInputRouter(), scope = scope, presentationPreferences = reloaded)
            assertTrue(recreated.state.value.preferences.buttonPad)
        }
        compose.onNodeWithContentDescription("Switch to swipe pad").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("Switch to button pad").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(false, RemotePresentationPreferences(backing).load().buttonPad)
            assertEquals(listOf(RemoteAction.SetButtonPad(true), RemoteAction.SetButtonPad(false)), actions)
            assertTrue(transport.commands.isEmpty())
        }
    }

    @Test fun nativeAdapterKeepsStageOneControlsAndUsesOneViewModelOverlayAndEffects() {
        val transport = Transport()
        val endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "fixture")
        val targets = MutableStateFlow<RemoteNativeTarget?>(RemoteNativeTarget(NativeRemoteConfiguration(RemoteReceiver(endpoint, "Fixture TV", RemoteProfile.AndroidTv), listOf("exo"))))
        val vm = RemoteViewModel(RemoteControllerRegistry(targets, transport, scope), RemoteInputRouter(), scope = scope)
        compose.setContent { MaterialTheme { RemoteRoute({}, Unit, { emptyList() }, viewModel = vm) } }
        compose.onNodeWithText("Context").assertDoesNotExist()
        compose.onNodeWithTag("remote.input").assertDoesNotExist()
        compose.onNodeWithText("Touchpad").assertDoesNotExist()
        compose.onNodeWithContentDescription("TV Home", substring = true).assertDoesNotExist()
        performWaveAction("Pause")
        compose.onNodeWithContentDescription("Playback seek and volume").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused"))
        compose.runOnIdle { vm.dispatch(RemoteAction.SeekBy(10_000L)); vm.dispatch(RemoteAction.SeekBy(10_000L)) }
        compose.onNodeWithContentDescription("Playback seek and volume").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(30_000f, 0f..120_000f),
        ))
        compose.runOnIdle {
            val controls = transport.commands.map { JSONObject(it) }.filter { it.optString("action") == "control" }.map { it.getJSONObject("payload").getString("command") }
            assertEquals(listOf("pause", "seek_to:20000", "seek_to:30000"), controls)
            assertEquals(10_000L, transport.snapshot.value.state.playback?.reportedPositionMs)
        }
        compose.onNodeWithContentDescription("Stop playback").performClick()
        compose.onNodeWithText("Nothing playing on Fixture TV").assertIsDisplayed()
        compose.onNodeWithText("Browse Library").assertDoesNotExist()
        compose.onNodeWithText("Open browser").assertDoesNotExist()
    }
    private class ExternalTransport(val kind: TargetKind) : ExternalRemoteTransport {
        val identity = Any()
        val endpoint = EndpointKey(if (kind == TargetKind.ROKU) CastProtocol.ROKU else CastProtocol.GOOGLE_CAST, "external-fixture")
        val receiver = RemoteReceiver(endpoint, "External TV", RemoteProfile.External)
        val target = RemoteExternalTarget(receiver, kind, identity)
        val caps = externalRemoteCapabilities(kind, buildSet {
            addAll(setOf(Capability.PLAY_PAUSE, Capability.STOP, Capability.VOLUME))
            if (kind == TargetKind.ROKU) add(Capability.REMOTE) else add(Capability.SEEK)
        })
        override val snapshot = MutableStateFlow(ExternalRemoteSnapshot(identity, RemoteReceiverSnapshot(RemoteUiState(
            receiver = receiver, connection = RemoteConnection.Connected(receiver.name),
            context = if (kind == TargetKind.ROKU) RemoteContext.Idle else RemoteContext.Player, capabilities = caps,
            playback = RemotePlayback(title = "Episode", reportedState = if (kind == TargetKind.ROKU) RemotePlaybackState.Idle else RemotePlaybackState.Playing, reportedPositionMs = 10_000L, durationMs = 120_000L, seekable = caps.transport.seekAbsolute),
            queue = RemoteQueueState(items = listOf(RemoteQueueItem(0, "One"), RemoteQueueItem(1, "Two")), phoneOwned = true),
            linkedWebsite = LinkedWebsiteState(true, "Linked app"),
        ), RemoteObservation(mediaGeneration = 8L))))
        val commands = mutableListOf<RemoteAction>()
        override fun isCurrent(identity: Any, loadGeneration: Long) = this.identity === identity && loadGeneration == 8L
        override fun submit(identity: Any, loadGeneration: Long, action: RemoteAction): RemoteCommandResult {
            commands += action
            if (action == RemoteAction.StopPlayback) {
                val old = snapshot.value
                snapshot.value = old.copy(receiverSnapshot = old.receiverSnapshot.copy(state = old.receiverSnapshot.state.copy(context = RemoteContext.Idle, playback = old.receiverSnapshot.state.playback?.copy(reportedState = RemotePlaybackState.Idle))))
            }
            return RemoteCommandResult.Submitted
        }
    }
    @Test fun castAdapterPreservesBadgeNoInputTabsReadOnlyQueueAndSharedPlaybackIntent() {
        val external = ExternalTransport(TargetKind.GOOGLE_CAST)
        val targets = MutableStateFlow<RemoteTarget?>(external.target)
        val vm = RemoteViewModel(RemoteControllerRegistry(targets, Transport(), scope, external), RemoteInputRouter(), scope = scope)
        val effects = mutableListOf<RemoteEffect>()
        compose.setContent { MaterialTheme { RemoteRoute(effects::add, Unit, { emptyList() }, viewModel = vm) } }
        compose.onNodeWithText("Casting to External TV").assertIsDisplayed()
        compose.onNodeWithText("Google Cast").assertIsDisplayed()
        compose.onNodeWithText("Context").assertDoesNotExist()
        compose.onNodeWithText("D-Pad").assertDoesNotExist()
        compose.onNodeWithText("Touchpad").assertDoesNotExist()
        compose.onNodeWithContentDescription("Previous episode").assertDoesNotExist()
        compose.onNodeWithContentDescription("Next episode").assertDoesNotExist()
        compose.onNodeWithContentDescription("TV Home", substring = true).assertDoesNotExist()
        performWaveAction("Pause")
        compose.onNodeWithContentDescription("Playback seek and volume").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused"))
        performWaveAction("Seek forward 10 seconds")
        compose.onNodeWithContentDescription("Playback seek and volume").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(20_000f, 0f..120_000f),
        ))
        compose.onNodeWithContentDescription("TV volume down").performClick()
        compose.onNodeWithText("Unlink").performClick()
        compose.runOnIdle {
            assertEquals(listOf(RemoteAction.SetPlaying(false), RemoteAction.SeekTo(20_000L, RemoteLearnedGesture.SEEK), RemoteAction.AdjustVolume(-1)), external.commands)
            assertEquals(RemoteEffect.UnlinkWebsite, effects.last())
        }
        compose.onNodeWithContentDescription("Stop playback").performClick()
        compose.onNodeWithText("Nothing playing on External TV").assertIsDisplayed()
        compose.onNodeWithText("Browse Library").assertDoesNotExist()
        compose.onNodeWithText("Open browser").assertDoesNotExist()
    }
    @Test fun rokuIdleAdapterKeepsPadAndHomeWithoutSeekOrNativeSocketCommands() {
        val external = ExternalTransport(TargetKind.ROKU)
        val native = Transport()
        val targets = MutableStateFlow<RemoteTarget?>(external.target)
        val vm = RemoteViewModel(RemoteControllerRegistry(targets, native, scope, external), RemoteInputRouter(), scope = scope)
        val effects = mutableListOf<RemoteEffect>()
        compose.setContent { MaterialTheme { RemoteRoute(effects::add, Unit, { emptyList() }, viewModel = vm) } }
        compose.onNodeWithText("Roku").assertIsDisplayed()
        // Roku idle is whole-device, so the pad fills INPUT instead of the empty sentence.
        compose.runOnIdle { vm.dispatch(RemoteAction.SetButtonPad(true)) }
        compose.onNodeWithContentDescription("TV Left").performClick()
        compose.onNodeWithContentDescription("TV Home").performClick()
        compose.onNodeWithContentDescription("Seek forward 10 seconds").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back", substring = false).performClick()
        compose.runOnIdle {
            assertEquals(listOf(RemoteAction.Key(RemoteKey.Left), RemoteAction.Key(RemoteKey.Home)), external.commands)
            assertTrue(native.commands.isEmpty())
            assertEquals(RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.Origin), effects.last())
        }
    }

}
