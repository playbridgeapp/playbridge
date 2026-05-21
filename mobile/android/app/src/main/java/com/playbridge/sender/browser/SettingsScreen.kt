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
import com.playbridge.sender.ui.theme.AppTheme

private sealed class SettingsSection {
    object Hub : SettingsSection()
    object Appearance : SettingsSection()
    object Library : SettingsSection()
    object Debrid : SettingsSection()
    object Proxy : SettingsSection()
    object Streaming : SettingsSection()
    object TV : SettingsSection()
    object ImportExport : SettingsSection()
    object PopupBlocker : SettingsSection()
}

private data class SettingsItemData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    tvIp: String? = null,
    tvPort: Int? = null,
    showBack: Boolean = true,
    isFromLibrary: Boolean = false
) {
    var section by remember { mutableStateOf<SettingsSection>(SettingsSection.Hub) }

    BackHandler(enabled = section != SettingsSection.Hub) {
        section = SettingsSection.Hub
    }

    when (section) {
        SettingsSection.Hub -> SettingsHubContent(
            onBack = onBack,
            onAppearance = { section = SettingsSection.Appearance },
            onLibrary = { section = SettingsSection.Library },
            onDebrid = { section = SettingsSection.Debrid },
            onProxy = { section = SettingsSection.Proxy },
            onStreaming = { section = SettingsSection.Streaming },
            onTV = { section = SettingsSection.TV },
            onImportExport = { section = SettingsSection.ImportExport },
            onPopupBlocker = { section = SettingsSection.PopupBlocker },
            showBack = showBack,
            isFromLibrary = isFromLibrary
        )
        SettingsSection.Appearance -> AppearanceSettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.PopupBlocker -> PopupBlockerSettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Library -> LibrarySettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Debrid -> DebridSettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Proxy -> MediaflowSettingsScreen(
            onBack = { section = SettingsSection.Hub }
        )
        SettingsSection.Streaming -> StreamingSettingsScreen(
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
    onAppearance: () -> Unit,
    onLibrary: () -> Unit,
    onDebrid: () -> Unit,
    onProxy: () -> Unit,
    onStreaming: () -> Unit,
    onTV: () -> Unit,
    onImportExport: () -> Unit,
    onPopupBlocker: () -> Unit,
    showBack: Boolean = true,
    isFromLibrary: Boolean = false
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
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
            val items = remember(isFromLibrary, context) {
                buildList {
                    // 1. Appearance (Shared)
                    add(
                        SettingsItemData(
                            icon = Icons.Default.Palette,
                            title = "Appearance",
                            subtitle = "Theme: ${AppTheme.fromPrefs(context).label}",
                            onClick = onAppearance
                        )
                    )

                    // 2. Library (Library-specific)
                    if (isFromLibrary) {
                        add(
                            SettingsItemData(
                                icon = Icons.Default.VideoLibrary,
                                title = "Library",
                                subtitle = "Metadata API keys and display options",
                                onClick = onLibrary
                            )
                        )
                    }

                    // 3. Debrid (Shared)
                    add(
                        SettingsItemData(
                            icon = Icons.Default.Cloud,
                            title = "Debrid",
                            subtitle = "Real-Debrid, All-Debrid, Premiumize, TorBox",
                            onClick = onDebrid
                        )
                    )

                    // 4. Proxy (Shared)
                    add(
                        SettingsItemData(
                            icon = Icons.Default.SwapHoriz,
                            title = "Proxy",
                            subtitle = "mediaflow-proxy for stream passthrough & transcoding",
                            onClick = onProxy
                        )
                    )

                    // 5. Streaming (Library-specific)
                    if (isFromLibrary) {
                        add(
                            SettingsItemData(
                                icon = Icons.Default.Tune,
                                title = "Streaming Preferences",
                                subtitle = "Audio, subtitles, and auto-select quality",
                                onClick = onStreaming
                            )
                        )
                    }

                    // 6. TV (Shared)
                    add(
                        SettingsItemData(
                            icon = Icons.Default.Tv,
                            title = "TV",
                            subtitle = "Player defaults and diagnostics",
                            onClick = onTV
                        )
                    )

                    // 7. Import/Export (Shared)
                    add(
                        SettingsItemData(
                            icon = Icons.Default.SwapVert,
                            title = "Import / Export",
                            subtitle = "Backup and restore settings",
                            onClick = onImportExport
                        )
                    )

                    // 8. Popup Blocker (Browser-specific)
                    if (!isFromLibrary) {
                        add(
                            SettingsItemData(
                                icon = Icons.Default.Block,
                                title = "Popup Blocker",
                                subtitle = "Block popups with per-site exceptions",
                                onClick = onPopupBlocker
                            )
                        )
                    }
                }
            }

            items.forEachIndexed { index, item ->
                SettingsNavItem(
                    icon = item.icon,
                    title = item.title,
                    subtitle = item.subtitle,
                    onClick = item.onClick
                )
                if (index < items.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }

            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (e: Exception) {
                    null
                }
            }
            if (versionName != null) {
                Spacer(modifier = Modifier.height(12.dp))
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
