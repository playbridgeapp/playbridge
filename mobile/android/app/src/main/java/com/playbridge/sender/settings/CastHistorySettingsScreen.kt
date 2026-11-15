package com.playbridge.sender.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.diagnostics.CastAttemptDiagnostics
import com.playbridge.sender.history.CastHistoryRepository
import com.playbridge.sender.history.CastHistorySettings
import com.playbridge.sender.history.CastHistoryStorage
import com.playbridge.sender.history.formatBytes
import com.playbridge.sender.history.limitHint
import com.playbridge.sender.history.storageLine
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Settings › Cast history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastHistorySettingsScreen(onBack: () -> Unit) {
    val settingsRepository: SettingsRepository = koinInject()
    val repo: CastHistoryRepository = koinInject()
    val diagnostics: CastAttemptDiagnostics = koinInject()
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.castHistory.collectAsStateWithLifecycle(initialValue = CastHistorySettings())
    val entries by repo.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    var storage by remember { mutableStateOf(CastHistoryStorage(0, 0, 0)) }
    LaunchedEffect(entries) { storage = repo.storage() }

    /** A lower limit asked for: (limit, how many casts it removes), waiting for a yes. */
    var pendingLimit by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    SettingsPage("Cast history", onBack) {
        SettingsSwitch(
            "Keep cast history",
            "Off: nothing new is saved. What is already there stays until you clear it.",
            settings.enabled,
        ) { scope.launch { settingsRepository.setCastHistoryEnabled(it) } }
        HorizontalDivider()

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "How many casts to keep",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "When the list is full, the oldest cast is removed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CastHistorySettings.LIMITS.forEachIndexed { index, limit ->
                    SegmentedButton(
                        selected = settings.limit == limit,
                        onClick = {
                            if (limit == settings.limit) return@SegmentedButton
                            scope.launch {
                                val removed = if (limit < settings.limit) repo.wouldRemove(limit) else 0
                                if (removed > 0) pendingLimit = limit to removed
                                else settingsRepository.setCastHistoryLimit(limit)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, CastHistorySettings.LIMITS.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("$limit") }
                }
            }
            limitHint(storage.casts)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()

        SettingsSwitch(
            "Remember where you stopped",
            "Saves the position and length while the phone is connected to the device playing it.",
            settings.rememberPosition,
        ) { scope.launch { settingsRepository.setCastHistoryRememberPosition(it) } }
        SettingsSwitch(
            "Keep preview pictures",
            "Uses the picture the cast sheet already made. Stored on this phone only.",
            settings.keepPreviews,
        ) { scope.launch { settingsRepository.setCastHistoryKeepPreviews(it) } }
        SettingsSwitch(
            "Check if links still work",
            "Off: only expiry times found in the link are shown. On: also asks the site, a few casts at a time, when you open history.",
            settings.checkLinks,
        ) { scope.launch { settingsRepository.setCastHistoryCheckLinks(it) } }
        SettingsSwitch(
            "Leave out bridged apps",
            "Casts from bridged apps are not saved here at all. The apps keep their own history.",
            settings.excludeBridgedApps,
        ) { scope.launch { settingsRepository.setCastHistoryExcludeBridgedApps(it) } }
        HorizontalDivider()

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(storageLine(storage), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${formatBytes(storage.pictureBytes)} on this phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { repo.removePictures(); storage = repo.storage() } },
                    enabled = storage.pictures > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Remove pictures") }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = storage.casts > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Clear history") }
            }
        }
        Text(
            "Watch party videos are never saved. Sign-in details needed to cast again stay encrypted on this phone, as today.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    pendingLimit?.let { (limit, removed) ->
        AlertDialog(
            onDismissRequest = { pendingLimit = null },
            title = { Text("Keep only $limit casts?") },
            text = { Text("This removes the $removed oldest ${if (removed == 1) "cast" else "casts"} right away. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingLimit = null
                    scope.launch { settingsRepository.setCastHistoryLimit(limit) }
                }) { Text("Remove $removed") }
            },
            dismissButton = { TextButton(onClick = { pendingLimit = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cast history?") },
            text = { Text("Removes ${storage.casts} ${if (storage.casts == 1) "cast" else "casts"}, their pictures and where you stopped from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { repo.clearAll(); diagnostics.clear(); storage = repo.storage() }
                }) { Text("Clear history") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
