package com.playbridge.player.ui.player

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
class PlayerControlsStateTest {
    @Test
    fun videoQualityRequiresAtLeastTwoSelectableRenditions() {
        assertFalse(emptyList<UnifiedTrack>().hasSelectableVideoQualities())
        assertFalse(
            listOf(
                videoTrack("auto"),
                videoTrack("max:720"),
            ).hasSelectableVideoQualities(),
        )
        assertTrue(
            listOf(
                videoTrack("auto"),
                videoTrack("max:720"),
                videoTrack("max:1080"),
            ).hasSelectableVideoQualities(),
        )
    }

    @Test
    fun `named playback transition obscures skip controls`() {
        assertTrue(
            PlayerControlsState(playbackTransitionMessage = "Preparing playback…")
                .isPlaybackObscured(),
        )
    }

    @Test
    fun `buffering obscures skip controls without transition text`() {
        assertTrue(PlayerControlsState(isBuffering = true).isPlaybackObscured())
    }

    @Test
    fun `ready playback allows skip controls`() {
        assertFalse(PlayerControlsState().isPlaybackObscured())
    }

    @Test
    fun `player switch defaults on and can be disabled for page media`() {
        assertTrue(PlayerControlsState().canSwitchPlayer)
        assertFalse(PlayerControlsState(canSwitchPlayer = false).canSwitchPlayer)
    }

    @Test
    fun `chapters and chapterOsd default to empty and null`() {
        val state = PlayerControlsState()
        assertTrue(state.chapters.isEmpty())
        assertFalse(state.chapterOsd != null)
    }

    @Test
    fun `chapters button requires at least two chapters on seekable non-live media`() {
        val twoChapters = listOf(
            com.playbridge.shared.player.PlayerChapter(0L, "Part 1"),
            com.playbridge.shared.player.PlayerChapter(60_000L, "Part 2"),
        )
        assertFalse(PlayerControlsState().hasSelectableChapters())
        assertFalse(
            PlayerControlsState(
                chapters = listOf(com.playbridge.shared.player.PlayerChapter(0L, "Only")),
            ).hasSelectableChapters(),
        )
        val withChapters = PlayerControlsState(chapters = twoChapters)
        assertTrue(withChapters.hasSelectableChapters())
        assertTrue(withChapters.hasSelectableChapters(mediaKind = "audio"))
        assertFalse(withChapters.hasSelectableChapters(mediaKind = "image"))
        assertFalse(
            withChapters.copy(
                capabilities = PlaybackCapabilities(isLive = true),
            ).hasSelectableChapters(),
        )
        assertFalse(
            withChapters.copy(
                capabilities = PlaybackCapabilities(isSeekable = false),
            ).hasSelectableChapters(),
        )
    }

