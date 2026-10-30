package com.playbridge.sender.cast
import androidx.core.content.edit

import android.content.Context
import org.koin.compose.koinInject
import com.playbridge.sender.data.settings.SettingsRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsRepository: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val browserPrefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }

    // --- Unified Quality & Selection ---
    val preferredResolution by settingsRepository.defaultVideoQuality.collectAsState(initial = "Auto")
    var resolutionExpanded by remember { mutableStateOf(false) }

    // --- Language Preferences ---
    val preferredAudioLang by settingsRepository.preferredAudioLang.collectAsState(initial = "")
    var audioExpanded by remember { mutableStateOf(false) }

    val preferredSubLang by settingsRepository.preferredSubtitleLang.collectAsState(initial = "")
    var subExpanded by remember { mutableStateOf(false) }
    val hideEmptyCastSheetTabs by settingsRepository.hideEmptyCastSheetTabs.collectAsState(initial = true)

    val queueWindow by settingsRepository.tvPrefetchWindow.collectAsState(initial = 1)
    val maxBitrateCapMbps by settingsRepository.maxBitrateCapMbps.collectAsState(initial = 0.0)
    var maxBitrateText by remember { mutableStateOf("") }

    
    LaunchedEffect(maxBitrateCapMbps) {
        maxBitrateText = if (maxBitrateCapMbps > 0.0) maxBitrateCapMbps.toString() else ""
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
            Text("Cast Sheet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            ListItem(
                headlineContent = { Text("Hide empty Audio and Images tabs") },
                supportingContent = { Text("Videos and Subtitles stay available; newly detected media updates the tab order.") },
                trailingContent = {
                    Switch(
                        checked = hideEmptyCastSheetTabs,
                        onCheckedChange = { checked ->
                            scope.launch { settingsRepository.setHideEmptyCastSheetTabs(checked) }
                        }
                    )
                },
                modifier = Modifier.clickable {
                    scope.launch { settingsRepository.setHideEmptyCastSheetTabs(!hideEmptyCastSheetTabs) }
                }
            )

            Text("Stream Selection & Quality", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            // Preferred Resolution (MASTER setting)
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = SubtitlePreferences.videoQualities.find { it.second == preferredResolution }?.first ?: "Auto",
                    onValueChange = {},
                    label = { Text("Preferred Resolution") },
                    supportingText = { Text("Default video track quality when casting.") },
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
                                resolutionExpanded = false
                                scope.launch { settingsRepository.setDefaultVideoQuality(code) }
                                browserPrefs.edit { putString("default_video_quality", code) }
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = maxBitrateText,
                onValueChange = { raw ->
                    if (raw.all { it.isDigit() || it == '.' } && raw.count { it == '.' } <= 1) {
                        maxBitrateText = raw
                        scope.launch { settingsRepository.setMaxBitrateCapMbps(raw.toDoubleOrNull() ?: 0.0) }
                    }
                },
                label = { Text("Maximum video bitrate (Mbps)") },
                supportingText = { Text("Leave empty for no limit.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            Text("Website queue", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Items requested ahead", modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { settingsRepository.setTvPrefetchWindow(queueWindow - 1) } }, enabled = queueWindow > 1) {
                    Icon(Icons.Default.Remove, contentDescription = "Fewer items")
                }
                Text("$queueWindow")
                IconButton(onClick = { scope.launch { settingsRepository.setTvPrefetchWindow(queueWindow + 1) } }, enabled = queueWindow < 10) {
                    Icon(Icons.Default.Add, contentDescription = "More items")
                }
            }

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
                                audioExpanded = false
                                scope.launch { settingsRepository.setPreferredAudioLang(code) }
                                browserPrefs.edit { putString("preferred_audio_lang", code) }
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
                                subExpanded = false
                                scope.launch { settingsRepository.setPreferredSubtitleLang(code) }
                                browserPrefs.edit { putString("preferred_subtitle_lang", code) }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

        }
    }
}
