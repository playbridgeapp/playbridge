package com.playbridge.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlFocusSlotTest {
    private fun slot(t: ControlActionFocusTarget, queue: Boolean = true, audioFirst: Boolean = false) =
        controlFocusSlot(t, showQueue = queue, showSubtitles = true, showAudio = true, showMore = true, audioHasFocusAfterPanel = audioFirst)

    @Test fun `each opener gets its own button back`() {
        assertEquals(ControlButtonSlot.SUBTITLES, slot(ControlActionFocusTarget.SUBTITLES))
        assertEquals(ControlButtonSlot.PLAYLIST, slot(ControlActionFocusTarget.PLAYLIST))
        assertEquals(ControlButtonSlot.AUDIO, slot(ControlActionFocusTarget.SETTINGS, audioFirst = true))
        assertEquals(ControlButtonSlot.MORE, slot(ControlActionFocusTarget.SETTINGS, audioFirst = false))
    }

    @Test fun `chapters opened from More return to More`() {
        assertEquals(ControlButtonSlot.MORE, slot(ControlActionFocusTarget.CHAPTERS, audioFirst = true))
    }

    @Test fun `missing button falls back to play`() {
        assertEquals(ControlButtonSlot.PLAY, slot(ControlActionFocusTarget.PLAYLIST, queue = false))
        assertEquals(ControlButtonSlot.PLAY, slot(ControlActionFocusTarget.PLAY_PAUSE))
    }

    @Test fun `subtitles lift only while something is drawn along the bottom`() {
        assertEquals(0, PlayerControlsState().subtitleLiftDp())
        assertEquals(SUBTITLE_LIFT_DP, PlayerControlsState(isVisible = true, isFullControlsVisible = true).subtitleLiftDp())
        assertEquals(SUBTITLE_LIFT_DP, PlayerControlsState(isVisible = true).subtitleLiftDp())
        assertEquals(SUBTITLE_LIFT_DP, PlayerControlsState(phonePaused = PhoneOrigin(null)).subtitleLiftDp())
        assertEquals(0, PlayerControlsState(isVisible = true, activeOverlay = ActiveOverlay.SETTINGS).subtitleLiftDp())
    }
}
