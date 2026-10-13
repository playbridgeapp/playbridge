package com.playbridge.sender.cast.remote

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteKeyboardRoutingTest {
    private fun receiver(context: RemoteContext = RemoteContext.Browser, engine: RemoteBrowserEngine = RemoteBrowserEngine.WebView): RemoteUiState {
        val raw = testSnapshot(context).state
        return raw.copy(
            sessionId = RemoteSessionId(remoteTestEndpoint, 1L), receiver = testConfiguration().receiver,
            browser = raw.browser?.copy(engine = engine),
            capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, context, RemoteMediaKind.Video, browserEngine = engine),
        )
    }

    private class Channel : TvRemoteChannel {
        override val state = MutableStateFlow(TvRemoteChannelState(
            matchedEndpoint = remoteTestEndpoint, physicalDeviceId = "fixture", paired = true, connected = true,
            keys = setOf(RemoteKey.Enter), text = TextCapabilities(Support.Supported, Support.Supported, Support.Supported),
        ))
        val actions = mutableListOf<RemoteAction>()
        override suspend fun sendKey(key: RemoteKey): RemoteCommandResult { actions += RemoteAction.Key(key); return RemoteCommandResult.Submitted }
        override suspend fun adjustVolume(steps: Int) = RemoteCommandResult.Submitted
        override suspend fun replaceText(text: String): RemoteCommandResult { actions += RemoteAction.ReplaceText(text); return RemoteCommandResult.Submitted }
        override suspend fun deleteText(): RemoteCommandResult { actions += RemoteAction.DeleteText; return RemoteCommandResult.Submitted }
        override suspend fun editText(deletes: Int, text: String, enter: Boolean): RemoteCommandResult {
            actions += RemoteAction.EditTvText(deletes, text, enter)
            return RemoteCommandResult.Submitted
        }
    }

    private class Controller(initial: RemoteUiState) : RemoteController {
        override val sessionId = initial.sessionId!!
        override val snapshot = MutableStateFlow(RemoteReceiverSnapshot(initial))
        val actions = mutableListOf<RemoteAction>()
        var result: RemoteCommandResult = RemoteCommandResult.Submitted
        override suspend fun execute(action: RemoteAction): RemoteCommandResult { actions += action; return result }
    }

    private fun input(action: RemoteAction, route: RemoteKeyboardRoute, context: RemoteContext = RemoteContext.Browser) =
        RemoteAction.KeyboardInput(action, route, context)

    @Test fun bothBrowserEnginesPreferReceiverTextWithAndWithoutFullControl() {
        for (engine in listOf(RemoteBrowserEngine.WebView, RemoteBrowserEngine.Gecko)) {
            val raw = receiver(engine = engine)
            assertEquals(RemoteKeyboardRoute.PlayBridge, raw.keyboardRoute())
            val projected = projectFullControl(raw, raw.capabilities, Channel().state.value)
            assertTrue(projected.fullControlReady)
            assertEquals(RemoteKeyboardRoute.PlayBridge, projected.keyboardRoute())
        }
    }

    @Test fun otherContextsKeepFullControl() {
        for (context in RemoteContext.entries.filter { it != RemoteContext.Browser }) {
            val raw = receiver(context)
            assertEquals(RemoteKeyboardRoute.FullControl, projectFullControl(raw, raw.capabilities, Channel().state.value).keyboardRoute())
        }
    }

    @Test fun missingReceiverOrUnsupportedNativeTextKeepsFullControlDespiteMergedTextSupport() {
        val raw = receiver()
        for (unavailable in listOf(
            raw.copy(connection = RemoteConnection.Disconnected),
            raw.copy(receiver = raw.receiver?.copy(profile = RemoteProfile.External)),
            raw.copy(capabilities = raw.capabilities.copy(text = TextCapabilities(replace = Support.Unsupported))),
            raw.copy(capabilities = raw.capabilities.copy(text = TextCapabilities())),
        )) {
            val projected = projectFullControl(unavailable, unavailable.capabilities, Channel().state.value)
            assertEquals(Support.Supported, projected.capabilities.text.replace)
            assertEquals(RemoteKeyboardRoute.FullControl, projected.keyboardRoute())
        }
    }

    @Test fun browserReplacementClearAndEnterNeverReachFullControl() = runTest {
        val controller = Controller(receiver())
        val channel = Channel()
        val router = RemoteInputRouter(channel)
        val edits = listOf(RemoteAction.ReplaceText("a😀b"), RemoteAction.ReplaceText("a😀"), RemoteAction.ReplaceText(""), RemoteAction.Key(RemoteKey.Enter))
        for (edit in edits) assertEquals(RemoteCommandResult.Submitted, router.execute(controller, input(edit, RemoteKeyboardRoute.PlayBridge)))
        assertEquals(edits, controller.actions)
        assertTrue(channel.actions.isEmpty())
    }

    @Test fun browserWithoutFullControlStillUsesReceiver() = runTest {
        val controller = Controller(receiver())
        val edit = RemoteAction.ReplaceText("browser")
        assertEquals(RemoteCommandResult.Submitted, RemoteInputRouter().execute(controller, input(edit, RemoteKeyboardRoute.PlayBridge)))
        assertEquals(listOf(edit), controller.actions)
    }

    @Test fun otherAppAndReceiverLossUseAcknowledgedAtvEdits() = runTest {
        val channel = Channel()
        val router = RemoteInputRouter(channel)
        val edit = RemoteAction.EditTvText(1, "new", true)
        for (raw in listOf(receiver(RemoteContext.Idle), receiver().copy(connection = RemoteConnection.Disconnected))) {
            val controller = Controller(raw)
            assertEquals(RemoteCommandResult.Submitted, router.execute(controller, input(edit, RemoteKeyboardRoute.FullControl, raw.context)))
            assertTrue(controller.actions.isEmpty())
        }
        assertEquals(listOf(edit, edit), channel.actions)
    }

    @Test fun browserSocketFailureNeverFallsBackToAtv() = runTest {
        val controller = Controller(receiver())
        controller.result = RemoteCommandResult.Failed("closed")
        val channel = Channel()
        val edit = RemoteAction.ReplaceText("once")
        assertEquals(controller.result, RemoteInputRouter(channel).execute(controller, input(edit, RemoteKeyboardRoute.PlayBridge)))
        assertEquals(listOf(edit), controller.actions)
        assertTrue(channel.actions.isEmpty())
    }

    @Test fun staleBrowserAndAtvInputsAreRejectedAfterContextSwitch() = runTest {
        val controller = Controller(receiver())
        val channel = Channel()
        val router = RemoteInputRouter(channel)
        controller.snapshot.value = RemoteReceiverSnapshot(receiver(RemoteContext.Idle))
        assertTrue(router.execute(controller, input(RemoteAction.ReplaceText("old browser"), RemoteKeyboardRoute.PlayBridge)) is RemoteCommandResult.Failed)
        controller.snapshot.value = RemoteReceiverSnapshot(receiver())
        assertTrue(router.execute(controller, input(RemoteAction.EditTvText(2, "old app"), RemoteKeyboardRoute.FullControl, RemoteContext.Idle)) is RemoteCommandResult.Failed)
        assertTrue(controller.actions.isEmpty())
        assertTrue(channel.actions.isEmpty())
    }

    @Test fun staleInputIsRejectedWhenCapabilitiesOrChannelChangeInSameContext() = runTest {
        val controller = Controller(receiver())
        val channel = Channel()
        val router = RemoteInputRouter(channel)
        controller.snapshot.value = RemoteReceiverSnapshot(receiver().let { it.copy(capabilities = it.capabilities.copy(text = TextCapabilities(replace = Support.Unsupported))) })
        assertTrue(router.execute(controller, input(RemoteAction.ReplaceText("stale"), RemoteKeyboardRoute.PlayBridge)) is RemoteCommandResult.Failed)
        channel.state.value = channel.state.value.copy(connected = false)
        assertTrue(router.execute(controller, input(RemoteAction.EditTvText(0, "stale"), RemoteKeyboardRoute.FullControl)) is RemoteCommandResult.Failed)
        assertTrue(controller.actions.isEmpty())
        assertTrue(channel.actions.isEmpty())
    }

    @Test fun keyboardClosesOnContextSwitchAndReopensWithNewRoute() {
        val reducer = RemoteStateReducer { 0L }
        reducer.observe(RemoteReceiverSnapshot(receiver()))
        reducer.applyChannel(Channel().state.value)
        reducer.local(RemoteAction.OpenSheet(RemoteSheet.Keyboard))
        assertNull(reducer.observe(RemoteReceiverSnapshot(receiver(RemoteContext.Idle))).sheet)
        assertEquals(RemoteKeyboardRoute.FullControl, reducer.local(RemoteAction.OpenSheet(RemoteSheet.Keyboard)).keyboardRoute())
        assertNull(reducer.observe(RemoteReceiverSnapshot(receiver())).sheet)
        assertEquals(RemoteKeyboardRoute.PlayBridge, reducer.local(RemoteAction.OpenSheet(RemoteSheet.Keyboard)).keyboardRoute())
    }

    @Test fun routeChangeClosesSheetButConnectingFullControlInBrowserDoesNot() {
        val reducer = RemoteStateReducer { 0L }
        val browser = receiver()
        reducer.observe(RemoteReceiverSnapshot(browser))
        reducer.local(RemoteAction.OpenSheet(RemoteSheet.Keyboard))
        assertEquals(RemoteSheet.Keyboard, reducer.applyChannel(Channel().state.value).sheet)
        val noText = browser.copy(capabilities = browser.capabilities.copy(text = TextCapabilities(replace = Support.Unsupported)))
        assertNull(reducer.observe(RemoteReceiverSnapshot(noText)).sheet)
        reducer.local(RemoteAction.OpenSheet(RemoteSheet.Keyboard))
        assertNull(reducer.applyChannel(Channel().state.value.copy(connected = false)).sheet)
        reducer.observe(RemoteReceiverSnapshot(receiver(RemoteContext.Idle)))
        reducer.local(RemoteAction.OpenSheet(RemoteSheet.Keyboard))
        assertNull(reducer.applyChannel(Channel().state.value).sheet)
    }

    @Test fun discardedAtvBaselineIsNotReplayedOnBrowserOrReopenedEditor() = runTest {
        val controller = Controller(receiver(RemoteContext.Idle))
        val channel = Channel()
        val router = RemoteInputRouter(channel)
        val editor = AtvKeyboardEditor(backgroundScope) {
            router.execute(controller, input(it, RemoteKeyboardRoute.FullControl, RemoteContext.Idle))
        }
        editor.update(TextFieldValue("previous"))
        runCurrent()
        editor.update(TextFieldValue("queued"))
        controller.snapshot.value = RemoteReceiverSnapshot(receiver())
        editor.close()
        runCurrent()
        assertEquals(listOf(RemoteAction.EditTvText(0, "previous")), channel.actions)
        assertTrue(controller.actions.isEmpty())
        controller.snapshot.value = RemoteReceiverSnapshot(receiver(RemoteContext.Idle))
        val reopened = AtvKeyboardEditor(backgroundScope) {
            router.execute(controller, input(it, RemoteKeyboardRoute.FullControl, RemoteContext.Idle))
        }
        assertEquals("", reopened.state.value.value.text)
        reopened.update(TextFieldValue("fresh"))
        runCurrent()
        assertEquals(RemoteAction.EditTvText(0, "fresh"), channel.actions.last())
        assertFalse(reopened.state.value.failed)
        reopened.close()
    }

    @Test fun realNativeControllerEncodesTextAndEnterOnWebSocket() = runTest {
        for (engine in listOf(RemoteBrowserEngine.WebView, RemoteBrowserEngine.Gecko)) {
            val raw = testSnapshot(RemoteContext.Browser).let { it.copy(state = it.state.copy(browser = RemoteBrowserState(engine))) }
            val transport = FakeNativeRemoteTransport(raw)
            val controller = testController(RemoteProfile.AndroidTv, transport, backgroundScope)
            val channel = Channel()
            val router = RemoteInputRouter(channel)
            runCurrent()
            for (edit in listOf(RemoteAction.ReplaceText("hello"), RemoteAction.ReplaceText(""), RemoteAction.Key(RemoteKey.Enter))) {
                assertEquals(RemoteCommandResult.Submitted, router.execute(controller, input(edit, RemoteKeyboardRoute.PlayBridge)))
            }
            assertEquals(3, transport.commands.size)
            assertTrue(transport.commands[0].contains("text:aGVsbG8="))
            assertTrue(transport.commands[1].contains("text:"))
            assertTrue(transport.commands[2].contains("key_enter"))
            assertTrue(channel.actions.isEmpty())
        }
    }
}
