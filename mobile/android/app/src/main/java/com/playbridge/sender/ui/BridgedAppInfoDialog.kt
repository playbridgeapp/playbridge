package com.playbridge.sender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.playbridge.sender.browser.BridgedApp

@Composable
internal fun BridgedAppInfoDialog(
    app: BridgedApp,
    onSave: (String, String) -> Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(app.origin) { mutableStateOf(app.name) }
    var homeUrl by rememberSaveable(app.origin) { mutableStateOf(app.startUrl) }
    var confirmRemove by rememberSaveable(app.origin) { mutableStateOf(false) }
    var saveFailed by rememberSaveable(app.origin) { mutableStateOf(false) }
    val valid = app.editing(name, homeUrl) != null

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${app.name}?") },
            text = { Text("Its dashboard tile and app session will be removed. Website data and casting permissions are kept.") },
            confirmButton = { TextButton(onClick = onRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Bridged App Info") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; saveFailed = false },
                        label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("bridged-app-name"),
                    )
                    OutlinedTextField(
                        value = homeUrl, onValueChange = { homeUrl = it; saveFailed = false },
                        label = { Text("Home URL") }, maxLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("bridged-app-home-url"),
                    )
                    Text("Website origin: ${app.origin}", style = MaterialTheme.typography.bodySmall)
                    Text("After a fresh PlayBridge launch, this app opens at its home URL. Use a URL on the same website origin and a name of 1–60 characters.", style = MaterialTheme.typography.bodySmall)
                    if (!valid || saveFailed) {
                        Text(
                            if (saveFailed) "This app could not be saved. It may have been removed."
                            else "Enter a name of 1–60 characters and a valid home URL on this website origin.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = { confirmRemove = true }) {
                        Text("Remove Bridged App", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    if (onSave(name, homeUrl)) onDismiss() else saveFailed = true
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
