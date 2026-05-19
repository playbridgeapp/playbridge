package com.playbridge.sender.browser

import android.content.Context
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
    val tmdbPrefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }

    var debridProvider by remember {
        mutableStateOf(
            tmdbPrefs.getString(DebridRepository.KEY_DEBRID_PROVIDER, DebridRepository.PROVIDER_NONE)
                ?: DebridRepository.PROVIDER_NONE
        )
    }
    var debridApiKey by remember {
        mutableStateOf(tmdbPrefs.getString(DebridRepository.KEY_DEBRID_API_KEY, "") ?: "")
    }
    var debridExpanded by remember { mutableStateOf(false) }
    val debridOptions = listOf(
        DebridRepository.PROVIDER_NONE,
        DebridRepository.PROVIDER_REAL_DEBRID,
        DebridRepository.PROVIDER_ALL_DEBRID,
        DebridRepository.PROVIDER_PREMIUMIZE,
        DebridRepository.PROVIDER_TORBOX
    )

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
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = debridProvider,
                    onValueChange = {},
                    label = { Text("Active Provider") },
                    trailingIcon = {
                        IconButton(onClick = { debridExpanded = !debridExpanded }) {
                            Icon(
                                if (debridExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (debridExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { debridExpanded = !debridExpanded }
                )
                DropdownMenu(
                    expanded = debridExpanded,
                    onDismissRequest = { debridExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    debridOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                debridProvider = option
                                debridExpanded = false
                                tmdbPrefs.edit().putString(DebridRepository.KEY_DEBRID_PROVIDER, option).apply()
                            }
                        )
                    }
                }
            }

            if (debridProvider != DebridRepository.PROVIDER_NONE) {
                var showDebridKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = debridApiKey,
                    onValueChange = { newKey ->
                        debridApiKey = newKey
                        tmdbPrefs.edit().putString(DebridRepository.KEY_DEBRID_API_KEY, newKey.trim()).apply()
                    },
                    label = { Text("$debridProvider API Key") },
                    placeholder = { Text("Enter API key") },
                    singleLine = true,
                    visualTransformation = if (showDebridKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showDebridKey = !showDebridKey }) {
                            Icon(
                                if (showDebridKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showDebridKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
