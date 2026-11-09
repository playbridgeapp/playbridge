package com.playbridge.sender.player

import android.media.AudioManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Taps show and hide the controls, double-taps skip 10 s, horizontal drags seek, and vertical
 * drags set brightness (left half) or volume (right half). Locked or in picture-in-picture,
 * only the unlock affordance responds.
 */
internal fun Modifier.playerGestures(state: PlayerScreenState, isInPip: Boolean, haptic: HapticFeedback): Modifier =
    this
        .pointerInput(state.locked, isInPip) {
            detectTapGestures(
                onTap = {
                    if (isInPip) return@detectTapGestures
                    if (state.locked) {
                        state.showUnlock = true
                    } else {
                        state.controlsVisible = !state.controlsVisible
                        if (state.controlsVisible) state.markInteraction()
                    }
                },
                onDoubleTap = { offset ->
                    if (isInPip) return@detectTapGestures
                    if (!state.locked) {
                        if (offset.x < size.width / 2f) {
                            state.doubleTapSeconds = if (state.doubleTapSide == -1) state.doubleTapSeconds + 10 else 10
                            state.doubleTapSide = -1
                            state.seekBy(-10_000)
                        } else {
                            state.doubleTapSeconds = if (state.doubleTapSide == 1) state.doubleTapSeconds + 10 else 10
                            state.doubleTapSide = 1
                            state.seekBy(10_000)
                        }
                    }
                }
            )
        }
        .pointerInput(state.locked, isInPip) {
            val slop = viewConfiguration.touchSlop
            var startX = 0f
            var accDx = 0f
            var accDy = 0f
            var seekStartMs = 0L
            detectDragGestures(
                onDragStart = { offset ->
                    if (isInPip) return@detectDragGestures
                    startX = offset.x
                    accDx = 0f; accDy = 0f
                    state.dragMode = DragMode.NONE
                    seekStartMs = state.player.currentPosition
                    // Re-sync in case volume was changed by hardware keys since last drag.
                    state.volume = state.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                },
                onDragEnd = {
                    if (state.dragMode == DragMode.SEEK) {
                        state.player.seekTo(state.seekPreviewMs)
                        state.playbackProgress.longValue = state.seekPreviewMs
                    }
                    state.dragMode = DragMode.NONE
                },
                onDragCancel = { state.dragMode = DragMode.NONE },
                onDrag = onDrag@{ change, amount ->
                    if (state.locked) return@onDrag
                    change.consume()
                    accDx += amount.x
                    accDy += amount.y
                    if (state.dragMode == DragMode.NONE) {
                        if (abs(accDx) > slop && abs(accDx) > abs(accDy)) {
                            state.dragMode = DragMode.SEEK
                        } else if (abs(accDy) > slop) {
                            state.dragMode = if (startX < size.width / 2f) DragMode.BRIGHTNESS else DragMode.VOLUME
                        }
                    }
                    when (state.dragMode) {
                        DragMode.SEEK -> {
                            val d = state.playbackDuration.longValue
                            if (d > 0) {
                                val delta = ((accDx / size.width) * SEEK_RANGE_MS).toLong()
                                state.seekPreviewMs = (seekStartMs + delta).coerceIn(0, d)
                                state.seekDeltaMs = state.seekPreviewMs - seekStartMs
                            }
                        }
                        DragMode.VOLUME -> {
                            val maxVolume = state.maxVolume
                            val prevVolume = state.volume
                            val newVolume = (state.volume - (amount.y / size.height) * maxVolume)
                                .coerceIn(0f, maxVolume.toFloat())
                            state.volume = newVolume
                            state.audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC, state.volume.roundToInt(), 0
                            )
                            if ((newVolume == 0f && prevVolume > 0f) || (newVolume == maxVolume.toFloat() && prevVolume < maxVolume.toFloat())) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                        DragMode.BRIGHTNESS -> {
                            val prevBrightness = state.brightness
                            val newBrightness = (state.brightness - amount.y / size.height).coerceIn(0.01f, 1f)
                            state.brightness = newBrightness
                            state.window?.let {
                                val lp = it.attributes
                                lp.screenBrightness = state.brightness
                                it.attributes = lp
                            }
                            if ((newBrightness == 0.01f && prevBrightness > 0.01f) || (newBrightness == 1f && prevBrightness < 1f)) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                        DragMode.NONE -> {}
                    }
                }
            )
        }

/** Pinch to zoom and drag to pan, while pan & zoom is on. */
internal fun Modifier.panZoomGestures(state: PlayerScreenState): Modifier =
    pointerInput(state.isPanZoomEnabled) {
        if (state.isPanZoomEnabled) {
            detectTransformGestures { _, pan, zoom, _ ->
                state.scale = (state.scale * zoom).coerceIn(1f, 5f)
                if (state.scale > 1f) {
                    state.panOffset += pan
                } else {
                    state.panOffset = Offset.Zero
                }
            }
        }
    }

/** How far two fingers must spread (or close) before the portrait fill switches. */
internal const val PINCH_OPEN = 1.15f
internal const val PINCH_CLOSE = 1 / PINCH_OPEN

/** The fill a pinch asks for once it has moved far enough: open fills, close fits; null until then. */
internal fun pinchFill(totalZoom: Float): Boolean? = when {
    totalZoom >= PINCH_OPEN -> true
    totalZoom <= PINCH_CLOSE -> false
    else -> null
}

/**
 * Two-finger pinch on an upright screen showing a landscape video: open to fill the screen
 * (cropping the sides), close to go back to the portrait layout. One pinch switches once. Taps and
 * one-finger drags pass through to [playerGestures].
 */
internal fun Modifier.portraitFillGestures(state: PlayerScreenState, enabled: Boolean): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            var zoom = 1f
            var switched = false
            do {
                // Initial pass: a pinch is claimed before the drag detector reads it as brightness or volume.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    state.dragMode = DragMode.NONE
                    if (!switched) {
                        zoom *= event.calculateZoom()
                        pinchFill(zoom)?.let { fill ->
                            state.switchPortraitFill(fill)
                            switched = true
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
