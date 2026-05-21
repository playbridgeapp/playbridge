package com.playbridge.sender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.playbridge.sender.R
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Filter out devices that are already in Known Devices to avoid duplicates
    val knownUuids = history.map { it.uuid }.filter { it.isNotEmpty() }.toSet()
    val knownIpPorts = history.map { "${it.ip}:${it.port}" }.toSet()
    val newDiscoveredDevices = discoveredDevices.filter { device ->
        if (device.uuid.isNotEmpty()) device.uuid !in knownUuids
        else "${device.ip}:${device.port}" !in knownIpPorts
    }
    val connectionState by viewModel.connectionState.collectAsState()
    val autoConnectEnabled by viewModel.autoConnectEnabled.collectAsState()
    val tvDevice by viewModel.tvDevice.collectAsState(initial = null)
    
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
            else -> Unit
        }
    }

    // When a device is selected, check history for a saved token.
    fun onDeviceSelected(ip: String, port: Int, name: String, uuid: String = "") {
        val existing = if (uuid.isNotEmpty()) {
            history.find { it.uuid == uuid } ?: history.find { it.ip == ip && it.port == port }
        } else {
            history.find { it.ip == ip && it.port == port }
        }

        if (existing != null && existing.token.isNotEmpty()) {
            // Saved token — reconnect directly (no pairing prompt).
            viewModel.connect(existing.copy(name = name, ip = ip, port = port, uuid = if (uuid.isNotEmpty()) uuid else existing.uuid))
        } else {
            // No token — connect with empty token; WebSocketClient will send pairing_request.
            viewModel.connect(TvDevice(ip = ip, port = port, token = "", name = name, uuid = uuid))
        }
    }

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

            // Connected TV Section
            if (connectionState is WebSocketClient.ConnectionState.Connected) {
                val serverName = (connectionState as WebSocketClient.ConnectionState.Connected).serverName
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
                                            text = "${tvDevice?.ip}:${tvDevice?.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
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

            // Discovered Devices Section
            item {
                Text(
                    text = "Discovered Devices",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            if (newDiscoveredDevices.isEmpty()) {
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Searching for TVs...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            } else {
                items(newDiscoveredDevices) { device ->
                    DeviceItem(
                        name = device.name,
                        ip = device.ip,
                        port = device.port,
                        icon = Icons.Default.Tv,
                        uuid = device.uuid,
                        onClick = { onDeviceSelected(device.ip, device.port, device.name, device.uuid) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Spacer(modifier = Modifier.height(8.dp))
            }

            // History Section
            if (history.isNotEmpty()) {
                item {
                    Text(
                        text = "Known Devices",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                items(history) { device ->
                    DeviceItem(
                        name = device.name,
                        ip = device.ip,
                        port = device.port,
                        icon = Icons.Default.History,
                        uuid = device.uuid,
                        onClick = { viewModel.connect(device) },
                        onRemove = { viewModel.removeDeviceFromHistory(device) }
                    )
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

@Composable
fun DeviceItem(
    name: String,
    ip: String,
    port: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    uuid: String = "",
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
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
            horizontalArrangement = Arrangement.SpaceBetween // Use SpaceBetween to push delete button to end
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f) // Take available space
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                
                Column {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$ip:$port",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uuid.isNotEmpty()) {
                        Text(
                            text = "ID: $uuid",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 10.sp
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
