package com.playbridge.sender.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.debrid.DebridRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebridSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { DebridRepository(context) }

    var activeProvider by remember { mutableStateOf(repository.getConfiguredProviderName()) }
    var providerExpanded by remember { mutableStateOf(false) }
    val providerOptions = remember {
        listOf(DebridRepository.PROVIDER_NONE) + DebridRepository.ALL_PROVIDERS
    }

    // One editable key per provider, seeded from storage.
    val apiKeys = remember {
        mutableStateMapOf<String, String>().apply {
            DebridRepository.ALL_PROVIDERS.forEach { put(it, repository.getApiKeyFor(it)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debrid") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Active provider selector ────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = activeProvider,
                    onValueChange = {},
                    label = { Text("Active Provider") },
                    trailingIcon = {
                        IconButton(onClick = { providerExpanded = !providerExpanded }) {
                            Icon(
                                if (providerExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (providerExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { providerExpanded = !providerExpanded }
                )
                DropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    providerOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                activeProvider = option
                                providerExpanded = false
                                repository.setActiveProvider(option)
                            }
                        )
                    }
                }
            }

            // ── Per-provider API keys ───────────────────────────────────────────
            Text(
                "API Keys",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Enter a key for each provider you use. Tap the title in the Debrid Library to switch between configured providers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DebridRepository.ALL_PROVIDERS.forEach { provider ->
                var visible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = apiKeys[provider] ?: "",
                    onValueChange = { newKey ->
                        apiKeys[provider] = newKey
                        repository.saveApiKeyFor(provider, newKey)
                    },
                    label = { Text("$provider API Key") },
                    placeholder = { Text("Enter API key") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (visible) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
