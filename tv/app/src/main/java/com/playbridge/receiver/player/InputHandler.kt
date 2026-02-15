package com.playbridge.receiver.player

import android.app.Activity
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "InputHandler"

/**
 * Handles all input sources for the player: phone control commands,
 * phone remote key simulation, and physical TV remote D-pad events.
 */
class InputHandler(
    private val activity: Activity,
    private val playerProvider: () -> ExoPlayer?,
    private val controls: PlayerControlsManager
) {

    /**
     * Handle phone-originated control commands (pause, play, stop, toggle, seek).
     */
    fun handleControlCommand(command: String?) {
        Log.i(TAG, "Control command: $command")

        when (command) {
            "pause" -> playerProvider()?.pause()
            "play" -> playerProvider()?.play()
            "stop" -> {
                playerProvider()?.stop()
                activity.finish()
            }
            "toggle" -> {
                playerProvider()?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
            }
            "seek_back" -> {
                playerProvider()?.let {
                    val newPos = maxOf(0L, it.currentPosition - 10_000L)
                    it.seekTo(newPos)
                    controls.showSeekUI()
                }
            }
            "seek_forward" -> {
                playerProvider()?.let {
                    val dur = it.duration
                    val newPos = if (dur > 0) minOf(dur, it.currentPosition + 10_000L) else it.currentPosition + 10_000L
                    it.seekTo(newPos)
                    controls.showSeekUI()
                }
            }
        }
    }

    /**
     * Handle phone remote D-pad simulation by dispatching synthetic key events.
     */
    fun handleRemoteCommand(key: String?) {
        Log.i(TAG, "Remote command: $key")

        val keyCode = when (key) {
            "dpad_up" -> KeyEvent.KEYCODE_DPAD_UP
            "dpad_down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "dpad_left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "dpad_right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "dpad_center" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            else -> null
        }

        if (keyCode != null) {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)

            activity.runOnUiThread {
                activity.dispatchKeyEvent(downEvent)
                activity.dispatchKeyEvent(upEvent)
            }
        }
    }

    /**
     * Handle physical TV remote key presses. Returns true if the event was consumed.
     *
     * @param controlsRoot the controls overlay view, used to check visibility/focus
     * @param currentFocus the currently focused view, if any
     */
    fun handleKeyDown(keyCode: Int, event: KeyEvent?, controlsRoot: View, currentFocus: View?): Boolean {
        // If controls are visible and a navigable element has focus,
        // let the system handle D-pad navigation
        if (controlsRoot.visibility == View.VISIBLE && currentFocus != null) {
            val focusedId = currentFocus.id
            val isNavigable = focusedId == com.playbridge.receiver.R.id.btn_play_pause ||
                              focusedId == com.playbridge.receiver.R.id.btn_tracks ||
                              focusedId == com.playbridge.receiver.R.id.player_seekbar

            if (isNavigable) {
                if (focusedId == com.playbridge.receiver.R.id.player_seekbar &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    // Fall through to custom scrubbing logic below
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                           keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                           keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    return false // Let system handle navigation
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                controls.togglePlayPause()
                controls.showControlsUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                controls.togglePlayPause()
                controls.showControlsUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playerProvider()?.play()
                controls.showControlsUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                playerProvider()?.pause()
                controls.showControlsUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                playerProvider()?.stop()
                activity.finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val repeatCount = event?.repeatCount ?: 0
                val multiplier = if (repeatCount > 10) 5 else 1
                controls.handleScrubbing(-10000L * multiplier)
                controls.showSeekUI()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val repeatCount = event?.repeatCount ?: 0
                val multiplier = if (repeatCount > 10) 5 else 1
                controls.handleScrubbing(10000L * multiplier)
                controls.showSeekUI()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!controls.isScrubbing) controls.showControlsUI()
                return true
            }
        }
        return false
    }
}
