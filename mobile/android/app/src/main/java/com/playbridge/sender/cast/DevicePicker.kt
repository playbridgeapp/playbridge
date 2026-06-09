package com.playbridge.sender.cast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.TvDeviceRow
import com.playbridge.sender.ui.buildUnifiedDevices
import com.playbridge.sender.ui.connectKnownOrPair
import org.koin.androidx.compose.koinViewModel

private val ConnectedGreen = Color(0xFF4CAF50)
private val ConnectingOrange = Color(0xFFFF9800)

/**
 * The shared device/destination chip used by the Library top bar, the Library detail destination
 * row, and the Cast sheet. Replaces the three hand-rolled `ChipDropdown` selectors: instead of a
 * dropdown it opens [DeviceConnectionSheet] (a minimal connection screen) on tap.
 *
 * Presentation-only and self-contained — it reads the active [ConnectionViewModel] for its label
 * and status, so callers don't thread device lists/handlers down. Host-specific side effects go
 * through [onPickedThisDevice] / [onPickedDevice] (e.g. the `watch_on_tv` preference).
 *
 * @param showThisDevice include a "This Device" (play on phone) entry — false in the Cast sheet.
 * @param onOpenAllDevices open the full TV Connection screen (manual connect, DLNA, auto-connect).
 */
@Composable
fun DeviceChip(
    onOpenAllDevices: () -> Unit,
    modifier: Modifier = Modifier,
    showThisDevice: Boolean = true,
    themeColor: Color = Color.Unspecified,
    fixedWidth: Dp? = null,
    onPickedThisDevice: (() -> Unit)? = null,
    onPickedDevice: ((TvDevice) -> Unit)? = null
) {
    val viewModel: ConnectionViewModel = koinViewModel()
    val connectionState by viewModel.connectionState.collectAsState()
    val tvDevice by viewModel.tvDevice.collectAsState(initial = null)
    val activeDlnaTarget by viewModel.activeDlnaTarget.collectAsState()

    val isDlna = activeDlnaTarget != null
    val isConnected = isDlna || connectionState is WebSocketClient.ConnectionState.Connected
    val isConnecting = !isDlna && (
        connectionState is WebSocketClient.ConnectionState.Connecting ||
            connectionState is WebSocketClient.ConnectionState.Retrying ||
            connectionState is WebSocketClient.ConnectionState.WaitingForApproval
        )

    val name = activeDlnaTarget?.name ?: tvDevice?.name
    val label = when {
        isConnected -> "Watching on: ${name ?: "TV"}"
        isConnecting -> "Connecting to: ${name ?: "TV"}…"
        else -> "Watching on: This Device"
    }
    val icon = when {
        isDlna -> Icons.Default.Cast
        isConnected || isConnecting -> Icons.Default.Tv
        else -> Icons.Default.Smartphone
    }
    val iconTint = when {
        isConnected -> ConnectedGreen
        isConnecting -> ConnectingOrange
        else -> Color.White.copy(alpha = 0.7f)
    }

    // Mirror ChipDropdown's capsule styling so the chip looks identical to the old trigger.
    val accent = when {
        isConnected -> ConnectedGreen
        isConnecting -> ConnectingOrange
        else -> Color.Unspecified
    }
    val highlighted = accent != Color.Unspecified
    val labelColor = when {
        highlighted -> accent
        themeColor != Color.Unspecified -> Color.White.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.75f)
    }
    val bg = when {
        highlighted -> accent.copy(alpha = 0.15f)
        themeColor != Color.Unspecified -> themeColor.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val borderColor = when {
        highlighted -> accent.copy(alpha = 0.5f)
        themeColor != Color.Unspecified -> themeColor.copy(alpha = 0.4f)
        else -> Color.White.copy(alpha = 0.2f)
    }

    var showPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .then(if (fixedWidth != null) Modifier.width(fixedWidth) else Modifier)
            .clickable { showPicker = true },
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .then(if (fixedWidth != null) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = iconTint)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (fixedWidth != null) Modifier.weight(1f) else Modifier
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = labelColor.copy(alpha = 0.6f)
            )
        }
    }

    if (showPicker) {
        DeviceConnectionSheet(
            onDismiss = { showPicker = false },
            onOpenAllDevices = {
                showPicker = false
                onOpenAllDevices()
            },
            showThisDevice = showThisDevice,
            onPickedThisDevice = onPickedThisDevice,
            onPickedDevice = onPickedDevice
        )
    }
}

