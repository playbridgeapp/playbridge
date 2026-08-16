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

    private fun videoTrack(id: String) = UnifiedTrack(
        id = id,
        name = id,
        isSelected = id == "auto",
        type = "video",
    )
}
