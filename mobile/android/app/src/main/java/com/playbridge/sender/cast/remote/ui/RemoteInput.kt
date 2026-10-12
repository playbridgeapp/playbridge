package com.playbridge.sender.cast.remote.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.LocalRemoteMotionPolicy
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteCallbackActions
import com.playbridge.sender.cast.remote.RemoteFeedback
import com.playbridge.sender.cast.remote.RemoteInputMode
import com.playbridge.sender.cast.remote.RemoteInputPresentation
import com.playbridge.sender.cast.remote.RemoteLayoutSpec
import com.playbridge.sender.cast.remote.RemoteMediaKind
import com.playbridge.sender.cast.remote.RemoteRepeatPolicy
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.ready
import com.playbridge.sender.cast.remote.rememberRemoteHaptics
import com.playbridge.sender.cast.remote.remotePressFeedback
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ButtonPadExtent = 224.dp

@Composable
internal fun RemoteInput(state: RemoteUiState, layout: RemoteLayoutSpec, dispatch: (RemoteAction) -> Unit) {
    when (layout.inputPresentation) {
        RemoteInputPresentation.Absent -> Unit
        RemoteInputPresentation.Full -> FullRemoteInput(state, layout, dispatch)
    }
}

@Composable
private fun FullRemoteInput(state: RemoteUiState, layout: RemoteLayoutSpec, dispatch: (RemoteAction) -> Unit) {
    val enabled = state.connection.ready && LocalRemoteMotionPolicy.current.resumed
    val sendKey: (String) -> Unit = { dispatch(RemoteCallbackActions.key(it)) }
    val image = state.playback?.mediaKind == RemoteMediaKind.Image
    val showSwitch = RemoteInputMode.Pad in layout.modes && RemoteInputMode.Pointer in layout.modes
    val buttonPad = state.preferences.buttonPad
    Box(Modifier.fillMaxSize()) {
        key(layout.selectedMode, buttonPad, state.context, state.sessionId, state.playback?.mediaId, enabled) {
            when (layout.selectedMode) {
                RemoteInputMode.Pad -> if (buttonPad) FittedButtonPad(sendKey, enabled) else RemoteGesturePad(enabled, sendKey)
                RemoteInputMode.Pointer -> RemotePointerArea(
                    enabled = enabled,
                    onMouseMove = { x, y -> dispatch(RemoteAction.PointerMove(x, y)) },
                    onMouseClick = { dispatch(RemoteAction.PointerClick) },
                    onMouseScroll = { x, y -> dispatch(RemoteAction.PointerScroll(x, y)) },
                    onPinchZoom = { dispatch(RemoteAction.ImageZoom(it)) },
                    onRotate = { dispatch(RemoteAction.ImageRotate(it)) },
                    onTransformAnchor = { x, y -> dispatch(RemoteAction.ImageAnchor(x, y)) },
                    onDoubleTap = {
                        dispatch(if (image) RemoteAction.ResetImageTransform else RemoteAction.PointerClick)
                    },
                    imageGestures = image,
                    onMouseDown = { dispatch(RemoteAction.PointerDown) },
                    onMouseUp = { dispatch(RemoteAction.PointerUp) },
                    onGestureEnd = { dispatch(RemoteAction.PointerGestureEnd) },
                )
                RemoteInputMode.Context, RemoteInputMode.Keyboard -> if (layout.idle) IdleEmptyState(state.receiver?.name)
            }
        }
        if (showSwitch) {
            PadPointerSwitch(
                selected = layout.selectedMode,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = if (image) 88.dp else 8.dp),
                onSelect = { dispatch(RemoteAction.SelectInputMode(it)) },
            )
        }
        if (RemoteInputMode.Pad in layout.modes) {
            PadSettingsButton(Modifier.align(Alignment.TopStart).padding(8.dp)) { dispatch(RemoteAction.OpenSheet(RemoteSheet.InputPreferences)) }
        }
    }
}

