package com.playbridge.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun videoTrack(id: String) = UnifiedTrack(
        id = id,
        name = id,
        isSelected = id == "auto",
        type = "video",
    )
}
