package com.playbridge.sender.browser

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private sealed class SettingsSection {
    object Hub : SettingsSection()
    object Library : SettingsSection()
    object Debrid : SettingsSection()
    object Playback : SettingsSection()
    object TV : SettingsSection()
    object ImportExport : SettingsSection()
    object PopupBlocker : SettingsSection()
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddonSettings: () -> Unit = {},
    tvIp: String? = null,
    tvPort: Int? = null
) {
    var section by remember { mutableStateOf<SettingsSection>(SettingsSection.Hub) }

    BackHandler(enabled = section != SettingsSection.Hub) {
        section = SettingsSection.Hub
    }

    when (section) {
        SettingsSection.Hub -> SettingsHubContent(
            onBack = onBack,
            onLibrary = { section = SettingsSection.Library },
            onDebrid = { section = SettingsSection.Debrid },
            onPlayback = { section = SettingsSection.Playback },
            onTV = { section = SettingsSection.TV },
            onImportExport = { section = SettingsSection.ImportExport },
            onPopupBlocker = { section = SettingsSection.PopupBlocker }
        )
        SettingsSection.PopupBlocker -> PopupBlockerSettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Library -> LibrarySettingsScreen(
            onBack = { section = SettingsSection.Hub },
            onAddonSettings = onAddonSettings
        )
        SettingsSection.Debrid -> DebridSettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Playback -> PlaybackSettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.TV -> TVSettingsScreen(
            onBack = { section = SettingsSection.Hub },
            tvIp = tvIp,
            tvPort = tvPort
        )
        SettingsSection.ImportExport -> ImportExportSettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHubContent(
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onDebrid: () -> Unit,
    onPlayback: () -> Unit,
    onTV: () -> Unit,
    onImportExport: () -> Unit,
    onPopupBlocker: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    var showInbuiltExtensions by remember {
        mutableStateOf(prefs.getBoolean("show_inbuilt_extensions", false))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show Inbuilt Extensions", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Display system extensions in the extensions list",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showInbuiltExtensions,
                    onCheckedChange = { checked ->
                        showInbuiltExtensions = checked
                        prefs.edit().putBoolean("show_inbuilt_extensions", checked).apply()
                    }
                )
            }

            HorizontalDivider()

            SettingsNavItem(
                icon = Icons.Default.VideoLibrary,
                title = "Library",
                subtitle = "API keys and addon management",
                onClick = onLibrary
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            SettingsNavItem(
                icon = Icons.Default.Cloud,
                title = "Debrid",
                subtitle = "Real-Debrid, All-Debrid, Premiumize",
                onClick = onDebrid
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            SettingsNavItem(
                icon = Icons.Default.Tune,
                title = "Playback",
                subtitle = "Audio, subtitles, video quality",
                onClick = onPlayback
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            SettingsNavItem(
                icon = Icons.Default.Tv,
                title = "TV",
                subtitle = "Player defaults and diagnostics",
                onClick = onTV
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            SettingsNavItem(
                icon = Icons.Default.SwapVert,
                title = "Import / Export",
                subtitle = "Backup and restore settings",
                onClick = onImportExport
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            SettingsNavItem(
                icon = Icons.Default.Block,
                title = "Popup Blocker",
                subtitle = "Block popups with per-site exceptions",
                onClick = onPopupBlocker
            )

            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (e: Exception) {
                    null
                }
            }
            if (versionName != null) {
                HorizontalDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Version $versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
