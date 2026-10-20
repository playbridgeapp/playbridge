package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.fullControlSupports

import com.playbridge.sender.cast.remote.fullControlReady

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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.playbridge.sender.cast.remote.VolumeCapabilities
import com.playbridge.sender.cast.remote.VolumeScope
import com.playbridge.sender.cast.remote.Support
import com.playbridge.sender.cast.remote.RemoteVolumePill
import com.playbridge.sender.cast.remote.remoteVolumePill

@Composable
internal fun RemoteNavBar(nav: RemoteNavAvailability, onRemoteKey: (String) -> Unit, onKeys: () -> Unit, volumeTitle: String = "TV volume", mute: Boolean = false, enter: Boolean = false, delete: Boolean = false, muted: Boolean? = null, volumePill: RemoteVolumePill? = null, onVolumePill: () -> Unit = {}, onStop: (() -> Unit)? = null) {
    if (!nav.back && !nav.home && !nav.keys && !nav.volume && !mute && !enter && !delete && onStop == null) return
    val haptics = rememberRemoteHaptics()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f), shape = CircleShape) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (nav.back) RemoteButton(Icons.AutoMirrored.Filled.Undo, "TV Back", true) { onRemoteKey("back") }
            if (nav.home) RemoteButton(Icons.Default.Home, "TV Home", true) { onRemoteKey("home") }
            if (nav.keys) RemoteButton(Icons.Default.Keyboard, "Keys", true, onKeys)
            if (enter) RemoteButton(Icons.AutoMirrored.Filled.KeyboardReturn, "TV Enter", true) { onRemoteKey("key_enter") }
            if (delete) RemoteButton(Icons.AutoMirrored.Filled.Backspace, "TV Delete", true) { onRemoteKey("key_delete") }
            // −, +, and mute collapse into one pill; its sheet holds them.
            if (nav.volume && volumePill != null) {
                RemoteVolumePillButton(volumePill, volumeTitle, onVolumePill)
            } else if (nav.volume) {
                RemoteButton(Icons.AutoMirrored.Filled.VolumeDown, "$volumeTitle down", true) { onRemoteKey("volume_down"); haptics.perform(RemoteFeedback.VOLUME) }
                RemoteButton(Icons.AutoMirrored.Filled.VolumeUp, "$volumeTitle up", true) { onRemoteKey("volume_up"); haptics.perform(RemoteFeedback.VOLUME) }
            }
            if (mute && volumePill == null) RemoteButton(Icons.Default.VolumeOff, volumeTitle + when (muted) { true -> " unmute"; false -> " mute"; null -> " toggle mute" }, true) { onRemoteKey("mute") }
            // Last, beside volume and away from Back, so a hurried Back tap can't end playback.
            if (onStop != null) RemoteButton(Icons.Default.Stop, "Stop playback", true, StopRed, onStop)
        }
    }
}

// The theme's dark-mode error colour is a pastel pink; Stop wants an unmistakable red in both themes.
private val StopRed = Color(0xFFE53935)

internal data class RemoteVolumeLabel(val text: String, val contentDescription: String)

internal fun remoteVolumeLabel(
    title: String,
    volume: VolumeCapabilities,
    nativeVolume: Boolean,
    legacyVolumeEnabled: Boolean,
): RemoteVolumeLabel? {
    val hasLevel = (nativeVolume || legacyVolumeEnabled) && volume.level != null &&
        volume.maximum != null && volume.maximum > 0
    val levelText = if (hasLevel) "${volume.level}/${volume.maximum}" else null
    // Keep the legacy level-only display; use the new separator for its muted suffix.
    val muteText = if (nativeVolume || (hasLevel && volume.muted == true)) {
        volume.muted?.let { if (it) "Muted" else "Unmuted" }
    } else null
    if (levelText == null && muteText == null) return null
    val scoped = nativeVolume || volume.scope in setOf(VolumeScope.Player, VolumeScope.Renderer, VolumeScope.ReceiverDevice)
    val base = if (scoped) title + (levelText?.let { " $it" } ?: "") else levelText.orEmpty()
    val text = base + (muteText?.let { " · $it" } ?: "")
    val description = title + (if (hasLevel) " ${volume.level} of ${volume.maximum}" else "") +
        (volume.muted?.let { if (it) ", muted" else ", unmuted" } ?: "")
    return RemoteVolumeLabel(text, description)
}

