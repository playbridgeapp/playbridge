package com.playbridge.sender.cast

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.vector.ImageVector
import com.playbridge.sender.cast.remote.RemoteInputMode
import com.playbridge.sender.cast.remote.RemoteViewport
import com.playbridge.sender.cast.remote.resolveRemoteLayout
import com.playbridge.sender.cast.remote.stage1PolicyState

internal enum class RemoteMode(val label: String, val icon: ImageVector) {
    CONTEXT("Context", Icons.Default.Dashboard),
    DPAD("D-Pad", Icons.Default.Gamepad),
    TOUCHPAD("Touchpad", Icons.Default.TouchApp),
    KEYBOARD("Keyboard", Icons.Default.Keyboard)
}

internal enum class RemoteContext { PLAYER, BROWSER, IDLE }

internal fun availableRemoteModes(
    remoteContext: RemoteContext,
    externalMode: Boolean,
    supportsRemote: Boolean,
    supportsTouchpad: Boolean = supportsRemote,
): List<RemoteMode> = resolveRemoteLayout(
    stage1PolicyState(remoteContext.name.lowercase(), externalMode, supportsRemote, supportsTouchpad),
    RemoteViewport(),
).modes.map { it.screenMode() }

internal fun RemoteInputMode.screenMode(): RemoteMode = when (this) {
    RemoteInputMode.Context -> RemoteMode.CONTEXT; RemoteInputMode.Pad -> RemoteMode.DPAD
    RemoteInputMode.Pointer -> RemoteMode.TOUCHPAD; RemoteInputMode.Keyboard -> RemoteMode.KEYBOARD
}
internal fun RemoteMode.inputMode(): RemoteInputMode = when (this) {
    RemoteMode.CONTEXT -> RemoteInputMode.Context; RemoteMode.DPAD -> RemoteInputMode.Pad
    RemoteMode.TOUCHPAD -> RemoteInputMode.Pointer; RemoteMode.KEYBOARD -> RemoteInputMode.Keyboard
}
