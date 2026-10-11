package com.playbridge.sender.cast.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputRouterTest {
    private class CastController : RemoteController {
        override val sessionId = RemoteSessionId(remoteTestEndpoint, 1L)
        override val snapshot = MutableStateFlow(RemoteReceiverSnapshot())
        val actions = mutableListOf<RemoteAction>()
        override suspend fun execute(action: RemoteAction): RemoteCommandResult { actions += action; return RemoteCommandResult.Submitted }
    }
    private class Channel : TvRemoteChannel {
        override val state = MutableStateFlow(TvRemoteChannelState(remoteTestEndpoint, "physical-tv", true, true, setOf(RemoteKey.Home), VolumeCapabilities(adjust = Support.Supported), TextCapabilities(Support.Supported, Support.Supported)))
        val actions = mutableListOf<RemoteAction>()
        var result: RemoteCommandResult = RemoteCommandResult.Submitted
        private fun send(action: RemoteAction): RemoteCommandResult { actions += action; return result }
        override suspend fun sendKey(key: RemoteKey) = send(RemoteAction.Key(key))
        override suspend fun adjustVolume(steps: Int) = send(RemoteAction.AdjustVolume(steps))
        override suspend fun replaceText(text: String) = send(RemoteAction.ReplaceText(text))
        override suspend fun deleteText() = send(RemoteAction.DeleteText)
    }
    private val inputs = listOf(RemoteAction.Key(RemoteKey.Home), RemoteAction.AdjustVolume(1), RemoteAction.ReplaceText("hello"), RemoteAction.DeleteText)

    @Test fun unavailableChannelFallsBackExactlyOnceForKeysVolumeAndText() = runTest {
        val controller = CastController()
        val router = RemoteInputRouter(UnavailableTvRemoteChannel())
        for (action in inputs) assertEquals(RemoteCommandResult.Submitted, router.execute(controller, action))
        assertEquals(inputs, controller.actions)
        assertEquals(RemoteCommandResult.Unsupported("Whole-TV control is not available yet"), UnavailableTvRemoteChannel().sendKey(RemoteKey.Home))
    }
    @Test fun pairedMatchedReadyChannelReceivesInputsWithoutCastDuplicates() = runTest {
        val controller = CastController(); val channel = Channel(); val router = RemoteInputRouter(channel)
        for (action in inputs) router.execute(controller, action)
        assertEquals(inputs, channel.actions); assertTrue(controller.actions.isEmpty())
        router.execute(controller, RemoteAction.SetPlaying(true))
        assertEquals(listOf(RemoteAction.SetPlaying(true)), controller.actions)
    }
    @Test fun unmatchedUnpairedDisconnectedAndUnsupportedRoutesFallBackBeforeSending() = runTest {
        val controller = CastController(); val channel = Channel(); val router = RemoteInputRouter(channel)
        val ready = channel.state.value
        for (state in listOf(ready.copy(matchedEndpoint = null), ready.copy(paired = false), ready.copy(physicalDeviceId = null), ready.copy(connected = false), ready.copy(keys = emptySet(), volume = VolumeCapabilities(), text = TextCapabilities()))) {
            channel.state.value = state
            for (action in inputs) router.execute(controller, action)
        }
        assertEquals(inputs + inputs + inputs + inputs + inputs, controller.actions)
        assertTrue(channel.actions.isEmpty())
    }
    @Test fun channelFailureOrLossDuringSendNeverResendsOnCastController() = runTest {
        val controller = CastController(); val channel = Channel(); val router = RemoteInputRouter(channel)
        channel.result = RemoteCommandResult.Failed("Connection lost after submission")
        assertEquals(channel.result, router.execute(controller, inputs[0]))
        channel.result = RemoteCommandResult.Unsupported("Capability changed")
        assertEquals(channel.result, router.execute(controller, inputs[1]))
        assertTrue(controller.actions.isEmpty())
        channel.state.value = channel.state.value.copy(connected = false)
        router.execute(controller, inputs[2])
        assertEquals(listOf(inputs[2]), controller.actions)
        assertEquals(inputs.take(2), channel.actions)
    }
}
