package com.playbridge.sender.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.playbridge.sender.browser.BridgedApp

/** Web app details: rename, change the home page or remove an installed web app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BridgedAppInfoDialog(
    app: BridgedApp,
    onSave: (String, String) -> Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Drafts survive Activity recreation; edits keep the app's origin, icon, tab and tile slot.
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
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (app.iconUrl != null) {
                        AsyncImage(model = app.iconUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        app.origin.removePrefix("https://"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it; saveFailed = false },
                label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bridged-app-name"),
            )
            OutlinedTextField(
                value = homeUrl, onValueChange = { homeUrl = it; saveFailed = false },
                label = { Text("Home page") }, maxLines = 4,
                supportingText = { Text("Opens here after a fresh launch. Must stay on ${app.origin}.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("bridged-app-home-url"),
            )
            if (!valid || saveFailed) {
                Text(
                    if (saveFailed) "This app could not be saved. It may have been removed."
                    else "Enter a name of 1–60 characters and a valid home page on this website.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp))
                    Text("  Remove", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    enabled = valid,
                    onClick = { if (onSave(name, homeUrl)) onDismiss() else saveFailed = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Save") }
            }
        }
    }
}
