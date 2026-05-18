package com.playbridge.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.playbridge.player.model.PairedDevice
import com.playbridge.player.server.WebSocketServer
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PairingScreen(
    ip: String,
    port: Int,
    deviceName: String,
    deviceId: String,
    connectionState: WebSocketServer.ConnectionState,
    connectedCount: Int,
    pendingRequest: WebSocketServer.PairingRequest?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    pairedDevices: List<PairedDevice> = emptyList(),
    onForget: (PairedDevice) -> Unit = {},
    onForgetAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDevicesDialog by remember { mutableStateOf(false) }
    val allowFocusRequester = remember { FocusRequester() }

    LaunchedEffect(pendingRequest) {
        if (pendingRequest != null) {
            try { allowFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    if (showDevicesDialog) {
        DevicesDialog(
            pairedDevices = pairedDevices,
            onForget = onForget,
            onForgetAll = onForgetAll,
            onDismiss = { showDevicesDialog = false }
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Connection status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            when (connectionState) {
                                is WebSocketServer.ConnectionState.Connected -> Color(0xFF00FF88)
                                is WebSocketServer.ConnectionState.Running -> Color(0xFFFFAA00)
                                is WebSocketServer.ConnectionState.Error -> Color(0xFFFF4444)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                )
                Text(
                    text = when (connectionState) {
                        is WebSocketServer.ConnectionState.Connected ->
                            if (connectedCount == 1) "1 device connected" else "$connectedCount devices connected"
                        is WebSocketServer.ConnectionState.Running -> "Ready to Connect"
                        is WebSocketServer.ConnectionState.Starting -> "Starting server…"
                        is WebSocketServer.ConnectionState.Error -> "Error: ${connectionState.message}"
                        is WebSocketServer.ConnectionState.Stopped -> "Server stopped"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (connectionState is WebSocketServer.ConnectionState.Connected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }

            if (pendingRequest != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 64.dp, vertical = 40.dp)
                ) {
                    Text(
                        text = "Allow device to connect?",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = pendingRequest.deviceName,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Button(
                            onClick = onAllow,
                            modifier = Modifier.focusRequester(allowFocusRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                "Allow",
                                fontSize = 22.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                        OutlinedButton(onClick = onDeny) {
                            Text(
                                "Deny",
                                fontSize = 22.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        text = "Request expires in 30 seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(text = "$ip:$port", fontSize = 28.sp, color = Color(0xFF00D9FF))
                    if (deviceId.isNotEmpty()) {
                        Text(
                            text = "ID: $deviceId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = when (connectionState) {
                            is WebSocketServer.ConnectionState.Connected ->
                                "Ready to receive videos!\nUse your phone to send content."
                            else ->
                                "Open PlayBridge on your phone and connect to this device."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (pairedDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { showDevicesDialog = true }) {
                            Text(
                                "Paired Devices (${pairedDevices.size})",
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Devices dialog ───────────────────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DevicesDialog(
    pairedDevices: List<PairedDevice>,
    onForget: (PairedDevice) -> Unit,
    onForgetAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 640.dp)
                .background(
                    MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(32.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Paired Devices",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (pairedDevices.isNotEmpty()) {
                            Button(
                                onClick = { onForgetAll(); onDismiss() },
                                colors = ButtonDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Remove All") }
                        }
                        OutlinedButton(onClick = onDismiss) { Text("Close") }
                    }
                }

                if (pairedDevices.isEmpty()) {
                    Text(
                        text = "No paired devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(pairedDevices, key = { it.id }) { device ->
                            DialogDeviceRow(
                                device = device,
                                onForget = { onForget(device) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DialogDeviceRow(device: PairedDevice, onForget: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Last connected: ${
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(device.lastConnected))
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = device.deviceUUID,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
        Button(
            onClick = onForget,
            colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Forget")
        }
    }
}
