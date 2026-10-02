package com.playbridge.sender.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.settings.SettingsRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.net.URI

/** Shared entry points for browser preferences and the current site's popup policy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPreferencesSheet(
    title: String,
    siteUrl: String? = null,
    onDismiss: () -> Unit,
    onMediaDetection: () -> Unit,
    onClearData: () -> Unit,
    userAgentActive: Boolean = false,
    onUserAgent: () -> Unit = {},
    onExtensions: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val blockPopups by settings.blockPopups.collectAsState(initial = true)
    val allowed by settings.popupWhitelist.collectAsState(initial = emptySet())
    val blocked by settings.popupBlacklist.collectAsState(initial = emptySet())
    val host = remember(siteUrl) { runCatching { URI(siteUrl.orEmpty()).host }.getOrNull() }
    var advanced by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        if (page == "permissions") {
            Column(Modifier.heightIn(max = 650.dp)) {
                PageCastConsentSettingsScreen(onBack = { page = null })
            }
        } else if (page == "popups") {
            Column(Modifier.heightIn(max = 650.dp)) {
                PopupBlockerSettingsScreen(onBack = { page = null })
            }
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                if (host != null) {
                    Text(host, style = MaterialTheme.typography.titleMedium)
                    Text("Popups for this site", style = MaterialTheme.typography.titleSmall)
                    val selected = when {
                        isHostMatch(host, allowed) -> "Allow"
                        isHostMatch(host, blocked) -> "Block"
                        else -> "Default"
                    }
                    listOf("Default", "Allow", "Block").forEach { option ->
                        TextButton(onClick = {
                            scope.launch {
                                settings.removePopupWhitelist(host)
                                settings.removePopupBlacklist(host)
                                if (option == "Allow") settings.addPopupWhitelist(host)
                                if (option == "Block") settings.addPopupBlacklist(host)
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(option + if (option == "Default") " (${if (blockPopups) "Block" else "Allow"})" else "",
                                modifier = Modifier.weight(1f))
                            if (selected == option) Text("✓")
                        }
                    }
                    Text("Rules may also be inherited from a parent domain. Manage exceptions in Popup Blocker.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Block popups", modifier = Modifier.weight(1f))
                        Switch(blockPopups, onCheckedChange = { scope.launch { settings.setBlockPopups(it) } })
                    }
                }
                BrowserMenuRow(Icons.Default.Shield, "Popup Blocker", onClick = { page = "popups" })
                BrowserMenuRow(Icons.Default.Security, "Website casting permissions", onClick = { page = "permissions" })
                HorizontalDivider()
                BrowserMenuRow(Icons.Default.PlayCircle, "Media detect", onClick = onMediaDetection)
                if (siteUrl == null) {
                    BrowserMenuRow(Icons.Default.DeleteSweep, "Clear Browsing Data", onClick = onClearData)
                    HorizontalDivider()
                    BrowserMenuRow(Icons.Default.Settings, "Advanced",
                        trailingIcon = if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        onClick = { advanced = !advanced })
                    if (advanced) {
                        BrowserMenuRow(Icons.Default.Language, "User Agent", status = if (userAgentActive) "Changed" else "Default", onClick = onUserAgent)
                        BrowserMenuRow(Icons.Default.Extension, "Extensions", onClick = onExtensions)
                    }
                }
            }
        }
    }
}
