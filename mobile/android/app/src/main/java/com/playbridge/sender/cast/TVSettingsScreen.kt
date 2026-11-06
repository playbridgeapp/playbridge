package com.playbridge.sender.cast
import androidx.core.content.edit

import android.content.Context
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.settings.SettingsDropdown
import com.playbridge.sender.settings.SettingsGroupTitle
import com.playbridge.sender.settings.SettingsPage
import com.playbridge.sender.settings.SettingsSwitch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.playbridge.sender.cast.remote.RemoteHapticLevel
import com.playbridge.sender.cast.remote.RemotePresentationPreferenceStore
import com.playbridge.sender.cast.remote.rememberRemoteHapticEmitter

@Composable
fun TVSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsRepository: SettingsRepository = koinInject()
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // Options reflect what the connected/last-paired TV reported it supports. Capabilities are
    // persisted on the saved device, so the real list survives disconnects (see
    // TvCapabilityOptions). Before any TV is paired, only "TV Default" is offered.
    val connectionViewModel: ConnectionViewModel = koinViewModel()
    val tvDevice by connectionViewModel.tvDevice.collectAsState(initial = null)
    val autoConnect by connectionViewModel.autoConnectEnabled.collectAsState()

    val playerMode by settingsRepository.tvPlayerMode.collectAsState(initial = "tv")
    var browserMode by remember { mutableStateOf(prefs.getString("tv_browser_mode", "tv") ?: "tv") }
    val preferredResolution by settingsRepository.defaultVideoQuality.collectAsState(initial = "Auto")
    val maxBitrateCapMbps by settingsRepository.maxBitrateCapMbps.collectAsState(initial = 0.0)
    var maxBitrateText by remember { mutableStateOf("") }
    LaunchedEffect(maxBitrateCapMbps) {
        maxBitrateText = if (maxBitrateCapMbps > 0.0) maxBitrateCapMbps.toString() else ""
    }
    val castHistorySettings: CastHistorySettings = koinInject()
    val preventHistory by castHistorySettings.preventHistory.collectAsState()
    val autoSwitchToRemote by settingsRepository.autoSwitchToRemote.collectAsState(initial = true)
    // Idle link while backgrounded (default off = soft stand-down after grace).
    val keepTvInBackground by settingsRepository.keepTvConnectionInBackground.collectAsState(initial = false)

    SettingsPage("TV", onBack) {
        SettingsGroupTitle("Connection")
        SettingsSwitch("Auto-connect to TV", "Reconnect to the last PlayBridge TV", autoConnect, connectionViewModel::setAutoConnectEnabled)
        SettingsSwitch(
            "Keep connection in background",
            if (keepTvInBackground) {
                "Stay linked to the TV with a Connected notification when PlayBridge is in the background (uses more battery)."
            } else {
                "When nothing is casting, disconnect after ~45s in the background to save battery. Reconnects when you return to the app. Casting always stays connected."
            },
            keepTvInBackground,
        ) { scope.launch { settingsRepository.setKeepTvConnectionInBackground(it) } }
        val atvCredentials: com.playbridge.sender.cast.atv.AtvCredentialStore = koinInject()
        AtvPairedTvsSettings(atvCredentials)

        SettingsGroupTitle("Playback")
        SettingsDropdown("TV video player", playerMode, TvCapabilityOptions.playerOptions(tvDevice).map { it.second to it.first }, "TV Default") {
            scope.launch { settingsRepository.setTvPlayerMode(it) }
        }
        SettingsDropdown("TV browser engine", browserMode, TvCapabilityOptions.browserOptions(tvDevice).map { it.second to it.first }, "TV Default") {
            browserMode = it
            prefs.edit { putString("tv_browser_mode", it) }
        }
        SettingsDropdown(
            "Preferred resolution",
            preferredResolution,
            SubtitlePreferences.videoQualities,
            "Auto",
            description = "Default video track quality when casting.",
        ) { code ->
            scope.launch { settingsRepository.setDefaultVideoQuality(code) }
            prefs.edit { putString("default_video_quality", code) }
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

        SettingsGroupTitle("After casting")
        SettingsSwitch("Open the remote", "Show the remote control after casting starts", autoSwitchToRemote) {
            scope.launch { settingsRepository.setAutoSwitchToRemote(it) }
        }
        SettingsSwitch(
            "Don't save cast history on TV",
            "Keep new casts out of PlayBridge Android TV history and saved playback progress. Requires an updated TV app. Existing history stays unchanged.",
            preventHistory,
            castHistorySettings::setPreventHistory,
        )
    }
}

@Composable
internal fun RemoteSettingsGroup(
    store: RemotePresentationPreferenceStore,
    sample: (RemoteHapticLevel) -> Unit = rememberRemoteHapticEmitter(),
    showTitle: Boolean = true,
) {
    val preferences by store.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) Text("Remote", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        ListItem(
            headlineContent = { Text("Haptic feedback") },
            trailingContent = {
                Switch(
                    checked = preferences.hapticsEnabled,
                    onCheckedChange = { enabled -> store.update { it.copy(hapticsEnabled = enabled) } },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = "Haptic feedback" },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Intensity", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteHapticLevel.entries.forEach { level ->
                    FilterChip(
                        selected = preferences.hapticsLevel == level,
                        enabled = preferences.hapticsEnabled,
                        onClick = {
                            // Read the shared store at click time, including when another screen just disabled feedback.
                            if (store.load().hapticsEnabled) {
                                val updated = store.update { it.copy(hapticsLevel = level) }
                                if (updated.hapticsEnabled) sample(level)
                            }
                        },
                        label = { Text(level.name) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Haptic intensity ${level.name}" },
                    )
                }
            }
        }
    }
}
