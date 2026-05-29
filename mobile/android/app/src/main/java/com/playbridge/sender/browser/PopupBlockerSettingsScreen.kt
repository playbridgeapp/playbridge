package com.playbridge.sender.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PopupBlockerSettingsScreen(onBack: () -> Unit) {
    val settingsRepository: com.playbridge.sender.data.settings.SettingsRepository = koinInject()
    
    // Observe global settings and exceptions lists
    val blockPopups by settingsRepository.blockPopups.collectAsState(initial = true)
    val whitelistSet by settingsRepository.popupWhitelist.collectAsState(initial = emptySet())
    val blacklistSet by settingsRepository.popupBlacklist.collectAsState(initial = emptySet())
    
    val whitelist = remember(whitelistSet) { whitelistSet.toSortedSet().toList() }
    val blacklist = remember(blacklistSet) { blacklistSet.toSortedSet().toList() }
    
    val coroutineScope = rememberCoroutineScope()
    
    // Add Dialog states
    var showAddAllowedDialog by remember { mutableStateOf(false) }
    var showAddBlockedDialog by remember { mutableStateOf(false) }
    var newHost by remember { mutableStateOf("") }

    fun normalizeAndAddHost(hostInput: String, isAllowedList: Boolean) {
        val host = hostInput.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .trim()
        if (host.isNotBlank()) {
            coroutineScope.launch {
                if (isAllowedList) {
                    settingsRepository.addPopupWhitelist(host)
                    settingsRepository.removePopupBlacklist(host)
                } else {
                    settingsRepository.addPopupBlacklist(host)
                    settingsRepository.removePopupWhitelist(host)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Popup Blocker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Global Blocker Toggle Row
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Block Popups", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Block popup windows by default",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = blockPopups,
                        onCheckedChange = { checked ->
                            coroutineScope.launch {
                                settingsRepository.setBlockPopups(checked)
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
            }

            // 2. Allowed Sites (Whitelist Exception List)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Allowed Sites",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { showAddAllowedDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add allowed site exception",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (whitelist.isEmpty()) {
                item {
                    Text(
                        "No exceptions. Tap + to always allow popups on a site.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            } else {
                items(whitelist, key = { "allowed_$it" }) { host ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            host,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            coroutineScope.launch {
                                settingsRepository.removePopupWhitelist(host)
                            }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove $host",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
            }

            // 3. Blocked Sites (Blacklist Exception List)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Blocked Sites",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { showAddBlockedDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add blocked site exception",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (blacklist.isEmpty()) {
                item {
                    Text(
                        "No exceptions. Tap + to always block popups on a site.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            } else {
                items(blacklist, key = { "blocked_$it" }) { host ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            host,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            coroutineScope.launch {
                                settingsRepository.removePopupBlacklist(host)
                            }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove $host",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Allowed exceptions dialog
    if (showAddAllowedDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddAllowedDialog = false
                newHost = ""
            },
            title = { Text("Always Allow Site") },
            text = {
                OutlinedTextField(
                    value = newHost,
                    onValueChange = { newHost = it },
                    label = { Text("Hostname (e.g. example.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        normalizeAndAddHost(newHost, isAllowedList = true)
                        showAddAllowedDialog = false
                        newHost = ""
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddAllowedDialog = false
                    newHost = ""
                }) { Text("Cancel") }
            }
        )
    }

    // Blocked exceptions dialog
    if (showAddBlockedDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddBlockedDialog = false
                newHost = ""
            },
            title = { Text("Always Block Site") },
            text = {
                OutlinedTextField(
                    value = newHost,
                    onValueChange = { newHost = it },
                    label = { Text("Hostname (e.g. example.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        normalizeAndAddHost(newHost, isAllowedList = false)
                        showAddBlockedDialog = false
                        newHost = ""
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddBlockedDialog = false
                    newHost = ""
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun PopupBlockedBar(
    host: String,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Popup from $host",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAllowOnce, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    "Allow once",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inversePrimary
                )
            }
            TextButton(onClick = onAlwaysAllow, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    "Always",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inversePrimary
                )
            }
            TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(
                    "✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}
