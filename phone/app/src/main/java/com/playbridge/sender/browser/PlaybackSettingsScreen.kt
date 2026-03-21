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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }

    var preferredAudioLang by remember {
        mutableStateOf(prefs.getString("preferred_audio_lang", "") ?: "")
    }
    var audioExpanded by remember { mutableStateOf(false) }

    var preferredSubLang by remember {
        mutableStateOf(prefs.getString("preferred_subtitle_lang", "") ?: "")
    }
    var subExpanded by remember { mutableStateOf(false) }

    var defaultVideoQuality by remember {
        mutableStateOf(prefs.getString("default_video_quality", "Auto") ?: "Auto")
    }
    var qualityExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playback") },
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
                                contentDescription = if (audioExpanded) "Collapse" else "Expand"
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
                                prefs.edit().putString("preferred_audio_lang", code).apply()
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
                                contentDescription = if (subExpanded) "Collapse" else "Expand"
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
                                prefs.edit().putString("preferred_subtitle_lang", code).apply()
                            }
                        )
                    }
                }
            }

            // Default Video Quality
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = SubtitlePreferences.videoQualities.find { it.second == defaultVideoQuality }?.first ?: "Auto",
                    onValueChange = {},
                    label = { Text("Default Video Quality") },
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
                    SubtitlePreferences.videoQualities.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                defaultVideoQuality = code
                                qualityExpanded = false
                                prefs.edit().putString("default_video_quality", code).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}