    @Test
    fun `controlActionFocusTarget restores focus to opener button after overlay closes`() {
        val twoChapters = listOf(
            com.playbridge.shared.player.PlayerChapter(0L, "Part 1"),
            com.playbridge.shared.player.PlayerChapter(60_000L, "Part 2"),
        )
        assertEquals(
            ControlActionFocusTarget.PLAY_PAUSE,
            PlayerControlsState().controlActionFocusTarget(),
        )
        assertEquals(
            ControlActionFocusTarget.CHAPTERS,
            PlayerControlsState(
                chapters = twoChapters,
                lastClosedOverlay = ActiveOverlay.CHAPTERS,
            ).controlActionFocusTarget(),
        )
        assertEquals(
            ControlActionFocusTarget.PLAY_PAUSE,
            PlayerControlsState(
                chapters = emptyList(),
                lastClosedOverlay = ActiveOverlay.CHAPTERS,
            ).controlActionFocusTarget(),
        )
        assertEquals(
            ControlActionFocusTarget.PLAYLIST,
            PlayerControlsState(
                hasPlaylist = true,
                lastClosedOverlay = ActiveOverlay.PLAYLIST_PICKER,
            ).controlActionFocusTarget(),
        )
        assertEquals(
            ControlActionFocusTarget.PLAY_PAUSE,
            PlayerControlsState(
                hasPlaylist = false,
                lastClosedOverlay = ActiveOverlay.PLAYLIST_PICKER,
            ).controlActionFocusTarget(),
        )
        assertEquals(
            ControlActionFocusTarget.SUBTITLES,
            PlayerControlsState(
                lastClosedOverlay = ActiveOverlay.SUBTITLES,
            ).controlActionFocusTarget(mediaKind = "video"),
        )
        assertEquals(
            ControlActionFocusTarget.PLAY_PAUSE,
            PlayerControlsState(
                lastClosedOverlay = ActiveOverlay.SUBTITLES,
            ).controlActionFocusTarget(mediaKind = "audio"),
        )
        assertEquals(
            ControlActionFocusTarget.SWITCH_PLAYER,
            PlayerControlsState(
                canSwitchPlayer = true,
                lastClosedOverlay = ActiveOverlay.SWITCH_PLAYER,
            ).controlActionFocusTarget(mediaKind = "video"),
        )
        assertEquals(
            ControlActionFocusTarget.SETTINGS,
            PlayerControlsState(
                lastClosedOverlay = ActiveOverlay.SETTINGS,
            ).controlActionFocusTarget(mediaKind = "video"),
        )
    }

    @Test
    fun `shouldAutoHideControls blocks auto-hide whenever any overlay is open`() {
        assertTrue(
            PlayerControlsState(
                isVisible = true,
                isFullControlsVisible = true,
                isPlaying = true,
                activeOverlay = ActiveOverlay.NONE,
            ).shouldAutoHideControls(),
        )
        assertFalse(
            PlayerControlsState(
                isVisible = true,
                isFullControlsVisible = true,
                isPlaying = false,
                activeOverlay = ActiveOverlay.NONE,
            ).shouldAutoHideControls(),
        )
        assertTrue(
            PlayerControlsState(
                isVisible = true,
                isFullControlsVisible = false,
                isPlaying = false,
                activeOverlay = ActiveOverlay.NONE,
            ).shouldAutoHideControls(),
        )
        for (overlay in ActiveOverlay.values().filter { it != ActiveOverlay.NONE }) {
            assertFalse(
                "Expected shouldAutoHideControls() == false when overlay=$overlay",
                PlayerControlsState(
                    isVisible = true,
                    isFullControlsVisible = true,
                    isPlaying = true,
                    activeOverlay = overlay,
                ).shouldAutoHideControls(),
            )
            assertFalse(
                "Expected shouldAutoHideControls() == false in seek strip when overlay=$overlay",
                PlayerControlsState(
                    isVisible = true,
                    isFullControlsVisible = false,
                    isPlaying = true,
                    activeOverlay = overlay,
                ).shouldAutoHideControls(),
            )
        }
    }

