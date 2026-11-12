package com.playbridge.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** One test per cell of the table in ControlsRemoteMap.dc.html. */
class PlayerKeyMapTest {
    private fun act(key: RemoteKey, surface: ControlsSurface, build: KeyContext.() -> KeyContext = { this }) =
        resolveKeyAction(key, KeyContext(surface).build())

    // --- OK ---
    @Test fun `OK with nothing on screen pauses and opens controls`() =
        assertEquals(KeyAction.PauseAndOpenControls, act(RemoteKey.OK, ControlsSurface.NOTHING))

    @Test fun `OK on the seek bar pauses and opens controls`() =
        assertEquals(KeyAction.PauseAndOpenControls, act(RemoteKey.OK, ControlsSurface.SEEK_BAR))

    @Test fun `OK on controls or a panel is left to the focused control`() {
        assertEquals(KeyAction.PassThrough, act(RemoteKey.OK, ControlsSurface.CONTROLS))
        assertEquals(KeyAction.PassThrough, act(RemoteKey.OK, ControlsSurface.PANEL))
    }

    // --- Left, Right ---
    @Test fun `Left and Right seek 10 seconds when the controls are hidden or only the seek bar shows`() {
        for (surface in listOf(ControlsSurface.NOTHING, ControlsSurface.SEEK_BAR)) {
            assertEquals(KeyAction.Seek(-10_000L), act(RemoteKey.LEFT, surface))
            assertEquals(KeyAction.Seek(10_000L), act(RemoteKey.RIGHT, surface))
        }
    }

    @Test fun `Left and Right on controls move focus (Compose), on a panel Left closes it`() {
        assertEquals(KeyAction.PassThrough, act(RemoteKey.LEFT, ControlsSurface.CONTROLS))
        assertEquals(KeyAction.PassThrough, act(RemoteKey.RIGHT, ControlsSurface.CONTROLS))
        assertEquals(KeyAction.ClosePanel, act(RemoteKey.LEFT, ControlsSurface.PANEL))
        assertEquals(KeyAction.PassThrough, act(RemoteKey.RIGHT, ControlsSurface.PANEL))
    }

    @Test fun `photos step to the previous or next photo instead of seeking`() {
        assertEquals(KeyAction.StepItem(-1), act(RemoteKey.LEFT, ControlsSurface.NOTHING) { copy(isImage = true) })
        assertEquals(KeyAction.StepItem(1), act(RemoteKey.RIGHT, ControlsSurface.NOTHING) { copy(isImage = true) })
    }

    // --- Hold Left, Right ---
    @Test fun `holding seeks faster after more than ten repeats`() {
        assertEquals(KeyAction.Seek(10_000L), act(RemoteKey.RIGHT, ControlsSurface.NOTHING) { copy(repeatCount = 10) })
        assertEquals(KeyAction.Seek(50_000L), act(RemoteKey.RIGHT, ControlsSurface.SEEK_BAR) { copy(repeatCount = 11) })
        assertEquals(KeyAction.Seek(-50_000L), act(RemoteKey.LEFT, ControlsSurface.NOTHING) { copy(repeatCount = 30) })
    }

    @Test fun `holding Left in a panel does nothing`() {
        assertEquals(KeyAction.Ignore, act(RemoteKey.LEFT, ControlsSurface.PANEL) { copy(repeatCount = 3) })
    }

    @Test fun `seek step grows with the repeat count`() {
        assertEquals(10_000L, seekStepMs(0))
        assertEquals(10_000L, seekStepMs(10))
        assertEquals(50_000L, seekStepMs(11))
    }

    // --- Up, Down ---
    @Test fun `Up and Down open the controls without pausing when hidden or on the seek bar`() {
        for (surface in listOf(ControlsSurface.NOTHING, ControlsSurface.SEEK_BAR)) {
            assertEquals(KeyAction.OpenControls, act(RemoteKey.UP, surface))
            assertEquals(KeyAction.OpenControls, act(RemoteKey.DOWN, surface))
        }
    }

    @Test fun `Up and Down on controls and panels are left to Compose focus`() {
        for (surface in listOf(ControlsSurface.CONTROLS, ControlsSurface.PANEL)) {
            assertEquals(KeyAction.PassThrough, act(RemoteKey.UP, surface))
            assertEquals(KeyAction.PassThrough, act(RemoteKey.DOWN, surface))
        }
    }

    // --- Back ---
    @Test fun `Back closes a panel, hides controls, hides the seek bar`() {
        assertEquals(KeyAction.ClosePanel, act(RemoteKey.BACK, ControlsSurface.PANEL))
        assertEquals(KeyAction.HideControls, act(RemoteKey.BACK, ControlsSurface.CONTROLS))
        assertEquals(KeyAction.HideSeekBar, act(RemoteKey.BACK, ControlsSurface.SEEK_BAR))
    }

    @Test fun `Back on a bare video asks for a second press, then leaves`() {
        assertEquals(KeyAction.ArmBack, act(RemoteKey.BACK, ControlsSurface.NOTHING))
        assertEquals(KeyAction.ExitPlayer, act(RemoteKey.BACK, ControlsSurface.NOTHING) { copy(backArmed = true) })
    }

    @Test fun `Back leaves at once where no confirmation applies`() {
        assertEquals(KeyAction.ExitPlayer, act(RemoteKey.BACK, ControlsSurface.NOTHING) { copy(confirmBack = false) })
    }

    // --- Play/Pause keys ---
    @Test fun `media keys with nothing on screen or the seek bar show a badge only`() {
        for (surface in listOf(ControlsSurface.NOTHING, ControlsSurface.SEEK_BAR)) {
            assertEquals(KeyAction.Transport("toggle", quiet = true), act(RemoteKey.PLAY_PAUSE, surface))
            assertEquals(KeyAction.Transport("play", quiet = true), act(RemoteKey.PLAY, surface))
            assertEquals(KeyAction.Transport("pause", quiet = true), act(RemoteKey.PAUSE, surface))
        }
    }

    @Test fun `media keys on controls or a panel leave everything where it is`() {
        assertEquals(KeyAction.Transport("toggle", quiet = false), act(RemoteKey.PLAY_PAUSE, ControlsSurface.CONTROLS))
        assertEquals(KeyAction.Transport("toggle", quiet = false), act(RemoteKey.PLAY_PAUSE, ControlsSurface.PANEL))
    }

    @Test fun `media keys are never quiet while pre-play or a prompt is up`() {
        assertEquals(
            KeyAction.Transport("toggle", quiet = false),
            act(RemoteKey.PLAY_PAUSE, ControlsSurface.NOTHING) { copy(quietAllowed = false) },
        )
    }

    // --- surface from state ---
    @Test fun `surface follows the controls state`() {
        assertEquals(ControlsSurface.NOTHING, PlayerControlsState().controlsSurface())
        assertEquals(ControlsSurface.SEEK_BAR, PlayerControlsState(isVisible = true).controlsSurface())
        assertEquals(
            ControlsSurface.CONTROLS,
            PlayerControlsState(isVisible = true, isFullControlsVisible = true).controlsSurface(),
        )
        assertEquals(
            ControlsSurface.PANEL,
            PlayerControlsState(
                isVisible = true, isFullControlsVisible = true, activeOverlay = ActiveOverlay.CHAPTERS,
            ).controlsSurface(),
        )
    }
}
