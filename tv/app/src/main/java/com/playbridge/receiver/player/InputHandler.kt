package com.playbridge.receiver.player

import android.app.Activity
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "InputHandler"

/**
 * Handles all input sources for the player: phone control commands,
 * phone remote key simulation, and physical TV remote D-pad events.
 *
 * Two modes of operation:
 *   1. Normal (overlay hidden): left/right seek, up/down volume, center pauses + opens overlay
 *   2. Overlay (pause menu visible): left/right navigate buttons, up/down ignored, center clicks button
 */
class InputHandler(
    private val activity: Activity,
    private val audioManager: AudioManager,
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
     * Called from dispatchKeyEvent to intercept key presses BEFORE views.
     * Returns true if consumed, false to let the system handle it.
     */
    fun handleKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
        // Only handle ACTION_DOWN
        if (event?.action != KeyEvent.ACTION_DOWN) return false

        // --- Full controls overlay is visible (pause menu) ---
        if (controls.isFullOverlayVisible) {
            return when (keyCode) {
                // Up/Down: consume silently (no volume change in overlay)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                // Left/Right: let system do focus navigation between buttons
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> false
                // Center/Enter: let system deliver click to focused button
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> false
                // Back: let system handle (exits player)
                KeyEvent.KEYCODE_BACK -> false
                // Media keys still work
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    controls.togglePlayPause()
                    true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    playerProvider()?.stop()
                    activity.finish()
                    true
                }
                else -> false
            }
        }

        // --- Normal mode (no overlay) ---
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                playerProvider()?.pause()
                controls.updatePlayPauseIcon()
                controls.showControlsUI()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val repeatCount = event.repeatCount
                val multiplier = if (repeatCount > 10) 5 else 1
                controls.handleScrubbing(-10000L * multiplier)
                controls.showSeekUI()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val repeatCount = event.repeatCount
                val multiplier = if (repeatCount > 10) 5 else 1
                controls.handleScrubbing(10000L * multiplier)
                controls.showSeekUI()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                controls.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playerProvider()?.play()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                playerProvider()?.pause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                playerProvider()?.stop()
                activity.finish()
                true
            }
            // Back: let system handle (exits player)
            else -> false
        }
    }
}
