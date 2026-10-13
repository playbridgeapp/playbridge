package com.playbridge.sender.cast.remote

import com.playbridge.sender.connection.ConnectionCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteReportedTvStateTest {
    @Test fun loopIsNullWhenTheFieldIsAbsentAndFollowsAReportedBoolean() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        messages.tryEmit("""{"type":"context","active":"player"}""")
        messages.tryEmit("""{"type":"player_settings","speed":1,"scaling":"Fit","audioBoost":false,"subtitleOffsetMs":0,"engine":"exo"}""")
        runCurrent()
        assertNull(coordinator.tvPlayerSettings.value.loop)
        assertFalse("loop" in coordinator.remoteObservation.value.settingsFields)
        assertNull(loopFromPlayerSettings(coordinator.tvPlayerSettings.value.loop, coordinator.remoteObservation.value.settingsFields))

        messages.tryEmit("""{"type":"player_settings","speed":1,"scaling":"Fit","audioBoost":false,"subtitleOffsetMs":0,"engine":"exo","loop":true}""")
        runCurrent()
        assertEquals(true, coordinator.tvPlayerSettings.value.loop)
        assertTrue("loop" in coordinator.remoteObservation.value.settingsFields)
        assertEquals(true, loopFromPlayerSettings(coordinator.tvPlayerSettings.value.loop, coordinator.remoteObservation.value.settingsFields))

        messages.tryEmit("""{"type":"player_settings","speed":1,"scaling":"Fit","audioBoost":false,"subtitleOffsetMs":0,"engine":"mpv","loop":false}""")
        runCurrent()
        assertEquals(false, coordinator.tvPlayerSettings.value.loop)
        assertEquals(false, loopFromPlayerSettings(coordinator.tvPlayerSettings.value.loop, coordinator.remoteObservation.value.settingsFields))
    }

    @Test fun browserFullscreenFollowsTheFieldAndIgnoresPlayerStatusAndOlderTvs() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        messages.tryEmit("""{"type":"context","active":"browser"}""")
        messages.tryEmit("""{"type":"status","state":"playing","position":1,"duration":2,"title":"Page"}""")
        runCurrent()
        assertNull(coordinator.remoteObservation.value.browserFullscreen)
        assertNull(browserFullscreenFromStatus(RemoteContext.Browser, coordinator.remoteObservation.value.browserFullscreen))

        messages.tryEmit("""{"type":"status","state":"playing","position":2,"duration":2,"title":"Page","fullscreen":true}""")
        runCurrent()
        assertEquals(true, coordinator.remoteObservation.value.browserFullscreen)
        assertEquals(true, browserFullscreenFromStatus(RemoteContext.Browser, coordinator.remoteObservation.value.browserFullscreen))
        assertNull(browserFullscreenFromStatus(RemoteContext.Player, true))

        messages.tryEmit("""{"type":"status","state":"paused","position":3,"duration":9,"title":"Movie","mediaKind":"video","playbackId":"pb","currentItemId":"ep","fullscreen":false}""")
        runCurrent()
        assertEquals(true, coordinator.remoteObservation.value.browserFullscreen)

        messages.tryEmit("""{"type":"status","state":"playing","position":4,"duration":2,"title":"Page","fullscreen":false}""")
        runCurrent()
        assertEquals(false, coordinator.remoteObservation.value.browserFullscreen)

        messages.tryEmit("""{"type":"context","active":"idle"}""")
        runCurrent()
        assertNull(coordinator.remoteObservation.value.browserFullscreen)
    }
}