    @Test
    fun `opening any overlay cancels auto-hide and closing restarts timer and restores focus target`() {
        val twoChapters = listOf(
            com.playbridge.shared.player.PlayerChapter(0L, "Part 1"),
            com.playbridge.shared.player.PlayerChapter(60_000L, "Part 2"),
        )
        val overlayCases = listOf<Triple<ActiveOverlay, ControlActionFocusTarget, (PlayerControlsViewModel) -> Unit>>(
            Triple(ActiveOverlay.CHAPTERS, ControlActionFocusTarget.CHAPTERS) { vm ->
                vm.showChapters()
            },
            Triple(ActiveOverlay.PLAYLIST_PICKER, ControlActionFocusTarget.PLAYLIST) { vm ->
                vm.showPlaylist(emptyList(), 0)
            },
            Triple(ActiveOverlay.SUBTITLES, ControlActionFocusTarget.SUBTITLES) { vm ->
                vm.showSubtitles()
            },
            Triple(ActiveOverlay.SETTINGS, ControlActionFocusTarget.SETTINGS) { vm ->
                vm.showSettings(SettingsTab.AUDIO)
            },
            Triple(ActiveOverlay.SWITCH_PLAYER, ControlActionFocusTarget.SWITCH_PLAYER) { vm ->
                vm.showSwitchPlayer()
            },
        )

        for ((expectedOverlay, expectedFocusTarget, openOverlay) in overlayCases) {
            val testScope = TestScope(StandardTestDispatcher())
            val vm = PlayerControlsViewModel(testScope)
            vm.setChapters(twoChapters)
            vm.setPlaylistVisible(true)
            vm.setPlaying(true)
            vm.showControls(full = true, playing = true)

            // Advance part way through the 5s auto-hide timer before opening the overlay.
            testScope.advanceTimeBy(4_000L)
            testScope.runCurrent()
            assertTrue(vm.controlsState.value.isVisible)

            openOverlay(vm)
            assertEquals(expectedOverlay, vm.controlsState.value.activeOverlay)

            // Even if resetAutoHideTimer() or showSeekUI() is invoked while the overlay is open,
            // and 25 seconds pass, controls and the overlay must remain visible.
            vm.resetAutoHideTimer(durationMs = 5_000L)
            vm.showSeekUI()
            testScope.advanceTimeBy(25_000L)
            testScope.runCurrent()
            assertTrue(
                "Controls must not auto-hide while $expectedOverlay is open",
                vm.controlsState.value.isVisible,
            )
            assertTrue(
                "Full controls must remain active while $expectedOverlay is open",
                vm.controlsState.value.isFullControlsVisible,
            )
            assertEquals(expectedOverlay, vm.controlsState.value.activeOverlay)

            // Closing the overlay (via Back or selection) keeps full controls visible,
            // sets the opener focus target, and restarts the 5s auto-hide timer from zero.
            vm.hideOverlay()
            assertEquals(ActiveOverlay.NONE, vm.controlsState.value.activeOverlay)
            assertTrue(vm.controlsState.value.isVisible)
            assertTrue(vm.controlsState.value.isFullControlsVisible)
            assertEquals(
                expectedFocusTarget,
                vm.controlsState.value.controlActionFocusTarget(mediaKind = "video"),
            )

            // At 4.9s after closing, controls are still visible (timer restarted from zero).
            testScope.advanceTimeBy(4_900L)
            testScope.runCurrent()
            assertTrue(vm.controlsState.value.isVisible)

            // At 5.0s after closing while playing, controls auto-hide.
            testScope.advanceTimeBy(200L)
            testScope.runCurrent()
            assertFalse(vm.controlsState.value.isVisible)
        }
    }

    @Test
    fun `closing chapters overlay after chapter selection while paused keeps full controls open`() {
        val testScope = TestScope(StandardTestDispatcher())
        val vm = PlayerControlsViewModel(testScope)
        val twoChapters = listOf(
            com.playbridge.shared.player.PlayerChapter(0L, "Part 1"),
            com.playbridge.shared.player.PlayerChapter(60_000L, "Part 2"),
        )
        vm.setChapters(twoChapters)
        vm.setPlaying(false)
        vm.showControls(full = true, playing = false)
        testScope.advanceTimeBy(20_000L)
        testScope.runCurrent()

        vm.showChapters()
        testScope.advanceTimeBy(10_000L)
        testScope.runCurrent()

        // Simulate selecting a chapter: showChapterOsd + hideOverlay
        vm.showChapterOsd("Chapter 2 · Part 2")
        vm.hideOverlay()

        testScope.advanceTimeBy(20_000L)
        testScope.runCurrent()
        assertTrue(vm.controlsState.value.isVisible)
        assertTrue(vm.controlsState.value.isFullControlsVisible)
        assertEquals(ActiveOverlay.NONE, vm.controlsState.value.activeOverlay)
        assertEquals(
            ControlActionFocusTarget.CHAPTERS,
            vm.controlsState.value.controlActionFocusTarget(),
        )
    }

    private fun videoTrack(id: String) = UnifiedTrack(
        id = id,
        name = id,
        isSelected = id == "auto",
        type = "video",
    )
}

