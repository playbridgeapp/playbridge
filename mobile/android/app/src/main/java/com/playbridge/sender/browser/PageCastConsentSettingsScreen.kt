package com.playbridge.sender.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageCastConsentSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val linkedCoordinator: LinkedPageCastCoordinator = koinInject()
    var resetGeneration by remember { mutableIntStateOf(0) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showClearLocalConfirmation by remember { mutableStateOf(false) }
    val origins = remember(resetGeneration) { PageCastConsentStore.approvedOrigins(context).sorted() }
    val localNetworkOrigins = remember(resetGeneration) { PageCastConsentStore.localNetworkOrigins(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Website casting permissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))
            Text("Allowed websites", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "These websites may start a cast to your selected device without asking again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (origins.isEmpty()) {
                Text(
                    "No websites have been allowed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                origins.forEachIndexed { index, origin ->
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(PageCastConsentStore.displayName(origin))
                        Text(
                            buildString {
                                append(PageCastConsentStore.connectionLabel(origin))
                                if (origin in localNetworkOrigins) append(" · Local-network media allowed")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index < origins.lastIndex) HorizontalDivider()
                }
            }
            if (localNetworkOrigins.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { showClearLocalConfirmation = true },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) { Text("Reset local-network access") }
            }
            if (origins.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showClearConfirmation = true }) {
                    Text("Reset all website casting permissions")
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Reset website permissions?") },
            text = {
                Text(
                    "Every website will need permission before it can start another cast or load local-network media.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    linkedCoordinator.unlink("permission_reset")
                    PageCastConsentStore.clear(context)
                    resetGeneration += 1
                    showClearConfirmation = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearLocalConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearLocalConfirmation = false },
            title = { Text("Reset local-network access?") },
            text = {
                Text(
                    "Websites will ask again before a receiver can load media from your local network. " +
                        "Their basic casting permission will remain allowed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    linkedCoordinator.unlink("local_network_permission_reset")
                    PageCastConsentStore.clearLocalNetwork(context)
                    resetGeneration += 1
                    showClearLocalConfirmation = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showClearLocalConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}