@Composable
internal fun RemoteNavBar(state: RemoteUiState, layout: RemoteLayoutSpec, dispatch: (RemoteAction) -> Unit) {
    val nav = RemoteNavAvailability(layout.nav.back.enabled, layout.nav.home.enabled, layout.nav.keys.enabled, layout.nav.volume.enabled)
    val nativeVolume = state.nativePolicy != null || state.fullControlReady
    if (!nativeVolume && !nav.back && !nav.home && !nav.keys && !nav.volume) return
    val mute = state.fullControlSupports(RemoteAction.Key(RemoteKey.Mute)) ||
        (state.nativePolicy?.volume?.operations?.contains("mute_toggle") == true && state.connection is com.playbridge.sender.cast.remote.RemoteConnection.Connected && RemoteKey.Mute in state.capabilities.keys)
    val enter = nativeVolume && state.connection is com.playbridge.sender.cast.remote.RemoteConnection.Connected && RemoteKey.Enter in state.capabilities.keys && !nav.keys
    val delete = state.nativePolicy?.operations?.text?.contains("delete") == true && state.connection is com.playbridge.sender.cast.remote.RemoteConnection.Connected && state.capabilities.text.delete == Support.Supported && !nav.keys
    val volumeTitle = state.volumeLabel()
    val volume = state.capabilities.volume
    val pill = if (!nav.volume) null else remoteVolumePill(
        absolute = volume.absolute == Support.Supported,
        wholeTvControl = state.fullControl?.connected == true,
        appFallbackOnSystemRoute = state.nativePolicy?.reportedVolumeScope == "app" &&
            state.receiverProtocol.descriptor?.volume?.scope == "system",
        percent = volume.reportedPercent,
        muted = volume.muted,
    )
    // The pill replaces the text level whenever the bar has volume controls.
    val label = if (pill != null) null else remoteVolumeLabel(state.volumeReportLabel(), volume, nativeVolume, nav.volume)
    var showVolumeSheet by rememberSaveable { mutableStateOf(false) }
    if (showVolumeSheet && pill != null) {
        RemoteVolumeSheet(
            title = state.volumeReportLabel(),
            pill = pill,
            canMute = mute,
            onSet = { dispatch(RemoteAction.SetVolume(it)) },
            onStep = { up -> dispatch(RemoteAction.AdjustVolume(if (up) 1 else -1)) },
            onMute = { dispatch(RemoteCallbackActions.key("mute")) },
            onDismiss = { showVolumeSheet = false },
        )
    }
    // Stop lives here (not in More) while something is playing.
    val stop = state.playback != null && state.context == com.playbridge.sender.cast.remote.RemoteContext.Player &&
        (state.nativePolicy == null || state.capabilities.transport.stop == Support.Supported)
    if (!nav.back && !nav.home && !nav.keys && !nav.volume && !mute && !enter && !delete && label == null && !stop) return
    Column(Modifier.testTag("remote.nav"), horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            Text(
                label.text,
                modifier = Modifier
                    .testTag("remote.volumeLevel")
                    .semantics { contentDescription = label.contentDescription },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        RemoteNavBar(
            nav,
            onRemoteKey = { dispatch(RemoteCallbackActions.key(it)) },
            onKeys = { dispatch(RemoteAction.OpenSheet(RemoteSheet.Keyboard)) },
            volumeTitle = volumeTitle, mute = mute, enter = enter, delete = delete, muted = state.capabilities.volume.muted,
            volumePill = pill, onVolumePill = { showVolumeSheet = true },
            onStop = if (stop) ({ dispatch(RemoteAction.StopPlayback) }) else null,
        )
    }
}
