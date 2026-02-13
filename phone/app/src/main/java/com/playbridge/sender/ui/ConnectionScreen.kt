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
import com.playbridge.sender.connection.NsdHelper
import com.playbridge.sender.model.TvDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    nsdHelper: NsdHelper,
    history: List<TvDevice>,
    onConnect: (TvDevice) -> Unit,
    onRemove: (TvDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val discoveredDevices by nsdHelper.discoveredDevices.collectAsState()
    
    // Start discovery when screen is visible
    DisposableEffect(Unit) {
        nsdHelper.startDiscovery()
        onDispose {
            nsdHelper.stopDiscovery()
        }
    }

    var showPinDialog by remember { mutableStateOf<Pair<String, Int>?>(null) } // IP, Port
    var showManualDialog by remember { mutableStateOf(false) }
    
    // When a device is selected, check history for token
    fun onDeviceSelected(ip: String, port: Int, name: String) {
        // Find if we have a token for this device
        val existing = history.find { it.ip == ip && it.port == port }
        
        if (existing != null && existing.token.isNotEmpty()) {
            // Already have token, connect directly
            onConnect(existing.copy(name = name)) // Use discovered name if available
        } else {
            // New device or token missing/invalid, ask for PIN
            showPinDialog = ip to port
        }
    }

    Scaffold(
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
                        onClick = { onDeviceSelected(device.ip, device.port, device.name) }
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
                        onClick = { onConnect(device) },
                        onRemove = { onRemove(device) }
                    )
                }
            }
        }
    }
    
    // PIN Dialog
    showPinDialog?.let { (ip, port) ->
        PinEntryDialog(
            ip = ip,
            port = port,
            onDismiss = { showPinDialog = null },
            onConfirm = { pin ->
                showPinDialog = null
                onConnect(TvDevice(
                    ip = ip,
                    port = port,
                    token = pin, // Use PIN as initial token
                    name = "TV ($ip)" // Temp name, will update on connect
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
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Enter the 4-digit PIN displayed on the TV at $ip")
                
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4) pin = it.uppercase() },
                    label = { Text("PIN") },
                    singleLine = true,
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
    var port by remember { mutableStateOf("8765") }

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
