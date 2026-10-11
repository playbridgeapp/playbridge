package com.playbridge.sender.cast.remote

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun RemoteButton(icon: ImageVector, label: String, enabled: Boolean, action: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick = action, enabled = enabled, interactionSource = source,
        modifier = Modifier.size(48.dp).remotePressFeedback(source),
    ) { Icon(icon, contentDescription = label) }
}

@Composable
internal fun RemoteNavBar(nav: RemoteNavAvailability, onRemoteKey: (String) -> Unit, onKeys: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f), shape = CircleShape) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteButton(Icons.AutoMirrored.Filled.ArrowBack, if (nav.back) "TV Back" else "TV Back unavailable: no remote route", nav.back) { onRemoteKey("back") }
            RemoteButton(Icons.Default.Home, if (nav.home) "TV Home" else "TV Home unavailable: no Home route; full TV control is coming later", nav.home) { onRemoteKey("home") }
            RemoteButton(Icons.Default.Keyboard, if (nav.keys) "Keys" else "Keys unavailable: text input requires the TV browser", nav.keys, onKeys)
            RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, if (nav.volume) "TV volume down" else "TV volume down unavailable: no volume route", nav.volume) { onRemoteKey("volume_down") }
            RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, if (nav.volume) "TV volume up" else "TV volume up unavailable: no volume route", nav.volume) { onRemoteKey("volume_up") }
        }
    }
}

@Composable
internal fun RemoteTransportRow(
    isPlaying: Boolean, enabled: Boolean, showPlayPause: Boolean, showSeek: Boolean,
    previous: Int?, next: Int?, onJumpToEpisode: (Int) -> Unit,
    onSeekBack: () -> Unit, onSeekForward: () -> Unit, onPlayPause: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (previous != null) RemoteButton(Icons.Default.SkipPrevious, "Previous episode", enabled) { onJumpToEpisode(previous) }
        if (showSeek) RemoteButton(Icons.Default.Replay10, "Seek back 10 seconds", enabled, onSeekBack)
        if (showPlayPause) RemoteButton(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", enabled, onPlayPause)
        if (showSeek) RemoteButton(Icons.Default.Forward10, "Seek forward 10 seconds", enabled, onSeekForward)
        if (next != null) RemoteButton(Icons.Default.SkipNext, "Next episode", enabled) { onJumpToEpisode(next) }
    }
}

@Composable
internal fun DpadArea(onRemoteKey: (String) -> Unit, enabled: Boolean) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = 12.dp)
            .then(if (Build.VERSION.SDK_INT >= 29) Modifier.systemGestureExclusion() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ArrowButton("dpad_up", "TV Up", Icons.Default.ArrowUpward, enabled, onRemoteKey)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArrowButton("dpad_left", "TV Left", Icons.Default.ChevronLeft, enabled, onRemoteKey)
                val haptics = rememberRemoteHaptics()
                val source = remember { MutableInteractionSource() }
                IconButton(
                    onClick = { onRemoteKey("dpad_center"); haptics.perform(RemoteFeedback.PAD_STEP) },
                    enabled = enabled, interactionSource = source,
                    modifier = Modifier.size(80.dp).remotePressFeedback(source)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                ) { Text("OK", color = MaterialTheme.colorScheme.onPrimaryContainer) }
                ArrowButton("dpad_right", "TV Right", Icons.Default.ChevronRight, enabled, onRemoteKey)
            }
            ArrowButton("dpad_down", "TV Down", Icons.Default.ArrowDownward, enabled, onRemoteKey)
        }
    }
}

@Composable
private fun ArrowButton(key: String, label: String, icon: ImageVector, enabled: Boolean, onRemoteKey: (String) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberRemoteHaptics()
    val callback by rememberUpdatedState(onRemoteKey)
    val step = { callback(key); haptics.perform(RemoteFeedback.PAD_STEP) }
    Box(
        Modifier.size(64.dp).remotePressFeedback(source).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
                onClick { if (enabled) step(); enabled }
            }
            .pointerInput(enabled, key) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val press = PressInteraction.Press(down.position)
                        source.tryEmit(press)
                        val policy = RemoteRepeatPolicy(SystemClock::uptimeMillis)
                        if (policy.press(key)) step()
                        val repeat = launch {
                            delay(RemoteRepeatPolicy.INITIAL_DELAY_MS)
                            while (policy.next()) { step(); delay(RemoteRepeatPolicy.CADENCE_MS) }
                        }
                        try {
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed || change.position.x !in 0f..size.width.toFloat() ||
                                    change.position.y !in 0f..size.height.toFloat()) break
                            } while (true)
                        } finally {
                            repeat.cancel()
                            policy.cancel()
                            source.tryEmit(PressInteraction.Cancel(press))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) }
}

/** Called only while motion is enabled and playback is active, so no inactive infinite clock remains. */
@Composable
internal fun remoteWaveClocks(): Pair<Float, Float> {
    val transition = rememberInfiniteTransition(label = "remoteWave")
    val phase by transition.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "phase")
    val breath by transition.animateFloat(0.8f, 1.2f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breath")
    return phase to breath
}
