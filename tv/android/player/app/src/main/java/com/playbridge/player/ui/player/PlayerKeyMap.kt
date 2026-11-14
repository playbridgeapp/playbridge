package com.playbridge.player.ui.player

/**
 * The player's remote key decisions as a pure function: (key, what is on screen) to an action.
 * It follows the table in ControlsRemoteMap.dc.html. The host (PlayerHostActivity) applies the
 * action; physical keys and the phone's on-screen remote ("dpad_*") both go through it.
 *
 * Volume keys are not here: the system handles dedicated volume keys, and the phone's volume
 * buttons are handled by the server before they reach the player.
 */
enum class RemoteKey { OK, LEFT, RIGHT, UP, DOWN, BACK, PLAY_PAUSE, PLAY, PAUSE }

/** What the viewer currently sees, from least to most: nothing, the seek bar, full controls, a side panel. */
enum class ControlsSurface { NOTHING, SEEK_BAR, CONTROLS, PANEL }

internal fun PlayerControlsState.controlsSurface(): ControlsSurface = when {
    activeOverlay != ActiveOverlay.NONE -> ControlsSurface.PANEL
    !isVisible -> ControlsSurface.NOTHING
    isFullControlsVisible -> ControlsSurface.CONTROLS
    else -> ControlsSurface.SEEK_BAR
}

sealed interface KeyAction {
    /** Show the seek bar and move the pending seek target by [deltaMs]. Several presses commit as one seek. */
    data class Seek(val deltaMs: Long) : KeyAction

    /** Photos: go to the previous (-1) or next (+1) item instead of seeking. */
    data class StepItem(val delta: Int) : KeyAction

    /** Pause if playing, then open full controls with focus on Play. */
    object PauseAndOpenControls : KeyAction

    /** Open full controls; the video keeps playing. */
    object OpenControls : KeyAction

    /** Play, pause or toggle. [quiet] shows only a small badge and no controls. */
    data class Transport(val command: String, val quiet: Boolean) : KeyAction

    object HideSeekBar : KeyAction
    object HideControls : KeyAction
    object ClosePanel : KeyAction

    /** First Back on a bare video: show "Press Back again to stop". */
    object ArmBack : KeyAction

    /** Leave the player. */
    object ExitPlayer : KeyAction

    /** Swallow the key. */
    object Ignore : KeyAction

    /** Not ours: Compose focus handling decides (moving along the row, pressing a button). */
    object PassThrough : KeyAction
}

/**
 * @property repeatCount Android's KeyEvent.repeatCount; grows while a key is held.
 * @property backArmed a first Back was pressed within [BACK_CONFIRM_WINDOW_MS].
 * @property confirmBack Back on a bare video needs a second press. False for pre-play, the Still
 *   Watching prompt, photos, loading and error states, where Back leaves at once as before.
 * @property isImage photos use Left/Right for previous/next photo.
 * @property quietAllowed media keys may pause without opening controls (no pre-play or prompt on screen).
 */
data class KeyContext(
    val surface: ControlsSurface,
    val repeatCount: Int = 0,
    val backArmed: Boolean = false,
    val confirmBack: Boolean = true,
    val isImage: Boolean = false,
    val quietAllowed: Boolean = true,
)

const val SEEK_STEP_MS = 10_000L
const val SEEK_FAST_STEP_MS = 50_000L
const val SEEK_FAST_AFTER_REPEATS = 10

/** 10 s per press, 50 s per repeat once a key has been held for more than 10 repeats. */
fun seekStepMs(repeatCount: Int): Long =
    if (repeatCount > SEEK_FAST_AFTER_REPEATS) SEEK_FAST_STEP_MS else SEEK_STEP_MS

fun resolveKeyAction(key: RemoteKey, ctx: KeyContext): KeyAction {
    val surface = ctx.surface
    val bare = surface == ControlsSurface.NOTHING || surface == ControlsSurface.SEEK_BAR
    return when (key) {
        RemoteKey.OK -> if (bare) KeyAction.PauseAndOpenControls else KeyAction.PassThrough

        RemoteKey.LEFT, RemoteKey.RIGHT -> {
            val direction = if (key == RemoteKey.LEFT) -1 else 1
            when {
                bare && ctx.isImage -> KeyAction.StepItem(direction)
                bare -> KeyAction.Seek(direction * seekStepMs(ctx.repeatCount))
                // Left closes a panel; a held key must not close it and then run on into the controls.
                surface == ControlsSurface.PANEL && key == RemoteKey.LEFT ->
                    if (ctx.repeatCount == 0) KeyAction.ClosePanel else KeyAction.Ignore
                // On the controls Compose moves focus between buttons, or seeks on the seek bar.
                else -> KeyAction.PassThrough
            }
        }

        RemoteKey.UP, RemoteKey.DOWN ->
            if (bare) KeyAction.OpenControls else KeyAction.PassThrough

        RemoteKey.BACK -> when (surface) {
            ControlsSurface.PANEL -> KeyAction.ClosePanel
            ControlsSurface.CONTROLS -> KeyAction.HideControls
            ControlsSurface.SEEK_BAR -> KeyAction.HideSeekBar
            ControlsSurface.NOTHING ->
                if (!ctx.confirmBack || ctx.backArmed) KeyAction.ExitPlayer else KeyAction.ArmBack
        }

        RemoteKey.PLAY_PAUSE, RemoteKey.PLAY, RemoteKey.PAUSE -> {
            val command = when (key) {
                RemoteKey.PLAY -> "play"
                RemoteKey.PAUSE -> "pause"
                else -> "toggle"
            }
            // Nothing on screen or just the seek bar: a small badge only. With controls or a
            // panel open they stay as they are (focus does not move, the panel stays open).
            KeyAction.Transport(command, quiet = bare && ctx.quietAllowed)
        }
    }
}
