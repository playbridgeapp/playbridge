package com.playbridge.sender.cast.remote.ui

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.LocalRemoteMotionPolicy
import com.playbridge.sender.cast.remote.RemoteFeedback
import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.input.PadDirection
import com.playbridge.sender.cast.remote.input.PadGesturePolicy
import com.playbridge.sender.cast.remote.input.PadStep
import com.playbridge.sender.cast.remote.input.PadStepKind
import com.playbridge.sender.cast.remote.rememberRemoteHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Exclusion stays on the pad or pointer rect. Android 9 and earlier keep insets only. */
internal fun Modifier.remotePadExclusion(): Modifier =
    if (Build.VERSION.SDK_INT >= 29) systemGestureExclusion() else this

@Composable
internal fun RemoteGesturePad(
    enabled: Boolean,
    onStep: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberRemoteHaptics()
    val onStepState = rememberUpdatedState(onStep)
    val enabledState = rememberUpdatedState(enabled)
    val motion = rememberUpdatedState(LocalRemoteMotionPolicy.current)
    val clock = remember { GestureClock() }
    val policy = remember { PadGesturePolicy(clock = { clock.now }) }
    var flash by remember { mutableStateOf<PadDirection?>(null) }
    var flashNonce by remember { mutableIntStateOf(0) }

    fun emit(steps: List<PadStep>) {
        if (!enabledState.value) return
        for (step in steps) {
            onStepState.value(step.key)
            haptics.perform(RemoteFeedback.PAD_STEP)
            if (step.direction != null && motion.value.animate) {
                flash = step.direction
                flashNonce++
            }
        }
    }

    LaunchedEffect(flashNonce) {
        if (flash == null) return@LaunchedEffect
        val ms = motion.value.duration(150).coerceAtMost(150)
        if (ms <= 0) {
            flash = null
            return@LaunchedEffect
        }
        delay(ms.toLong())
        flash = null
    }

    val actions = listOf(
        PadDirection.Up to "Up",
        PadDirection.Down to "Down",
        PadDirection.Left to "Left",
        PadDirection.Right to "Right",
    ).map { (direction, label) ->
        CustomAccessibilityAction(label) {
            if (!enabledState.value) return@CustomAccessibilityAction false
            policy.cancel()
            emit(listOf(PadStep(direction.key.wireValue, PadStepKind.SWIPE, direction)))
            true
        }
    } + CustomAccessibilityAction("OK") {
        if (!enabledState.value) return@CustomAccessibilityAction false
        policy.cancel()
        emit(listOf(PadStep(RemoteKey.Ok.wireValue, PadStepKind.TAP, null)))
        true
    }

    Box(
        modifier
            .testTag("remote.gesturePad")
            .fillMaxSize()
            .remotePadExclusion()
            .semantics {
                contentDescription = "Gesture pad"
                if (!enabled) disabled()
                customActions = actions
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    var flick: Job? = null
                    try {
                        awaitEachGesture {
                            flick?.cancel()
                            val down = awaitFirstDown()
                            down.consume()
                            clock.observe(down.uptimeMillis)
                            policy.down(down.position.x, down.position.y)
                            val hold = launch {
                                while (isActive) {
                                    if (!policy.isArmed()) {
                                        delay(16)
                                        continue
                                    }
                                    val deadline = policy.holdDeadline()
                                    val wait = deadline - clock.now
                                    if (wait > 0) delay(wait)
                                    if (!isActive || !policy.isArmed()) continue
                                    clock.advanceTo(deadline)
                                    val steps = policy.tick()
                                    if (steps.isEmpty()) delay(16) else emit(steps)
                                }
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    clock.observe(change.uptimeMillis)
                                    if (!change.pressed) {
                                        emit(policy.up(change.position.x, change.position.y))
                                        break
                                    }
                                    emit(policy.move(change.position.x, change.position.y))
                                }
                            } finally {
                                hold.cancel()
                            }
                            flick = launch {
                                while (isActive && policy.hasPendingFlick()) {
                                    val deadline = policy.flickDeadline()
                                    val wait = deadline - clock.now
                                    if (wait > 0) {
                                        delay(wait)
                                        clock.advanceTo(deadline)
                                    }
                                    if (!isActive) break
                                    val steps = policy.tick()
                                    if (steps.isEmpty()) break
                                    emit(steps)
                                }
                            }
                        }
                    } finally {
                        flick?.cancel()
                        policy.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val direction = flash
        if (direction != null && motion.value.animate) {
            Icon(
                when (direction) {
                    PadDirection.Up -> Icons.Default.ArrowUpward
                    PadDirection.Down -> Icons.Default.ArrowDownward
                    PadDirection.Left -> Icons.Default.ChevronLeft
                    PadDirection.Right -> Icons.Default.ChevronRight
                },
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        } else {
            Text(
                "Swipe to move\nTap for OK",
                modifier = Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Event timestamps drive recognition. The hold and flick loops only move time forward to a deadline. */
private class GestureClock {
    var now: Long = 0L
        private set

    fun observe(time: Long) {
        if (time > now) now = time
    }

    fun advanceTo(time: Long) {
        if (time > now) now = time
    }
}
