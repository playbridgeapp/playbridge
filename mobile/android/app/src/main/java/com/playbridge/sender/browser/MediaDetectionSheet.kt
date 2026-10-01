package com.playbridge.sender.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.settings.MediaDetectionSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetectionSheet(
    settings: MediaDetectionSettings,
    onSettingsChange: (MediaDetectionSettings) -> Unit,
    onDismissRequest: () -> Unit,
) {
    // Keep local changes immediate while DataStore writes complete in the background.
    var draft by remember { mutableStateOf(settings) }
    var advanced by remember { mutableStateOf(false) }
    fun update(value: MediaDetectionSettings) {
        draft = value
        onSettingsChange(value)
    }
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismissRequest,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Media detect", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            DetectionToggle("Automatic detection", draft.enabled,
                "Applies to all browser tabs. Website cast buttons remain available.") {
                update(draft.copy(enabled = it))
            }
            HorizontalDivider()
            DetectionToggle("Video detection", draft.videos, enabled = draft.enabled) { update(draft.copy(videos = it)) }
            DetectionToggle("Images", draft.images, enabled = draft.enabled) { update(draft.copy(images = it)) }
            DetectionToggle("Audio", draft.audio, enabled = draft.enabled) { update(draft.copy(audio = it)) }
            DetectionToggle("Subtitles", draft.subtitles, enabled = draft.enabled) { update(draft.copy(subtitles = it)) }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium)
                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (advanced) "Collapse advanced" else "Expand advanced")
            }
            if (advanced) {
                Text("Turn features off individually to compare page responsiveness. Changes apply immediately; reload if existing media does not reappear.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                DetectionToggle("Page scanning", draft.domScanning,
                    "Watch page elements for media sources and images.", draft.enabled) { update(draft.copy(domScanning = it)) }
                DetectionToggle("Network detection", draft.networkDetection,
                    "Identify media from request URLs and response headers, and capture playback headers.", draft.enabled) { update(draft.copy(networkDetection = it)) }
                DetectionToggle("Response scanning", draft.responseScanning,
                    "Read text responses and playlists for embedded media URLs. May be needed for stream variants and audio tracks.", draft.enabled) { update(draft.copy(responseScanning = it)) }
                DetectionToggle("Scan on page changes", draft.navigationRescans,
                    "Rescan when a site changes views without reloading.", draft.enabled) { update(draft.copy(navigationRescans = it)) }
                DetectionToggle("Player probes", draft.playerProbes,
                    "Look for sources exposed by supported page players.", draft.enabled) { update(draft.copy(playerProbes = it)) }
                DetectionToggle("Keep page visible", draft.visibilityOverrides,
                    "Let the page see itself as visible while detection is active.", draft.enabled) { update(draft.copy(visibilityOverrides = it)) }
                DetectionToggle("Detect on bridged sites", draft.detectInBridgedSites,
                    "Override automatic detection being off for bridged apps such as Streams.", draft.enabled) { update(draft.copy(detectInBridgedSites = it)) }
            }
        }
    }
}

@Composable
private fun DetectionToggle(
    label: String,
    checked: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
