package com.playbridge.player.player

import android.app.Activity
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.playbridge.player.ui.player.PlayerControlsViewModel
import com.playbridge.player.ui.player.ActiveOverlay

private const val TAG = "InputHandler"

/**
 * Handles all input sources for the player: phone control commands,
 * phone remote key simulation, and physical TV remote D-pad events.
 */
class InputHandler(
    private val activity: Activity,
    private val audioManager: AudioManager,
    private val engine: PlayerEngineAdapter,
    private val controls: PlayerControlsViewModel,
    private val isExternalOverlayVisible: () -> Boolean = { false }
) {

    /**
     * Handle phone-originated control commands (pause, play, stop, toggle, seek).
     */
    fun handleControlCommand(command: String?) {
        Log.i(TAG, "Control command: $command")

        // Absolute seek from the phone seekbar: "seek_to:<positionMs>"
        if (command != null && command.startsWith("seek_to:")) {
            val pos = command.substringAfter("seek_to:").toLongOrNull()
            if (pos != null) {
                val dur = engine.duration
                val clamped = if (dur > 0) pos.coerceIn(0L, dur) else pos.coerceAtLeast(0L)
                engine.seekTo(clamped)
                controls.showSeekUI()
            }
            return
        }

        // Settings commands routed through the controls VM (engine-agnostic):
        // playback speed, audio boost, subtitle offset, external subtitle.
        if (command != null) {
            when {
                command.startsWith("speed:") -> {
                    command.removePrefix("speed:").toFloatOrNull()?.let { controls.setPlaybackSpeed(it) }
                    return
                }
                command.startsWith("sub_offset:") -> {
                    command.removePrefix("sub_offset:").toLongOrNull()?.let { controls.adjustSubtitleDelay(it) }
                    return
                }
                command.startsWith("add_subtitle:") -> {
                    val url = command.removePrefix("add_subtitle:")
                    if (url.isNotBlank()) controls.loadExternalSubtitle(url)
                    return
                }
                command == "audio_boost" -> {
                    controls.toggleAudioBoost()
                    return
                }
            }
        }

        when (command) {
            // Drive play/pause through the controls VM so the on-screen overlay
            // matches the play state: playing hides controls, pausing shows them —
            // regardless of whether the command came from the phone or the TV remote.
            "pause" -> {
                engine.pause()
                controls.setPlaying(false)
                controls.showControls(full = true, playing = false)
            }
            "play" -> {
                engine.play()
                controls.setPlaying(true)
                controls.hideControls()
            }
            "stop" -> {
                activity.finish()
            }
            "toggle" -> controls.togglePlayPause()
            "seek_back" -> {
                val newPos = maxOf(0L, engine.currentPosition - 10_000L)
                engine.seekTo(newPos)
                controls.showSeekUI()
            }
            "seek_forward" -> {
                val dur = engine.duration
                val newPos = if (dur > 0) minOf(dur, engine.currentPosition + 10_000L) else engine.currentPosition + 10_000L
                engine.seekTo(newPos)
                controls.showSeekUI()
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

        val state = controls.controlsState.value
        val isExtVisible = isExternalOverlayVisible()

        // --- Full controls overlay or external overlay is visible ---
        if (state.isVisible || isExtVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!state.isFullControlsVisible && !isExtVisible) {
                        adjustVolume(AudioManager.ADJUST_RAISE)
                        true
                    } else {
                        // Let system handle focus navigation in full overlay
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!state.isFullControlsVisible && !isExtVisible) {
                        adjustVolume(AudioManager.ADJUST_LOWER)
                        true
                    } else {
                        // Let system handle focus navigation in full overlay
                        false
                    }
                }
                // Left/Right: let system do focus navigation between buttons if full,
                // BUT if light seek mode, they should handle scrubbing.
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (!state.isFullControlsVisible && !isExtVisible) {
                        val repeatCount = event.repeatCount
                        val multiplier = if (repeatCount > 10) 5 else 1
                        controls.handleScrubbing(-10000L * multiplier)
                        controls.showSeekUI()
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!state.isFullControlsVisible && !isExtVisible) {
                        val repeatCount = event.repeatCount
                        val multiplier = if (repeatCount > 10) 5 else 1
                        controls.handleScrubbing(10000L * multiplier)
                        controls.showSeekUI()
                        true
                    } else {
                        false
                    }
                }
                // Center/Enter: if in seek UI (not full), pause and show full controls.
                // Otherwise, let system deliver click to focused button.
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // If an external overlay (PrePlayScreen, StreamPicker, etc.) is visible,
                    // let Compose handle the enter key so it reaches chips/buttons.
                    if (isExtVisible) return false

                    if (!state.isFullControlsVisible) {
                        controls.commitSeek() // Ensure any pending scrub is committed
                        if (engine.isPlaying) {
                            engine.pause()
                        }
                        controls.showControls(full = true, playing = false)
                        true
                    } else {
                        false
                    }
                }
                // Back: hide overlay or controls if visible, otherwise let system handle (exits player)
                KeyEvent.KEYCODE_BACK -> {
                    if (state.activeOverlay != ActiveOverlay.NONE) {
                        controls.hideOverlay()
                        true
                    } else {
                        if (state.isVisible) {
                            controls.hideControls()
                            true
                        } else {
                            false
                        }
                    }
                }
                // Media keys still work
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    controls.togglePlayPause()
                    true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    activity.finish()
                    true
                }
                else -> false
            }
        }

        // --- Normal mode (no overlay) ---
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (engine.isPlaying) {
                    engine.pause()
                }
                controls.showControls(full = true, playing = false)
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
                adjustVolume(AudioManager.ADJUST_RAISE)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                activity.finish()
                true
            }
            // Back: let system handle (exits player)
            else -> false
        }
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }
}