@Composable
private fun PadSettingsButton(modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).remotePressFeedback(source), interactionSource = source) {
        Icon(Icons.Default.Settings, contentDescription = "Pad settings")
    }
}

@Composable
private fun PadPointerSwitch(selected: RemoteInputMode, modifier: Modifier, onSelect: (RemoteInputMode) -> Unit) {
    Row(
        modifier
            .testTag("remote.padPointerSwitch")
            .remoteSolidSurface(RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SwitchSegment("Pad", selected == RemoteInputMode.Pad, Modifier.testTag("remote.inputMode.pad")) { onSelect(RemoteInputMode.Pad) }
        SwitchSegment("Pointer", selected == RemoteInputMode.Pointer, Modifier.testTag("remote.inputMode.pointer")) { onSelect(RemoteInputMode.Pointer) }
    }
}

@Composable
private fun SwitchSegment(label: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val motion = LocalRemoteMotionPolicy.current
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        if (motion.animate) tween(motion.duration(200).coerceIn(1, 200)) else snap(),
        label = "padPointer",
    )
    val enabled = LocalRemoteControlsEnabled.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .remotePressFeedback(source)
            .clip(RoundedCornerShape(50))
            .background(color)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onSelect)
            .semantics {
                role = Role.Button
                this.selected = selected
                if (!enabled) disabled()
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun IdleEmptyState(tvName: String?) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Tv, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Nothing playing on ${tvName ?: "TV"}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FittedButtonPad(onRemoteKey: (String) -> Unit, enabled: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("remote.buttonPad"), contentAlignment = Alignment.Center) {
        val availableW = if (maxWidth == Dp.Infinity) ButtonPadExtent else maxWidth
        val availableH = if (maxHeight == Dp.Infinity) ButtonPadExtent else maxHeight
        val scale = minOf(availableW / ButtonPadExtent, availableH / ButtonPadExtent).coerceIn(0.01f, 1f)
        DpadArea(onRemoteKey, enabled, scale)
    }
}

@Composable
internal fun DpadArea(onRemoteKey: (String) -> Unit, enabled: Boolean, scale: Float = 1f) {
    val s = scale.coerceIn(0.01f, 1f)
    val arrow = 64.dp * s
    val ok = 80.dp * s
    val gap = 8.dp * s
    Box(Modifier.fillMaxSize().remotePadExclusion(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
            ArrowButton("dpad_up", "TV Up", Icons.Default.ArrowUpward, enabled, arrow, onRemoteKey)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                ArrowButton("dpad_left", "TV Left", Icons.Default.ChevronLeft, enabled, arrow, onRemoteKey)
                val haptics = rememberRemoteHaptics()
                val source = remember { MutableInteractionSource() }
                IconButton(
                    onClick = { onRemoteKey("dpad_center"); haptics.perform(RemoteFeedback.PAD_STEP) },
                    enabled = enabled,
                    interactionSource = source,
                    modifier = Modifier.size(ok).remotePressFeedback(source).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                ) { Text("OK", color = MaterialTheme.colorScheme.onPrimaryContainer) }
                ArrowButton("dpad_right", "TV Right", Icons.Default.ChevronRight, enabled, arrow, onRemoteKey)
            }
            ArrowButton("dpad_down", "TV Down", Icons.Default.ArrowDownward, enabled, arrow, onRemoteKey)
        }
    }
}

@Composable
private fun ArrowButton(key: String, label: String, icon: ImageVector, enabled: Boolean, buttonSize: Dp, onRemoteKey: (String) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberRemoteHaptics()
    val callback by rememberUpdatedState(onRemoteKey)
    val step = { callback(key); haptics.perform(RemoteFeedback.PAD_STEP) }
    Box(
        Modifier.size(buttonSize).remotePressFeedback(source).clip(CircleShape)
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
                            while (policy.next()) {
                                step()
                                delay(RemoteRepeatPolicy.CADENCE_MS)
                            }
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
