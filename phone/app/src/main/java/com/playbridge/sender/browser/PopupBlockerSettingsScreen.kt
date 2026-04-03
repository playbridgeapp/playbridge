package com.playbridge.sender.browser

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PopupBlockerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }

    var blockPopups by remember { mutableStateOf(prefs.getBoolean("block_popups", true)) }
    var whitelist by remember {
        mutableStateOf(prefs.getStringSet("popup_whitelist", emptySet())!!.toSortedSet().toList())
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var newHost by remember { mutableStateOf("") }

    fun saveWhitelist(updated: List<String>) {
        prefs.edit().putStringSet("popup_whitelist", updated.toSet()).apply()
        whitelist = updated.sorted()
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
        },
        floatingActionButton = {
            if (blockPopups) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add site")
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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
                            blockPopups = checked
                            prefs.edit().putBoolean("block_popups", checked).apply()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (blockPopups) {
                item {
                    Text(
                        "Allowed Sites",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }

                if (whitelist.isEmpty()) {
                    item {
                        Text(
                            "No exceptions. Tap + to allow a site.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    items(whitelist, key = { it }) { host ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                host,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                saveWhitelist(whitelist.filter { it != host })
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
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                newHost = ""
            },
            title = { Text("Allow Site") },
            text = {
                OutlinedTextField(
                    value = newHost,
                    onValueChange = { newHost = it.trim() },
                    label = { Text("Hostname (e.g. example.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val host = newHost.lowercase()
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .trimEnd('/')
                        if (host.isNotBlank() && !whitelist.contains(host)) {
                            saveWhitelist(whitelist + host)
                        }
                        showAddDialog = false
                        newHost = ""
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
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
