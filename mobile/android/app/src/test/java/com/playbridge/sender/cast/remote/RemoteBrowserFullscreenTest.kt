package com.playbridge.sender.cast.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBrowserFullscreenTest {
    private class Fixture(scope: TestScope, initial: RemoteReceiverSnapshot = browserSnapshot()) {
        val transport = FakeNativeRemoteTransport(initial)
        val target = MutableStateFlow<RemoteNativeTarget?>(RemoteNativeTarget(testConfiguration(), "connection-1"))
        val registry = RemoteControllerRegistry(target, transport, scope.backgroundScope)
        val vm = RemoteViewModel(registry, RemoteInputRouter(), { 10L }, scope.backgroundScope)
        fun publish(transform: (RemoteUiState) -> RemoteUiState) {
            val old = transport.snapshot.value
            transport.snapshot.value = old.copy(state = transform(old.state))
        }
        val fullscreen: Boolean? get() = vm.state.value.browser?.fullscreen
    }

    @Test fun toggleRoundTripSendsMaximizeThenRestore() = runTest {
        val f = Fixture(this); runCurrent()
        assertEquals(false, f.fullscreen)
        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.SetBrowserFullscreen(true)))
        assertEquals(true, f.fullscreen)
        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.SetBrowserFullscreen(f.fullscreen != true)))
        assertEquals(false, f.fullscreen)
        assertEquals(listOf("maximize_video", "restore_video"), wireActions(f.transport.commands))
    }

    @Test fun failedFullscreenCommandKeepsThePreviousValue() = runTest {
        val f = Fixture(this); runCurrent()
        f.transport.accepted = false
        assertTrue(f.vm.execute(RemoteAction.SetBrowserFullscreen(true)) is RemoteCommandResult.Failed)
        assertEquals(false, f.fullscreen)
        f.transport.accepted = true
        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        f.transport.accepted = false
        assertTrue(f.vm.execute(RemoteAction.SetBrowserFullscreen(false)) is RemoteCommandResult.Failed)
        assertEquals(true, f.fullscreen)
    }

    @Test fun fullscreenResetsOnContextUrlDisconnectAndSessionChange() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        f.publish { it.copy(context = RemoteContext.Player, browser = null) }
        runCurrent()
        assertFalse(f.fullscreen == true)

        f.publish { browserSnapshot().state }
        runCurrent()
        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        assertEquals(true, f.fullscreen)
        f.publish { it.copy(browser = it.browser?.copy(url = "https://example.test/next")) }
        runCurrent()
        assertEquals(false, f.fullscreen)

        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        f.publish { it.copy(connection = RemoteConnection.Disconnected) }
        runCurrent()
        assertEquals(false, f.fullscreen)

        f.publish { browserSnapshot().state }
        runCurrent()
        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        f.target.value = RemoteNativeTarget(testConfiguration(), "connection-2")
        runCurrent()
        assertEquals(false, f.fullscreen)
    }

    @Test fun tvBackWhileFullscreenSendsBackAndClearsIt() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.Key(RemoteKey.Back)))
        assertEquals(false, f.fullscreen)
        assertEquals(listOf("maximize_video", "back"), wireActions(f.transport.commands))
    }

    @Test fun pageCommandsResetFullscreenButAStatusTitleDoesNot() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        f.publish { it.copy(playback = it.playback?.copy(title = "Other video", reportedPositionMs = 11_000L)) }
        runCurrent()
        assertEquals(true, f.fullscreen)
        f.publish { it.copy(browser = it.browser?.copy(url = null)) }
        runCurrent()
        assertEquals(true, f.fullscreen)

        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.Browser(BrowserAction.Reload)))
        assertEquals(false, f.fullscreen)
        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.Browser(BrowserAction.Forward)))
        assertEquals(false, f.fullscreen)
        f.vm.execute(RemoteAction.SetBrowserFullscreen(true))
        f.vm.execute(RemoteAction.Browser(BrowserAction.CycleSource))
        f.vm.execute(RemoteAction.Browser(BrowserAction.Unmute))
        assertEquals(true, f.fullscreen)
        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.OpenTvBrowser("https://example.test/new")))
        assertEquals(false, f.fullscreen)
    }

    @Test fun reportedFullscreenOverridesTheLocalValue() = runTest {
        val f = Fixture(this); runCurrent()
        f.publish { it.copy(browser = it.browser?.copy(fullscreen = true)) }
        runCurrent()
        assertEquals(true, f.fullscreen)
        f.publish { it.copy(browser = it.browser?.copy(fullscreen = null), playback = it.playback?.copy(reportedPositionMs = 12_000L)) }
        runCurrent()
        assertEquals(true, f.fullscreen)
        f.publish { it.copy(browser = it.browser?.copy(fullscreen = false)) }
        runCurrent()
        assertEquals(false, f.fullscreen)
        f.publish { it.copy(browser = it.browser?.copy(url = "https://example.test/reported", fullscreen = true)) }
        runCurrent()
        assertEquals(true, f.fullscreen)
    }

    @Test fun receiverChangeClearsFullscreenWithoutANewUrl() {
        val reducer = RemoteStateReducer { 0L }
        val raw = browserSnapshot()
        val session = RemoteSessionId(remoteTestEndpoint, 1L)
        val receiver = RemoteReceiver(remoteTestEndpoint, "Fixture TV", RemoteProfile.AndroidTv)
        reducer.observe(raw.copy(state = raw.state.copy(sessionId = session, receiver = receiver)))
        reducer.browserCommandAccepted(RemoteAction.SetBrowserFullscreen(true))
        assertEquals(true, reducer.state.browser?.fullscreen)
        reducer.observe(raw.copy(state = raw.state.copy(sessionId = session, receiver = receiver.copy(endpoint = remoteTestEndpoint.copy(stableId = "other-tv")))))
        assertEquals(false, reducer.state.browser?.fullscreen)
        assertNotEquals(session.endpoint, reducer.state.receiver?.endpoint)
    }
}

private fun browserSnapshot(url: String? = "https://example.test/watch"): RemoteReceiverSnapshot {
    val raw = testSnapshot(RemoteContext.Browser)
    return raw.copy(state = raw.state.copy(browser = raw.state.browser?.copy(url = url)))
}

private fun wireActions(commands: List<String>): List<String> = commands.map { raw ->
    val json = JSONObject(raw)
    val payload = json.getJSONObject("payload")
    when (json.getString("action")) {
        "browser_control" -> payload.getString("action")
        "remote" -> payload.getString("key")
        else -> json.getString("action")
    }
}
