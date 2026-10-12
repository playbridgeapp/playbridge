package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Manage the TV browser's User-Agent remotely: pick a built-in preset, reselect something
 * already saved on the TV, or add a new custom one. Mirrors [UserScriptsSheet]'s remote
 * pattern — the TV is the source of truth for [active]/[savedEntries]; this sheet just
 * sends `user_agent` messages and waits for the next `user_agents` reply to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvUserAgentSheet(
    active: String,
    savedEntries: List<Pair<String, String>>,
    onSelectDefault: () -> Unit,
    onSelectPreset: (name: String, value: String) -> Unit,
    onApplyNamed: (name: String, value: String) -> Unit,
    onRemove: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys()
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text(
                "User Agent (TV Browser)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                "Changes what sites think the TV's browser is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider()

            TvUserAgentRow(
                label = "Default (Mobile)",
                selected = active.isBlank(),
                onClick = onSelectDefault,
            )
            com.playbridge.sender.browser.UserAgentPresets.presets
                .filter { it.value != null }
                .forEach { preset ->
                    TvUserAgentRow(
                        label = preset.label,
                        selected = active == preset.label,
                        onClick = { onSelectPreset(preset.label, preset.value!!) },
                    )
                }

            if (savedEntries.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Saved on TV",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                savedEntries.forEach { (name, value) ->
                    TvUserAgentRow(
                        label = name,
                        selected = active == name,
                        onClick = { onApplyNamed(name, value) },
                        trailing = {
                            IconButton(onClick = { onRemove(name) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove $name",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add custom user agent")
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys(); Text("Add custom user agent") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. My Custom UA") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("User agent string") },
                        placeholder = { Text("Mozilla/5.0 (...) ...") },
                        singleLine = false,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApplyNamed(name.trim(), value.trim())
                        showAddDialog = false
                    },
                    enabled = name.isNotBlank() && value.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TvUserAgentRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        trailing?.invoke()
    }
}
