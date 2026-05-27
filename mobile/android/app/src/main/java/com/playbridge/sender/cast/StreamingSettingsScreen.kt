package com.playbridge.sender.cast

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val browserPrefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }

    // --- Unified Quality & Selection ---
    var preferredResolution by remember {
        mutableStateOf(browserPrefs.getString("default_video_quality", "Auto") ?: "Auto")
    }
    var resolutionExpanded by remember { mutableStateOf(false) }

    var autoSelectEnabled by remember {
        mutableStateOf(browserPrefs.getBoolean("auto_select_enabled", false))
    }

    val addonDao = remember {
        DatabaseProvider.getDatabase(context).addonDao()
    }
    val installedAddons by addonDao.getAll().collectAsState(initial = emptyList())
    val streamAddons = remember(installedAddons) {
        installedAddons.filter { it.isEnabled && it.supportsResource("stream") && it.isFeatureEnabled("stream") }
    }

    var autoAddon by remember {
        mutableStateOf(browserPrefs.getString("auto_stream_addon", "") ?: "")
    }
    var addonExpanded by remember { mutableStateOf(false) }

    var autoSourceTypes by remember {
        mutableStateOf(SourceTypeFilter.parseCsv(browserPrefs.getString("auto_stream_source_types", "")))
    }

    // --- Language Preferences ---
    var preferredAudioLang by remember {
        mutableStateOf(browserPrefs.getString("preferred_audio_lang", "") ?: "")
    }
    var audioExpanded by remember { mutableStateOf(false) }

    var preferredSubLang by remember {
        mutableStateOf(browserPrefs.getString("preferred_subtitle_lang", "") ?: "")
    }
    var subExpanded by remember { mutableStateOf(false) }

    var minSizeText by remember {
        mutableStateOf(browserPrefs.getString("auto_stream_min_gb", "") ?: "")
    }
    var maxSizeText by remember {
        mutableStateOf(browserPrefs.getString("auto_stream_max_gb", "") ?: "")
    }
    var minBitrateText by remember {
        mutableStateOf(browserPrefs.getString("auto_stream_min_mbps", "") ?: "")
    }
    var maxBitrateText by remember {
        mutableStateOf(browserPrefs.getString("auto_stream_max_mbps", "") ?: "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Streaming Preferences") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Stream Selection & Quality", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            // Preferred Resolution (MASTER setting)
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = SubtitlePreferences.videoQualities.find { it.second == preferredResolution }?.first ?: "Auto",
                    onValueChange = {},
                    label = { Text("Preferred Resolution") },
                    supportingText = { Text("Used for both auto-selection and player track defaults.") },
                    trailingIcon = {
                        IconButton(onClick = { resolutionExpanded = !resolutionExpanded }) {
                            Icon(
                                if (resolutionExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { resolutionExpanded = !resolutionExpanded }
                )
                DropdownMenu(
                    expanded = resolutionExpanded,
                    onDismissRequest = { resolutionExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    SubtitlePreferences.videoQualities.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                preferredResolution = code
                                resolutionExpanded = false
                                browserPrefs.edit().putString("default_video_quality", code).apply()
                            }
                        )
                    }
                }
            }

            // Auto-Select Switch
            ListItem(
                headlineContent = { Text("Auto-Select Matching Stream") },
                supportingContent = { Text("Automatically launch the first stream matching your Preferred Resolution.") },
                trailingContent = {
                    Switch(
                        checked = autoSelectEnabled,
                        onCheckedChange = { checked ->
                            autoSelectEnabled = checked
                            browserPrefs.edit().putBoolean("auto_select_enabled", checked).apply()
                        }
                    )
                },
                modifier = Modifier.clickable {
                    autoSelectEnabled = !autoSelectEnabled
                    browserPrefs.edit().putBoolean("auto_select_enabled", autoSelectEnabled).apply()
                }
            )

            // Bitrate Constraints
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = minBitrateText,
                    onValueChange = { raw ->
                        if (raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1) {
                            minBitrateText = raw
                            browserPrefs.edit().putString("auto_stream_min_mbps", raw.trim()).apply()
                        }
                    },
                    label = { Text("Min Bitrate") },
                    placeholder = { Text("Mbps") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = maxBitrateText,
                    onValueChange = { raw ->
                        if (raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1) {
                            maxBitrateText = raw
                            browserPrefs.edit().putString("auto_stream_max_mbps", raw.trim()).apply()
                        }
                    },
                    label = { Text("Max Bitrate") },
                    placeholder = { Text("Mbps") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
            Text(
                "Filter streams by estimated bitrate. Leave empty for no limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Size Constraints
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = minSizeText,
                    onValueChange = { raw ->
                        if (raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1) {
                            minSizeText = raw
                            browserPrefs.edit().putString("auto_stream_min_gb", raw.trim()).apply()
                        }
                    },
                    label = { Text("Min Size") },
                    placeholder = { Text("GB") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = maxSizeText,
                    onValueChange = { raw ->
                        if (raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1) {
                            maxSizeText = raw
                            browserPrefs.edit().putString("auto_stream_max_gb", raw.trim()).apply()
                        }
                    },
                    label = { Text("Max Size") },
                    placeholder = { Text("GB") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
            Text(
                "Filter streams by file size. Leave empty for no limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Preferred Addon
            if (streamAddons.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        readOnly = true,
                        value = if (autoAddon.isEmpty()) "Any addon" else autoAddon,
                        onValueChange = {},
                        label = { Text("Preferred Addon") },
                        supportingText = { Text("Try streams from this addon first.") },
                        trailingIcon = {
                            IconButton(onClick = { addonExpanded = !addonExpanded }) {
                                Icon(
                                    if (addonExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.matchParentSize().clickable { addonExpanded = !addonExpanded })
                    DropdownMenu(
                        expanded = addonExpanded,
                        onDismissRequest = { addonExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Any addon") },
                            onClick = {
                                autoAddon = ""
                                browserPrefs.edit().putString("auto_stream_addon", "").apply()
                                addonExpanded = false
                            }
                        )
                        streamAddons.forEach { addon ->
                            DropdownMenuItem(
                                text = { Text(addon.name) },
                                onClick = {
                                    autoAddon = addon.name
                                    browserPrefs.edit().putString("auto_stream_addon", addon.name).apply()
                                    addonExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Source Types
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Preferred Source Types",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Prefer streams matching these release types (e.g. Remux, Bluray).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    items(SourceTypeFilter.ORDERED) { type ->
                        FilterChip(
                            selected = type in autoSourceTypes,
                            onClick = {
                                autoSourceTypes = if (type in autoSourceTypes) {
                                    autoSourceTypes - type
                                } else {
                                    autoSourceTypes + type
                                }
                                browserPrefs.edit()
                                    .putString("auto_stream_source_types", SourceTypeFilter.toCsv(autoSourceTypes))
                                    .apply()
                            },
                            label = { Text(type.label) }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Audio & Subtitles", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            // Preferred Audio Language
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = SubtitlePreferences.audioLanguages.find { it.second == preferredAudioLang }?.first ?: "Auto",
                    onValueChange = {},
                    label = { Text("Preferred Audio Language") },
                    trailingIcon = {
                        IconButton(onClick = { audioExpanded = !audioExpanded }) {
                            Icon(
                                if (audioExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { audioExpanded = !audioExpanded }
                )
                DropdownMenu(
                    expanded = audioExpanded,
                    onDismissRequest = { audioExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    SubtitlePreferences.audioLanguages.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                preferredAudioLang = code
                                audioExpanded = false
                                browserPrefs.edit().putString("preferred_audio_lang", code).apply()
                            }
                        )
                    }
                }
            }

            // Preferred Subtitle Language
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = SubtitlePreferences.subtitleLanguages.find { it.second == preferredSubLang }?.first ?: "None",
                    onValueChange = {},
                    label = { Text("Preferred Subtitle Language") },
                    trailingIcon = {
                        IconButton(onClick = { subExpanded = !subExpanded }) {
                            Icon(
                                if (subExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { subExpanded = !subExpanded }
                )
                DropdownMenu(
                    expanded = subExpanded,
                    onDismissRequest = { subExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    SubtitlePreferences.subtitleLanguages.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                preferredSubLang = code
                                subExpanded = false
                                browserPrefs.edit().putString("preferred_subtitle_lang", code).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}
