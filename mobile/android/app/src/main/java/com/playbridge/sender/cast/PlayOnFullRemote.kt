package com.playbridge.sender.cast

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.playbridge.sender.cast.atv.AtvPairingController
import com.playbridge.sender.cast.atv.AtvPairingPhase
import com.playbridge.sender.cast.remote.TvRemoteChannel
import com.playbridge.sender.cast.remote.ui.AtvPairingContent
import com.playbridge.sender.model.TvDevice
import org.koin.compose.koinInject

/** Reuses the TV remote's pairing controller and code UI inside the destination sheet. */
@Composable
internal fun PlayOnFullRemote(device: TvDevice, onCancel: () -> Unit, onPaired: () -> Unit) {
    val controller: AtvPairingController = koinInject()
    val channel: TvRemoteChannel = koinInject()
    val ui by controller.pairing.collectAsState()
    val control by channel.state.collectAsState()
    val remoteAlreadyVisible = LocalPlayOnRemoteVisible.current
    val available = control.matchedEndpoint == device.endpointKey && control.fullControlAvailable
    DisposableEffect(channel, controller, remoteAlreadyVisible) {
        if (!remoteAlreadyVisible) channel.setRemoteVisible(true)
        onDispose {
            controller.cancel()
            if (!remoteAlreadyVisible) channel.setRemoteVisible(false)
        }
    }
    LaunchedEffect(available, ui.phase) {
        if (available && ui.phase == AtvPairingPhase.Idle) controller.begin()
        if (ui.phase == AtvPairingPhase.Paired) onPaired()
    }
    if (available) AtvPairingContent(ui, controller::submitCode, onCancel, controller::begin, embedded = true)
    else {
        CircularProgressIndicator()
        Text("Preparing the TV remote for ${device.name}…")
        TextButton(onClick = onCancel) { Text("Back") }
    }
}