/**
 * Bottom-sheet device picker: a minimal version of the full TV Connection screen. Shows the active
 * cast target (with Disconnect), an optional "This Device" entry, the scannable "Your TVs" list
 * (saved + discovered, reusing [TvDeviceRow]), and an "All devices" link to the full screen.
 *
 * Self-sources the activity [ConnectionViewModel] and drives connect/disconnect/DLNA directly;
 * [onPickedThisDevice] / [onPickedDevice] are host hooks for side effects like `watch_on_tv`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectionSheet(
    onDismiss: () -> Unit,
    onOpenAllDevices: () -> Unit,
    showThisDevice: Boolean = true,
    onPickedThisDevice: (() -> Unit)? = null,
    onPickedDevice: ((TvDevice) -> Unit)? = null
) {
    val viewModel: ConnectionViewModel = koinViewModel()
    val discovered by viewModel.discoveredDevices.collectAsState()
    val history by viewModel.deviceHistory.collectAsState(initial = emptyList())
    val connectionState by viewModel.connectionState.collectAsState()
    val tvDevice by viewModel.tvDevice.collectAsState(initial = null)
    val activeDlnaTarget by viewModel.activeDlnaTarget.collectAsState()

    val isConnected = connectionState is WebSocketClient.ConnectionState.Connected
    val isConnecting = connectionState is WebSocketClient.ConnectionState.Connecting ||
        connectionState is WebSocketClient.ConnectionState.WaitingForApproval ||
        connectionState is WebSocketClient.ConnectionState.Retrying
    val onPhone = !isConnected && !isConnecting && activeDlnaTarget == null
    val isScanning = onPhone

    // Discover only while the sheet is open (mirrors ConnectionScreen).
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    val unified = buildUnifiedDevices(discovered, history, tvDevice, isConnected)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Cast to",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Active target card (DLNA / connected TV) or a connecting indicator.
            val dlna = activeDlnaTarget
            when {
                dlna != null -> ActiveDeviceCard(
                    name = dlna.name,
                    subtitle = "${dlna.ip} · cast a video to play here",
                    icon = Icons.Default.Cast,
                    badge = "DLNA",
                    onDisconnect = {
                        viewModel.dlnaStop()
                        viewModel.clearDlnaTarget()
                        onDismiss()
                    }
                )
                isConnected -> {
                    val connected = connectionState as WebSocketClient.ConnectionState.Connected
                    ActiveDeviceCard(
                        name = connected.serverName,
                        subtitle = tvDevice?.let { "${it.ip}:${it.port}" },
                        icon = Icons.Default.Tv,
                        badge = null,
                        onDisconnect = {
                            viewModel.disconnect()
                            onDismiss()
                        }
                    )
                }
                isConnecting -> ConnectingCard(onCancel = { viewModel.disconnect() })
            }

            if (showThisDevice) {
                ThisDeviceRow(
                    selected = onPhone,
                    onClick = {
                        viewModel.disconnect()
                        onPickedThisDevice?.invoke()
                        onDismiss()
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your TVs",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            "Scanning…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (unified.isEmpty()) {
                Text(
                    text = "Looking for TVs on your network…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                unified.forEach { device ->
                    TvDeviceRow(
                        device = device,
                        onClick = {
                            if (device.connectDevice.isDlna) {
                                viewModel.selectDlnaTarget(device.connectDevice)
                            } else {
                                connectKnownOrPair(
                                    viewModel,
                                    history,
                                    device.connectDevice.ip,
                                    device.connectDevice.port,
                                    device.connectDevice.name,
                                    device.connectDevice.uuid
                                )
                            }
                            onPickedDevice?.invoke(device.connectDevice)
                            onDismiss()
                        },
                        onRemove = device.historyEntry?.let { entry ->
                            { viewModel.removeDeviceFromHistory(entry) }
                        }
                    )
                }
            }

            AllDevicesRow(onClick = onOpenAllDevices)
        }
    }
}

@Composable
private fun ActiveDeviceCard(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    badge: String?,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (badge != null) ProtocolBadge(badge)
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            TextButton(onClick = onDisconnect) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConnectingCard(onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Connecting to TV…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ThisDeviceRow(selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text("This Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = ConnectedGreen)
            }
        }
    }
}

@Composable
private fun AllDevicesRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "All devices & manual connect",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ProtocolBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
