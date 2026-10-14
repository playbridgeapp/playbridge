package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteCallbackActions
import com.playbridge.sender.cast.remote.RemoteFeedback
import com.playbridge.sender.cast.remote.RemoteLayoutSpec
import com.playbridge.sender.cast.remote.RemoteNavAvailability
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.rememberRemoteHaptics
import com.playbridge.sender.cast.remote.volumeLabel
import com.playbridge.sender.cast.remote.volumeReportLabel
import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.Support

@Composable
internal fun RemoteNavBar(nav: RemoteNavAvailability, onRemoteKey: (String) -> Unit, onKeys: () -> Unit, volumeTitle: String = "TV volume", mute: Boolean = false, enter: Boolean = false, delete: Boolean = false, muted: Boolean? = null) {
    if (!nav.back && !nav.home && !nav.keys && !nav.volume && !mute && !enter && !delete) return
    val haptics = rememberRemoteHaptics()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f), shape = CircleShape) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (nav.back) RemoteButton(Icons.AutoMirrored.Filled.Undo, "TV Back", true) { onRemoteKey("back") }
            if (nav.home) RemoteButton(Icons.Default.Home, "TV Home", true) { onRemoteKey("home") }
            if (nav.keys) RemoteButton(Icons.Default.Keyboard, "Keys", true, onKeys)
            if (enter) RemoteButton(Icons.AutoMirrored.Filled.KeyboardReturn, "TV Enter", true) { onRemoteKey("key_enter") }
            if (delete) RemoteButton(Icons.AutoMirrored.Filled.Backspace, "TV Delete", true) { onRemoteKey("key_delete") }
            if (nav.volume) {
                RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, "$volumeTitle down", true) { onRemoteKey("volume_down"); haptics.perform(RemoteFeedback.VOLUME) }
                RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, "$volumeTitle up", true) { onRemoteKey("volume_up"); haptics.perform(RemoteFeedback.VOLUME) }
            }
            if (mute) RemoteButton(Icons.Default.VolumeOff, volumeTitle + when (muted) { true -> " unmute"; false -> " mute"; null -> " toggle mute" }, true) { onRemoteKey("mute") }
        }
    }
}

@Composable
internal fun RemoteNavBar(state: RemoteUiState, layout: RemoteLayoutSpec, dispatch: (RemoteAction) -> Unit) {
    val nav = RemoteNavAvailability(layout.nav.back.enabled, layout.nav.home.enabled, layout.nav.keys.enabled, layout.nav.volume.enabled)
    val nativeVolume = state.nativePolicy != null
    if (!nativeVolume && !nav.back && !nav.home && !nav.keys && !nav.volume) return
    val mute = state.nativePolicy?.volume?.operations?.contains("mute_toggle") == true && state.connection is com.playbridge.sender.cast.remote.RemoteConnection.Connected && RemoteKey.Mute in state.capabilities.keys
    val absolute = nativeVolume && state.capabilities.volume.absolute == Support.Supported
    val enter = nativeVolume && state.connection is com.playbridge.sender.cast.remote.RemoteConnection.Connected && RemoteKey.Enter in state.capabilities.keys && !nav.keys
    val delete = state.nativePolicy?.operations?.text?.contains("delete") == true && state.connection is com.playbridge.sender.cast.remote.RemoteConnection.Connected && state.capabilities.text.delete == Support.Supported && !nav.keys
    val level = state.capabilities.volume.level
    val maximum = state.capabilities.volume.maximum
    val volumeTitle = state.volumeLabel()
    val reportTitle = state.volumeReportLabel()
    if (!nav.back && !nav.home && !nav.keys && !nav.volume && !mute && !enter && !delete && !absolute && level == null && state.capabilities.volume.muted == null) return
    Column(Modifier.testTag("remote.nav"), horizontalAlignment = Alignment.CenterHorizontally) {
        if ((nav.volume || nativeVolume) && level != null && maximum != null && maximum > 0) {
            val value = if (state.capabilities.volume.muted == true) "$level/$maximum muted" else "$level/$maximum"
            val label = if (nativeVolume) "$reportTitle $value" else value
            Text(
                label,
                modifier = Modifier
                    .testTag("remote.volumeLevel")
                    .semantics { contentDescription = "$reportTitle $level of $maximum" },
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (nativeVolume && (volumeTitle == "App volume" || state.capabilities.volume.muted != null)) {
            Text(reportTitle + when (state.capabilities.volume.muted) { true -> " · Muted"; false -> " · Unmuted"; null -> "" })
        }
        if (absolute) {
            val scopeTitle = if (state.nativePolicy?.volume?.scope == "app") "App volume" else "TV volume"
            val receiverLevel = state.nativePolicy?.receiverVolume?.level?.takeIf { state.nativePolicy.reportedVolumeScope == state.nativePolicy.volume?.scope }
            var draft by remember(state.sessionId) { mutableFloatStateOf((receiverLevel ?: 50) / 100f) }
            var dragging by remember(state.sessionId) { mutableStateOf(false) }
            LaunchedEffect(receiverLevel) { if (!dragging && receiverLevel != null) draft = receiverLevel / 100f }
            Text("Set $scopeTitle")
            Slider(value = draft, valueRange = 0f..1f,
                enabled = state.connection is com.playbridge.sender.cast.remote.RemoteConnection.Connected,
                onValueChange = { draft = it; dragging = true },
                onValueChangeFinished = { dispatch(RemoteAction.SetVolume(kotlin.math.round(draft * 100).toInt())); dragging = false },
                modifier = Modifier.testTag("remote.absoluteVolume").semantics { contentDescription = "Set $scopeTitle" })
        }
        RemoteNavBar(
            nav,
            onRemoteKey = { dispatch(RemoteCallbackActions.key(it)) },
            onKeys = { dispatch(RemoteAction.OpenSheet(RemoteSheet.Keyboard)) },
            volumeTitle = volumeTitle, mute = mute, enter = enter, delete = delete, muted = state.capabilities.volume.muted,
        )
    }
}
