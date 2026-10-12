package com.playbridge.sender.cast.remote.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.remoteDoubleTapAction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln

private enum class TwoFingerMode { UNDECIDED, SCROLL, ZOOM, ROTATE }

/** Existing pointer surface: click, drag, two-finger scroll, long-press drag, image pinch/rotate, and a final flush. */
@Composable
internal fun RemotePointerArea(
    enabled: Boolean = true,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit,
    onPinchZoom: (factor: Float) -> Unit = {},
    onRotate: (degrees: Float) -> Unit = {},
    onTransformAnchor: (x: Float, y: Float) -> Unit = { _, _ -> },
    onDoubleTap: () -> Unit = onMouseClick,
    imageGestures: Boolean = false,
    onMouseDown: () -> Unit = {},
    onMouseUp: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
) {
    val ink = MaterialTheme.colorScheme.onSurface
    var isScrolling by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    val latestOnGestureEnd by rememberUpdatedState(onGestureEnd)
    DisposableEffect(Unit) {
        onDispose { if (isDragging) onMouseUp(); latestOnGestureEnd() }
    }

    Box(
        modifier = Modifier
            .testTag("remote.pointer")
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .remotePadExclusion()
            .remoteSolidSurface(RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(enabled, imageGestures) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        var twoFingerMode = TwoFingerMode.UNDECIDED
                        var initialPinchDistance: Float? = null
                        var twoFingerStartTimeMs = 0L
                        var accumPan = 0f
                        var rotationReferenceAngle: Float? = null
                        val gestureSlop = 24f
                        val zoomSlopLogRatio = 0.08f
                        val imageTransformSlopPx = 16f
                        val imageZoomObservationMs = 120L
                        val modeDominance = 1.2f

                        var downTime = 0L
                        var downPos = Offset.Zero
                        var maxMoveDist = 0f
                        val clickSlop = 15f
                        val clickTimeout = 300L
                        val doubleTapTimeout = 350L
                        val doubleTapSlop = 40f
                        var lastTapTime = 0L
                        var lastTapPosition = Offset.Zero

                        while (true) {
                            val event = awaitPointerEvent()
                            val pointerCount = event.changes.count { it.pressed }

                            if (pointerCount >= 2) {
                                isScrolling = true
                                val pressed = event.changes.filter { it.pressed }
                                val a = pressed.getOrNull(0)
                                val b = pressed.getOrNull(1)
                                if (a != null && b != null && a.previousPressed && b.previousPressed) {
                                    val midpoint = (a.position + b.position) / 2f
                                    onTransformAnchor(
                                        (midpoint.x / size.width).coerceIn(0f, 1f),
                                        (midpoint.y / size.height).coerceIn(0f, 1f),
                                    )
                                    val curDist = (a.position - b.position).getDistance()
                                    val prevDist = (a.previousPosition - b.previousPosition).getDistance()
                                    val panX = ((a.position.x + b.position.x) - (a.previousPosition.x + b.previousPosition.x)) / 2f
                                    val panY = ((a.position.y + b.position.y) - (a.previousPosition.y + b.previousPosition.y)) / 2f
                                    val currentVector = a.position - b.position
                                    val previousVector = a.previousPosition - b.previousPosition
                                    val currentAngle = atan2(currentVector.y, currentVector.x)
                                    val previousAngle = atan2(previousVector.y, previousVector.x)
                                    var angleDelta = currentAngle - previousAngle
                                    while (angleDelta > PI.toFloat()) angleDelta -= (2 * PI).toFloat()
                                    while (angleDelta < -PI.toFloat()) angleDelta += (2 * PI).toFloat()
                                    val angleDeltaDegrees = Math.toDegrees(angleDelta.toDouble()).toFloat()
                                    val referenceAngle = rotationReferenceAngle ?: currentAngle.also {
                                        rotationReferenceAngle = it
                                    }
                                    var netRotation = currentAngle - referenceAngle
                                    while (netRotation > PI.toFloat()) netRotation -= (2 * PI).toFloat()
                                    while (netRotation < -PI.toFloat()) netRotation += (2 * PI).toFloat()

                                    if (twoFingerMode == TwoFingerMode.UNDECIDED) {
                                        val startDistance = initialPinchDistance ?: curDist.also {
                                            initialPinchDistance = it
                                            twoFingerStartTimeMs = SystemClock.uptimeMillis()
                                        }
                                        accumPan += abs(panX) + abs(panY)
                                        val zoomProgress = if (startDistance > 0f && curDist > 0f) {
                                            abs(ln((curDist / startDistance).toDouble())).toFloat() / zoomSlopLogRatio
                                        } else {
                                            0f
                                        }
                                        if (imageGestures) {
                                            val radialTravel = abs(curDist - startDistance)
                                            val averageRadius = (curDist + startDistance) / 4f
                                            val tangentialTravel = abs(netRotation) * averageRadius * 2f
                                            val elapsed = SystemClock.uptimeMillis() - twoFingerStartTimeMs
                                            twoFingerMode = when {
                                                tangentialTravel > imageTransformSlopPx &&
                                                    tangentialTravel >= radialTravel * modeDominance -> TwoFingerMode.ROTATE
                                                elapsed >= imageZoomObservationMs &&
                                                    radialTravel > imageTransformSlopPx &&
                                                    radialTravel >= tangentialTravel * 1.5f -> TwoFingerMode.ZOOM
                                                else -> TwoFingerMode.UNDECIDED
                                            }
                                        } else {
                                            val panProgress = accumPan / gestureSlop
                                            twoFingerMode = when {
                                                zoomProgress > 1f && zoomProgress >= panProgress * modeDominance -> TwoFingerMode.ZOOM
                                                panProgress > 1f -> TwoFingerMode.SCROLL
                                                zoomProgress > 1.75f -> TwoFingerMode.ZOOM
                                                else -> TwoFingerMode.UNDECIDED
                                            }
                                        }
                                    }

                                    when (twoFingerMode) {
                                        TwoFingerMode.ZOOM -> if (prevDist > 0f) onPinchZoom(curDist / prevDist)
                                        TwoFingerMode.ROTATE -> onRotate(angleDeltaDegrees)
                                        TwoFingerMode.SCROLL -> onMouseScroll(panX, panY * 2f)
                                        TwoFingerMode.UNDECIDED -> Unit
                                    }
                                    a.consume(); b.consume()
                                }
                            } else if (pointerCount == 1 && !isScrolling) {
                                val change = event.changes.first()
                                if (change.pressed && !change.previousPressed) {
                                    downTime = System.currentTimeMillis()
                                    downPos = change.position
                                    maxMoveDist = 0f
                                }
                                if (change.pressed && change.previousPressed) {
                                    val delta = change.position - change.previousPosition
                                    val totalDist = (change.position - downPos).getDistance()
                                    if (totalDist > maxMoveDist) maxMoveDist = totalDist
                                    onMouseMove(delta.x * 1.5f, delta.y * 1.5f)
                                    change.consume()
                                }
                            } else if (pointerCount == 0) {
                                val upTime = System.currentTimeMillis()
                                val duration = upTime - downTime
                                if (!isScrolling && duration < clickTimeout && maxMoveDist < clickSlop && downTime > 0L) {
                                    val isDoubleTap = lastTapTime > 0L &&
                                        upTime - lastTapTime <= doubleTapTimeout &&
                                        (downPos - lastTapPosition).getDistance() <= doubleTapSlop
                                    if (isDoubleTap) {
                                        if (remoteDoubleTapAction(imageGestures) == "reset") onDoubleTap()
                                        lastTapTime = 0L
                                    } else {
                                        onMouseClick()
                                        lastTapTime = upTime
                                        lastTapPosition = downPos
                                    }
                                }
                                isScrolling = false
                                twoFingerMode = TwoFingerMode.UNDECIDED
                                initialPinchDistance = null
                                twoFingerStartTimeMs = 0L
                                accumPan = 0f
                                rotationReferenceAngle = null
                                downTime = 0L
                                onGestureEnd()
                            }
                        }
                    }
                }
                .pointerInput(enabled, imageGestures) {
                    if (!enabled) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { isDragging = true; onMouseDown() },
                        onDrag = { change, dragAmount -> change.consume(); onMouseMove(dragAmount.x * 1.5f, dragAmount.y * 1.5f) },
                        onDragEnd = { if (isDragging) { isDragging = false; onMouseUp() } },
                        onDragCancel = { if (isDragging) { isDragging = false; onMouseUp() } },
                    )
                },
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridColor = ink.copy(alpha = 0.05f)
            val cols = 3
            val rows = 3
            val colWidth = size.width / cols
            val rowHeight = size.height / rows
            for (i in 1 until cols) {
                drawLine(gridColor, Offset(i * colWidth, 0f), Offset(i * colWidth, size.height), strokeWidth = 1f)
            }
            for (i in 1 until rows) {
                drawLine(gridColor, Offset(0f, i * rowHeight), Offset(size.width, i * rowHeight), strokeWidth = 1f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (imageGestures) "1 finger: move  •  Pinch: zoom  •  Twist: rotate  •  Double-tap: reset"
                else "1 finger: move  •  2 fingers: scroll  •  Pinch: zoom  •  Tap: click  •  Long-press+drag: drag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        if (imageGestures) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FilledTonalIconButton(enabled = LocalRemoteControlsEnabled.current, onClick = { onRotate(-90f) }) {
                    Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "Rotate left 90 degrees")
                }
                FilledTonalIconButton(enabled = LocalRemoteControlsEnabled.current, onClick = { onRotate(90f) }) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate right 90 degrees")
                }
            }
        }
    }
}
