package com.playbridge.sender.browser

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    onBack: () -> Unit,
    onAddonSettings: () -> Unit
) {
    val context = LocalContext.current
    val tmdbPrefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }
    val browserPrefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }

    var tmdbApiKey by remember { mutableStateOf(tmdbPrefs.getString("tmdb_api_key", "") ?: "") }
    var showTmdbKey by remember { mutableStateOf(false) }

    var omdbApiKey by remember { mutableStateOf(tmdbPrefs.getString("omdb_api_key", "") ?: "") }
    var showOmdbKey by remember { mutableStateOf(false) }

    var tvdbApiKey by remember { mutableStateOf(tmdbPrefs.getString("tvdb_api_key", "") ?: "") }
    var showTvdbKey by remember { mutableStateOf(false) }
    var seriesSource by remember { mutableStateOf(tmdbPrefs.getString("series_meta_source", "auto") ?: "auto") }
    var seriesSourceExpanded by remember { mutableStateOf(false) }

    var showCardTextOverlay by remember { mutableStateOf(tmdbPrefs.getBoolean("show_card_text_overlay", false)) }

    // Stream picker auto-select prefs
    val qualityOptions = listOf(
        "" to "Off (always show picker)",
        "2160p" to "4K (2160p)",
        "1080p" to "1080p",
        "720p" to "720p"
    )
    var autoQuality by remember {
        mutableStateOf(browserPrefs.getString("auto_stream_quality", "") ?: "")
    }
    var qualityExpanded by remember { mutableStateOf(false) }

    var autoMaxMbpsText by remember {
        mutableStateOf(browserPrefs.getString("auto_stream_max_mbps", "") ?: "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
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
            OutlinedTextField(
                value = tmdbApiKey,
                onValueChange = { newKey ->
                    tmdbApiKey = newKey
                    tmdbPrefs.edit().putString("tmdb_api_key", newKey.trim()).apply()
                },
                label = { Text("TMDB API Key") },
                placeholder = { Text("Enter your TMDB API key") },
                supportingText = { Text("Free at themoviedb.org → Settings → API") },
                singleLine = true,
                visualTransformation = if (showTmdbKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showTmdbKey = !showTmdbKey }) {
                        Icon(
                            if (showTmdbKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showTmdbKey) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = omdbApiKey,
                onValueChange = { newKey ->
                    omdbApiKey = newKey
                    tmdbPrefs.edit().putString("omdb_api_key", newKey.trim()).apply()
                },
                label = { Text("OMDB API Key (Optional)") },
                placeholder = { Text("Enter your OMDB API key") },
                supportingText = { Text("Free at omdbapi.com (For IMDb/Rotten Tomatoes Ratings)") },
                singleLine = true,
                visualTransformation = if (showOmdbKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showOmdbKey = !showOmdbKey }) {
                        Icon(
                            if (showOmdbKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showOmdbKey) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = tvdbApiKey,
                onValueChange = { newKey ->
                    tvdbApiKey = newKey
                    tmdbPrefs.edit().putString("tvdb_api_key", newKey.trim()).apply()
                },
                label = { Text("TVDB API Key (Optional)") },
                placeholder = { Text("Get one free at thetvdb.com/dashboard") },
                visualTransformation = if (showTvdbKey) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showTvdbKey = !showTvdbKey }) {
                        Icon(
                            if (showTvdbKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            if (tvdbApiKey.isNotBlank()) {
                val sourceOptions = listOf(
                    "auto" to "Auto (TVDB if configured)",
                    "tvdb" to "Always TVDB",
                    "tmdb" to "Always TMDB"
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = sourceOptions.firstOrNull { it.first == seriesSource }?.second ?: "Auto (TVDB if configured)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Series metadata source") },
                        trailingIcon = {
                            Icon(
                                if (seriesSourceExpanded) Icons.Default.ArrowDropUp
                                else Icons.Default.ArrowDropDown,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { seriesSourceExpanded = !seriesSourceExpanded }
                    )
                    DropdownMenu(
                        expanded = seriesSourceExpanded,
                        onDismissRequest = { seriesSourceExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        sourceOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    seriesSource = value
                                    tmdbPrefs.edit().putString("series_meta_source", value).apply()
                                    seriesSourceExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("Display Options", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showCardTextOverlay = !showCardTextOverlay
                        tmdbPrefs.edit().putBoolean("show_card_text_overlay", showCardTextOverlay).apply()
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Details on Library Cards", style = MaterialTheme.typography.titleSmall)
                    Text("Display movie rating, title, and year permanently over the poster cards", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = showCardTextOverlay,
                    onCheckedChange = { isChecked ->
                        showCardTextOverlay = isChecked
                        tmdbPrefs.edit().putBoolean("show_card_text_overlay", isChecked).apply()
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("Stream Picker", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Text(
                "Automatically pick a stream without showing the picker. Streams are matched by quality tag in their name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Auto-select quality dropdown
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = qualityOptions.find { it.first == autoQuality }?.second ?: "Off (always show picker)",
                    onValueChange = {},
                    label = { Text("Auto-select Quality") },
                    trailingIcon = {
                        IconButton(onClick = { qualityExpanded = !qualityExpanded }) {
                            Icon(
                                if (qualityExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (qualityExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { qualityExpanded = !qualityExpanded }
                )
                DropdownMenu(
                    expanded = qualityExpanded,
                    onDismissRequest = { qualityExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    qualityOptions.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                autoQuality = code
                                qualityExpanded = false
                                browserPrefs.edit().putString("auto_stream_quality", code).apply()
                            }
                        )
                    }
                }
            }

            // Max bitrate field — only relevant when auto-select is enabled
            if (autoQuality.isNotEmpty()) {
                OutlinedTextField(
                    value = autoMaxMbpsText,
                    onValueChange = { raw ->
                        // Allow only digits and a single decimal point
                        if (raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1) {
                            autoMaxMbpsText = raw
                            browserPrefs.edit().putString("auto_stream_max_mbps", raw.trim()).apply()
                        }
                    },
                    label = { Text("Max Bitrate (Mbps, optional)") },
                    placeholder = { Text("e.g. 20 — leave blank to pick first match") },
                    supportingText = { Text("Only streams at or below this bitrate will be auto-selected. Requires file size metadata from the addon.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            OutlinedButton(
                onClick = onAddonSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage Stremio Addons")
            }
        }
    }
}

