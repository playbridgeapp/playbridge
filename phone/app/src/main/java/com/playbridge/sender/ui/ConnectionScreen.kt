package com.playbridge.sender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.TvDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val history by viewModel.deviceHistory.collectAsState(initial = emptyList())
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

    var showPinDialog by remember { mutableStateOf<Triple<String, Int, String>?>(null) } // IP, Port, UUID
    var pinDialogShowError by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

    // When auth fails, re-open the PIN dialog for the current device so the user knows
    // to try again (covers wrong PIN and TV app reinstall scenarios).
    val tvDeviceSnapshot by viewModel.tvDevice.collectAsState(initial = null)
    LaunchedEffect(connectionState) {
        if (connectionState is WebSocketClient.ConnectionState.AuthFailed) {
            val device = tvDeviceSnapshot
            if (device != null) {
                // Re-signal the TV so its PairingScreen comes back up (it may have navigated
                // away after the failed attempt closed the WebSocket connection).
                viewModel.requestPairing(device.ip, device.port)
                pinDialogShowError = true
                showPinDialog = Triple(device.ip, device.port, device.uuid)
            }
        }
    }
    
    // When a device is selected, check history for token
    fun onDeviceSelected(ip: String, port: Int, name: String, uuid: String = "") {
        // Find if we have a token for this device by uuid or ip/port
        val existing = if (uuid.isNotEmpty()) {
            history.find { it.uuid == uuid } ?: history.find { it.ip == ip && it.port == port }
        } else {
            history.find { it.ip == ip && it.port == port }
        }
        
        if (existing != null && existing.token.isNotEmpty()) {
            // Already have token, connect directly. Update IP and name if changed.
            viewModel.connect(existing.copy(name = name, ip = ip, port = port, uuid = if (uuid.isNotEmpty()) uuid else existing.uuid))
        } else {
            viewModel.requestPairing(ip, port)
            showPinDialog = Triple(ip, port, uuid)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV Connection") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, "Menu")
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
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
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
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else if (connectionState is WebSocketClient.ConnectionState.Connecting) {
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
                                Text("Connecting to TV...", style = MaterialTheme.typography.bodyMedium)
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

            if (discoveredDevices.isEmpty()) {
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
                items(discoveredDevices) { device ->
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
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // History Section
            if (history.isNotEmpty()) {
                item {
                    Text(
                        text = "Recent Connections",
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
    
    // PIN Dialog
    showPinDialog?.let { (ip, port, uuid) ->
        PinEntryDialog(
            ip = ip,
            port = port,
            uuid = uuid,
            showError = pinDialogShowError,
            onDismiss = {
                showPinDialog = null
                pinDialogShowError = false
            },
            onConfirm = { pin ->
                showPinDialog = null
                pinDialogShowError = false
                viewModel.connect(TvDevice(
                    ip = ip,
                    port = port,
                    token = pin,
                    name = "TV ($ip)",
                    uuid = uuid
                ))
            }
        )
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
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun PinEntryDialog(
    ip: String,
    port: Int,
    uuid: String = "",
    showError: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (showError) {
                    Text(
                        text = "Incorrect PIN — please check the PIN shown on the TV and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "Enter the 4-digit PIN displayed on the TV at $ip",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (uuid.isNotEmpty()) {
                    Text(
                        text = "Device ID: $uuid",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4) pin = it.uppercase() },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = showError,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pin) },
                enabled = pin.length >= 4
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

@Composable
fun ManualConnectionDialog(
    onDismiss: () -> Unit,
    onConnect: (String, Int) -> Unit
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(com.playbridge.protocol.Config.DEFAULT_PORT.toString()) }

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
