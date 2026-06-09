package com.playbridge.sender.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.playbridge.sender.R
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.TvDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemoteClick: (() -> Unit)? = null
) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val history by viewModel.deviceHistory.collectAsState(initial = emptyList())

    val connectionState by viewModel.connectionState.collectAsState()
    val autoConnectEnabled by viewModel.autoConnectEnabled.collectAsState()
    val tvDevice by viewModel.tvDevice.collectAsState(initial = null)
    val activeDlnaTarget by viewModel.activeDlnaTarget.collectAsState()

    // Build a single "Your TVs" list: saved devices annotated with live online
    // status, plus any freshly-discovered TVs that aren't saved yet. This replaces
    // the old split Discovered/Known sections, whose dedup left "Discovered" stuck
    // on an endless "Searching…" spinner once every TV on the network was saved.
    val isConnected = connectionState is WebSocketClient.ConnectionState.Connected
    val isConnecting = connectionState is WebSocketClient.ConnectionState.Connecting ||
        connectionState is WebSocketClient.ConnectionState.WaitingForApproval
    val isScanning = !isConnected && !isConnecting

    val unifiedDevices = buildUnifiedDevices(discoveredDevices, history, tvDevice, isConnected)
    
    // Start discovery when screen is visible
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose {
            viewModel.stopDiscovery()
        }
    }

    var showManualDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Show a snackbar when pairing is denied or a stale token is rejected.
    LaunchedEffect(connectionState) {
        when (val state = connectionState) {
            is WebSocketClient.ConnectionState.PairingDenied ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("${state.serverName} denied the connection")
                }
            is WebSocketClient.ConnectionState.AuthFailed ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Connection lost — tap the TV to reconnect")
                }
            is WebSocketClient.ConnectionState.PinMismatch ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        "Security warning: ${state.serverName}'s certificate changed. Forget the device and re-pair."
                    )
                }
            else -> Unit
        }
    }

    // When a device is selected, check history for a saved token (shared with the device-picker sheet).
    fun onDeviceSelected(ip: String, port: Int, name: String, uuid: String = "") =
        connectKnownOrPair(viewModel, history, ip, port, name, uuid)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV Connection") },
                navigationIcon = {
                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.semantics {
                            contentDescription = "Menu"
                            role = androidx.compose.ui.semantics.Role.Button
                        }
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_playbridge_logo),
                            contentDescription = "PlayBridge",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    if (onRemoteClick != null) {
                        IconButton(onClick = onRemoteClick) {
                            Icon(Icons.Default.Gamepad, contentDescription = "Remote Control", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showManualDialog = true },
                icon = { Icon(Icons.Default.Add, "Manual Connect") },
                text = { Text("Manual Connect") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Active DLNA cast target (mutually exclusive with a native connection).
            val dlnaTarget = activeDlnaTarget
            if (dlnaTarget != null) {
                item {
                    Text(
                        text = "Casting via DLNA",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = dlnaTarget.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${dlnaTarget.ip} · cast a video to play here",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.dlnaStop()
                                        viewModel.clearDlnaTarget()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Disconnect")
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // Connected TV Section
            if (connectionState is WebSocketClient.ConnectionState.Connected) {
                val connected = connectionState as WebSocketClient.ConnectionState.Connected
                val serverName = connected.serverName
                item {
                    Text(
                        text = "Connected TV",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = serverName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    if (tvDevice != null) {
                                        Text(
                                            text = "${tvDevice?.ip}:${if (connected.secure) (tvDevice?.wssPort ?: tvDevice?.port) else tvDevice?.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (connected.secure) Icons.Default.Lock else Icons.Default.LockOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = if (connected.secure) Color(0xFF4CAF50) else Color(0xFFFFA000)
                                        )
                                        Text(
                                            text = if (connected.secure) "Secure (wss)" else "Not secure (ws)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (connected.secure) Color(0xFF4CAF50) else Color(0xFFFFA000)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = autoConnectEnabled,
                                        onCheckedChange = { viewModel.setAutoConnectEnabled(it) }
                                    )
                                    Text("Auto-connect to this TV", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }

                                Button(
                                    onClick = { viewModel.disconnect() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Disconnect")
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else if (connectionState is WebSocketClient.ConnectionState.Connecting ||
                       connectionState is WebSocketClient.ConnectionState.WaitingForApproval) {
                item {
                    val isWaiting = connectionState is WebSocketClient.ConnectionState.WaitingForApproval
                    val serverName = (connectionState as? WebSocketClient.ConnectionState.WaitingForApproval)?.serverName

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(
                                text = if (isWaiting && serverName != null)
                                    "Waiting for $serverName to approve…"
                                else
                                    "Connecting to TV…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (isWaiting) {
                                TextButton(onClick = { viewModel.disconnect() }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            // Your TVs — unified list of saved + currently-discovered devices.
            if (unifiedDevices.isNotEmpty() || isScanning) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                }

                if (unifiedDevices.isEmpty()) {
                    // First run: nothing saved and nothing discovered on the network yet.
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Looking for TVs on your network…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(unifiedDevices) { device ->
                        TvDeviceRow(
                            device = device,
                            onClick = {
                                if (device.connectDevice.isDlna) {
                                    viewModel.selectDlnaTarget(device.connectDevice)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Selected ${device.connectDevice.name} — cast a video to play here"
                                        )
                                    }
                                } else {
                                    onDeviceSelected(
                                        device.connectDevice.ip,
                                        device.connectDevice.port,
                                        device.connectDevice.name,
                                        device.connectDevice.uuid
                                    )
                                }
                            },
                            onRemove = device.historyEntry?.let { entry ->
                                { viewModel.removeDeviceFromHistory(entry) }
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Manual Dialog
    if (showManualDialog) {
        ManualConnectionDialog(
            onDismiss = { showManualDialog = false },
            onConnect = { ip, port ->
                showManualDialog = false
                // Check history first
                onDeviceSelected(ip, port, "Manual TV")
            }
        )
    }
}

/**
 * A device in the unified "Your TVs" list: the device to connect with, whether it's
 * currently reachable on the network, and (if saved) its history entry + last-connected
 * time so the row can show "Online" vs. "Last seen …".
 */
data class UnifiedDevice(
    val connectDevice: TvDevice,
    val historyEntry: TvDevice?,
    val isOnline: Boolean,
    val lastConnected: Long?
) {
    val isKnown: Boolean get() = historyEntry != null
}

/**
 * Merge saved + freshly-discovered TVs into a single "Your TVs" list, annotated with live online
 * status. Shared by [ConnectionScreen] and the device-picker sheet so both show identical results.
 * Saved entries adopt live mDNS ip/port when online (a saved address can go stale); the
 * currently-connected device is dropped (it has its own card above the list).
 */
fun buildUnifiedDevices(
    discovered: List<TvDevice>,
    history: List<TvDevice>,
    connectedDevice: TvDevice?,
    isConnected: Boolean
): List<UnifiedDevice> {
    fun liveMatch(device: TvDevice): TvDevice? =
        if (device.uuid.isNotEmpty()) discovered.find { it.uuid == device.uuid }
        else discovered.find { it.ip == device.ip && it.port == device.port }

    val knownUuids = history.mapNotNull { it.uuid.takeIf(String::isNotEmpty) }.toSet()
    val knownIpPorts = history.map { "${it.ip}:${it.port}" }.toSet()

    val knownDevices = history.map { saved ->
        val live = liveMatch(saved)
        UnifiedDevice(
            connectDevice = if (live != null)
                saved.copy(ip = live.ip, port = live.port, wssPort = live.wssPort ?: saved.wssPort)
            else saved,
            historyEntry = saved,
            isOnline = live != null,
            lastConnected = saved.lastConnected
        )
    }
    val newDevices = discovered
        .filter { d -> if (d.uuid.isNotEmpty()) d.uuid !in knownUuids else "${d.ip}:${d.port}" !in knownIpPorts }
        .map { UnifiedDevice(connectDevice = it, historyEntry = null, isOnline = true, lastConnected = null) }

    return (newDevices + knownDevices)
        .filterNot { u ->
            isConnected && connectedDevice?.let { c ->
                (u.connectDevice.uuid.isNotEmpty() && u.connectDevice.uuid == c.uuid) ||
                    (u.connectDevice.ip == c.ip && u.connectDevice.port == c.port)
            } == true
        }
        .sortedWith(
            compareByDescending<UnifiedDevice> { it.isOnline }
                // Newly-discovered (no lastConnected) sort to the top of the online group.
                .thenByDescending { it.lastConnected ?: Long.MAX_VALUE }
        )
}

/**
 * Connect to a TV, reusing a saved pairing token when we have one (silent reconnect) and otherwise
 * connecting with an empty token so [WebSocketClient] sends a pairing request. Shared by
 * [ConnectionScreen] and the device-picker sheet.
 */
fun connectKnownOrPair(
    viewModel: ConnectionViewModel,
    history: List<TvDevice>,
    ip: String,
    port: Int,
    name: String,
    uuid: String = ""
) {
    val existing = if (uuid.isNotEmpty()) {
        history.find { it.uuid == uuid } ?: history.find { it.ip == ip && it.port == port }
    } else {
        history.find { it.ip == ip && it.port == port }
    }
    if (existing != null && existing.token.isNotEmpty()) {
        viewModel.connect(existing.copy(name = name, ip = ip, port = port, uuid = if (uuid.isNotEmpty()) uuid else existing.uuid))
    } else {
        viewModel.connect(TvDevice(ip = ip, port = port, token = "", name = name, uuid = uuid))
    }
}

private fun formatLastSeen(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> "Last seen just now"
        diff < 3_600_000L -> "Last seen ${diff / 60_000L}m ago"
        diff < 86_400_000L -> "Last seen ${diff / 3_600_000L}h ago"
        diff < 7 * 86_400_000L -> "Last seen ${diff / 86_400_000L}d ago"
        else -> "Saved"
    }
}

@Composable
fun TvDeviceRow(
    device: UnifiedDevice,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val onlineColor = Color(0xFF4CAF50)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (device.connectDevice.isDlna) Icons.Default.Cast else Icons.Default.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = device.connectDevice.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (device.connectDevice.isDlna) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "DLNA",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${device.connectDevice.ip}:${device.connectDevice.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        val dotColor = if (device.isOnline) onlineColor
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        val statusText = when {
                            device.isOnline && !device.isKnown -> "Online · New"
                            device.isOnline -> "Online"
                            device.lastConnected != null -> formatLastSeen(device.lastConnected)
                            else -> "Saved"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (device.isOnline) onlineColor
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.semantics {
                        contentDescription = "Remove"
                        role = androidx.compose.ui.semantics.Role.Button
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun ManualConnectionDialog(
    onDismiss: () -> Unit,
    onConnect: (String, Int) -> Unit
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(com.playbridge.shared.protocol.Config.DEFAULT_PORT.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP Address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (it.all { char -> char.isDigit() }) port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portInt = port.toIntOrNull()
                    if (ip.isNotEmpty() && portInt != null) {
                        onConnect(ip, portInt)
                    }
                },
                enabled = ip.isNotEmpty() && port.isNotEmpty()
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
