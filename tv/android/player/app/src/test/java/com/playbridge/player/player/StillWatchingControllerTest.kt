package com.playbridge.player.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StillWatchingControllerTest {
    @Test fun promptsOnlyAfterCumulativeActivePlaybackAndContinueResets() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var paused = 0
        var resumed = 0
        var now = 0L
        val controller = StillWatchingController(scope, { paused++ }, { resumed++ }, {}, { now }, graceSeconds = 60)
        controller.updateSettings(true, 1)
        controller.onPlayingChanged(true)
        now += 30_000; scope.advanceTimeBy(30_000)
        controller.onPlayingChanged(false)
        now += 100_000; scope.advanceTimeBy(100_000)
        assertFalse(controller.state.value.isPrompting)
        controller.onPlayingChanged(true)
        now += 30_000; scope.advanceTimeBy(30_000); scope.runCurrent()
        assertTrue(controller.state.value.isPrompting)
        assertEquals(1, paused)
        controller.continueWatching()
        assertFalse(controller.state.value.isPrompting)
        assertEquals(1, resumed)
    }

    @Test fun countdownExpiresAndStops() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var stopped = 0
        var now = 0L
        val controller = StillWatchingController(scope, {}, {}, { stopped++ }, { now }, graceSeconds = 2)
        controller.updateSettings(true, 1)
        controller.onPlayingChanged(true)
        now = 60_000; scope.advanceTimeBy(60_000); scope.runCurrent()
        assertTrue(controller.state.value.isPrompting)
        scope.advanceTimeBy(2_000); scope.runCurrent()
        assertEquals(1, stopped)
    }

    @Test fun liveSettingsChangesRescheduleActivePlayback() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var paused = 0
        var now = 0L
        val controller = StillWatchingController(scope, { paused++ }, {}, {}, { now })
        controller.updateSettings(true, 2)
        controller.onPlayingChanged(true)
        now = 60_000; scope.advanceTimeBy(60_000); scope.runCurrent()

        controller.updateSettings(true, 1)
        scope.runCurrent()
        assertTrue(controller.state.value.isPrompting)
        assertEquals(1, paused)

        controller.continueWatching()
        controller.updateSettings(false, 1)
        controller.onPlayingChanged(true)
        now += 120_000; scope.advanceTimeBy(120_000); scope.runCurrent()
        assertFalse(controller.state.value.isPrompting)

        controller.updateSettings(true, 1)
        now += 60_000; scope.advanceTimeBy(60_000); scope.runCurrent()
        assertTrue(controller.state.value.isPrompting)
    }

    @Test fun userActivityRestartsUnattendedPlaybackWindow() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var now = 0L
        val controller = StillWatchingController(scope, {}, {}, {}, nowMs = { now })
        controller.updateSettings(true, 1)
        controller.onPlayingChanged(true)

        now = 50_000; scope.advanceTimeBy(50_000)
        controller.onUserActivity()
        now = 70_000; scope.advanceTimeBy(20_000); scope.runCurrent()
        assertFalse(controller.state.value.isPrompting)

        now = 110_000; scope.advanceTimeBy(40_000); scope.runCurrent()
        assertTrue(controller.state.value.isPrompting)
    }

    @Test fun mediaChangeDismissesPromptWithoutResumingOldMedia() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var resumed = 0
        var stopped = 0
        var now = 0L
        val controller = StillWatchingController(
            scope, {}, { resumed++ }, { stopped++ }, nowMs = { now }, graceSeconds = 2,
        )
        controller.updateSettings(true, 1)
        controller.onPlayingChanged(true)
        now = 60_000; scope.advanceTimeBy(60_000); scope.runCurrent()
        assertTrue(controller.state.value.isPrompting)

        controller.onMediaChanged()
        scope.advanceTimeBy(5_000); scope.runCurrent()
        assertFalse(controller.state.value.isPrompting)
        assertEquals(0, resumed)
        assertEquals(0, stopped)
    }

    @Test fun userActivityDismissesPromptAndResumesPromptPausedPlayback() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var resumed = 0
        var now = 0L
        val controller = StillWatchingController(
            scope, {}, { resumed++ }, {}, nowMs = { now }, graceSeconds = 2,
        )
        controller.updateSettings(true, 1)
        controller.onPlayingChanged(true)
        now = 60_000; scope.advanceTimeBy(60_000); scope.runCurrent()
        assertTrue(controller.state.value.isPrompting)

        controller.onUserActivity()

        assertFalse(controller.state.value.isPrompting)
        assertEquals(1, resumed)
        scope.advanceTimeBy(5_000); scope.runCurrent()
        assertFalse(controller.state.value.isPrompting)
    }

    @Test fun disablingClearsPreviouslyAccumulatedPlayback() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var now = 0L
        val controller = StillWatchingController(scope, {}, {}, {}, nowMs = { now })
        controller.updateSettings(true, 1)
        controller.onPlayingChanged(true)
        now = 50_000; scope.advanceTimeBy(50_000)
        controller.updateSettings(false, 1)
        now = 100_000; scope.advanceTimeBy(50_000)
        controller.updateSettings(true, 1)
        now = 110_000; scope.advanceTimeBy(10_000); scope.runCurrent()
        assertFalse(controller.state.value.isPrompting)

        now = 160_000; scope.advanceTimeBy(50_000); scope.runCurrent()
        assertTrue(controller.state.value.isPrompting)
    }
}
