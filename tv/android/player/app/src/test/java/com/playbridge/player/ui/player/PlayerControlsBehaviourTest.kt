package com.playbridge.player.ui.player

import com.playbridge.player.player.PlayerEngineAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControlsBehaviourTest {
    private class FakeEngine(var position: Long = 60_000L, override val duration: Long = 600_000L) : PlayerEngineAdapter {
        var playing = true
        val seeks = mutableListOf<Long>()
        override val isPlaying get() = playing
        override val currentPosition get() = position
        override val bufferedPosition get() = position
        override val streamInfo: String? = null
        override val frameRate = 0f
        override val hdrFormat: String? = null
        override fun setLoudnessEnhancer(enabled: Boolean) {}
        override fun setSubtitleDelay(delayMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) { seeks += positionMs; position = positionMs }
    }

    private fun vmWithEngine(scope: TestScope, engine: FakeEngine): PlayerControlsViewModel {
        val vm = PlayerControlsViewModel(scope)
        // setEngine starts a progress loop on viewModelScope; the tests drive state directly instead.
        val field = PlayerControlsViewModel::class.java.getDeclaredField("engine")
        field.isAccessible = true
        field.set(vm, engine)
        return vm
    }

    @Test
    fun `several Left presses inside 400ms commit as one seek`() {
        val scope = TestScope(StandardTestDispatcher())
        val engine = FakeEngine(position = 60_000L)
        val vm = vmWithEngine(scope, engine)

        vm.handleScrubbing(-10_000L)
        scope.advanceTimeBy(200L); scope.runCurrent()
        vm.handleScrubbing(-10_000L)
        scope.advanceTimeBy(200L); scope.runCurrent()
        vm.handleScrubbing(-10_000L)
        // The target is shown immediately and nothing has been sent to the player yet.
        assertEquals(30_000L, vm.controlsState.value.currentPosition)
        assertTrue(engine.seeks.isEmpty())

        scope.advanceTimeBy(401L); scope.runCurrent()
        assertEquals(listOf(30_000L), engine.seeks)
    }

    @Test
    fun `seek target stays inside the video`() {
        val scope = TestScope(StandardTestDispatcher())
        val engine = FakeEngine(position = 5_000L, duration = 100_000L)
        val vm = vmWithEngine(scope, engine)
        vm.handleScrubbing(-50_000L)
        assertEquals(0L, vm.controlsState.value.currentPosition)
        vm.handleScrubbing(500_000L)
        assertEquals(100_000L, vm.controlsState.value.currentPosition)
    }

    @Test
    fun `seek strip hides after 3 seconds and full controls after 8 while playing`() {
        assertEquals(3_000L, autoHideDelayMs(fullControls = false))
        assertEquals(8_000L, autoHideDelayMs(fullControls = true))

        val scope = TestScope(StandardTestDispatcher())
        val vm = vmWithEngine(scope, FakeEngine())
        vm.setPlaying(true)
        vm.showSeekUI()
        scope.advanceTimeBy(2_900L); scope.runCurrent()
        assertTrue(vm.controlsState.value.isVisible)
        scope.advanceTimeBy(200L); scope.runCurrent()
        assertFalse(vm.controlsState.value.isVisible)

        vm.showControls(full = true, playing = true)
        scope.advanceTimeBy(7_900L); scope.runCurrent()
        assertTrue(vm.controlsState.value.isVisible)
        scope.advanceTimeBy(200L); scope.runCurrent()
        assertFalse(vm.controlsState.value.isVisible)
    }

    @Test
    fun `full controls stay while paused and start timing out once playback resumes`() {
        val scope = TestScope(StandardTestDispatcher())
        val vm = vmWithEngine(scope, FakeEngine())
        vm.setPlaying(false)
        vm.showControls(full = true, playing = false)
        scope.advanceTimeBy(60_000L); scope.runCurrent()
        assertTrue(vm.controlsState.value.isVisible)

        vm.setPlaying(true)
        scope.advanceTimeBy(8_100L); scope.runCurrent()
        assertFalse(vm.controlsState.value.isVisible)
    }

    @Test
    fun `quiet pause badge stays until playback resumes or controls open`() {
        val scope = TestScope(StandardTestDispatcher())
        val vm = vmWithEngine(scope, FakeEngine())
        vm.setPlaying(false)
        vm.showTransportBadge(TransportBadge.PAUSED)
        scope.advanceTimeBy(30_000L); scope.runCurrent()
        assertEquals(TransportBadge.PAUSED, vm.controlsState.value.transportBadge)

        vm.setPlaying(true)
        assertEquals(TransportBadge.NONE, vm.controlsState.value.transportBadge)

        vm.setPlaying(false)
        vm.showTransportBadge(TransportBadge.PAUSED)
        vm.showControls(full = true, playing = false)
        assertEquals(TransportBadge.NONE, vm.controlsState.value.transportBadge)
    }

    @Test
    fun `play badge and notices clear themselves`() {
        val scope = TestScope(StandardTestDispatcher())
        val vm = vmWithEngine(scope, FakeEngine())
        vm.showTransportBadge(TransportBadge.PLAYING)
        vm.showNotice("Press Back again to stop", durationMs = 3_000L)
        assertEquals(PlayerNotice("Press Back again to stop"), vm.controlsState.value.notice)
        scope.advanceTimeBy(1_100L); scope.runCurrent()
        assertEquals(TransportBadge.NONE, vm.controlsState.value.transportBadge)
        assertEquals(PlayerNotice("Press Back again to stop"), vm.controlsState.value.notice)
        scope.advanceTimeBy(2_000L); scope.runCurrent()
        assertNull(vm.controlsState.value.notice)
    }

    @Test
    fun `an open panel pins the controls`() {
        val scope = TestScope(StandardTestDispatcher())
        val vm = vmWithEngine(scope, FakeEngine())
        vm.setPlaying(true)
        vm.showControls(full = true, playing = true)
        vm.showSubtitles()
        scope.advanceTimeBy(60_000L); scope.runCurrent()
        assertTrue(vm.controlsState.value.isVisible)
        assertEquals(ActiveOverlay.SUBTITLES, vm.controlsState.value.activeOverlay)
    }

    @Test
    fun `playback error replaces the transition message and clears with it`() {
        val scope = TestScope(StandardTestDispatcher())
        val vm = vmWithEngine(scope, FakeEngine())
        vm.showPlaybackTransition("Preparing")
        vm.showPlaybackError("The video stopped loading")
        assertNull(vm.controlsState.value.playbackTransitionMessage)
        assertEquals("The video stopped loading", vm.controlsState.value.playbackError)
        vm.clearPlaybackTransition()
        assertNull(vm.controlsState.value.playbackError)
    }

    @Test
    fun `bookmark state is stored as given`() {
        val vm = PlayerControlsViewModel(TestScope(StandardTestDispatcher()))
        assertEquals(BookmarkState.UNAVAILABLE, vm.controlsState.value.bookmark)
        vm.setBookmarkState(BookmarkState.ON)
        assertEquals(BookmarkState.ON, vm.controlsState.value.bookmark)
    }
}
