package com.playbridge.sender.settings

import com.playbridge.sender.library.DebridSettingsScreen
import com.playbridge.sender.library.LibrarySettingsScreen
import com.playbridge.sender.cast.proxy.StreamProxySettingsScreen
import com.playbridge.sender.browser.PopupBlockerSettingsScreen
import com.playbridge.sender.cast.StreamingSettingsScreen
import com.playbridge.sender.cast.TVSettingsScreen
import com.playbridge.sender.diagnostics.LogsScreen
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private sealed class SettingsSection {
    data object Hub : SettingsSection()
    data object Appearance : SettingsSection()
    data object Library : SettingsSection()
    data object Debrid : SettingsSection()
    data object Proxy : SettingsSection()
    data object Streaming : SettingsSection()
    data object TV : SettingsSection()
    data object ImportExport : SettingsSection()
    data object PopupBlocker : SettingsSection()
    data object Logs : SettingsSection()
}

/** One row or a category header in the settings hub list. */
private sealed class SettingsHubRow {
    data class Header(val title: String) : SettingsHubRow()
    data class Item(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val onClick: () -> Unit,
    ) : SettingsHubRow()
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    tvIp: String? = null,
    tvPort: Int? = null,
    showBack: Boolean = true,
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
            onLogs = { section = SettingsSection.Logs },
            showBack = showBack,
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
        SettingsSection.Proxy -> StreamProxySettingsScreen(
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
        SettingsSection.Logs -> LogsScreen(
            onBack = { section = SettingsSection.Hub },
            tvIp = tvIp,
            tvPort = tvPort
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
    onLogs: () -> Unit,
    showBack: Boolean = true,
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
            // Theme label must be read in composable scope so the subtitle refreshes on change.
            val themeLabel = com.playbridge.sender.ui.theme.ThemeController.current().label
            val rows = remember(themeLabel) {
                buildList {
                    add(SettingsHubRow.Header("General"))
                    add(
                        SettingsHubRow.Item(
                            icon = Icons.Default.Palette,
                            title = "Appearance",
                            subtitle = "Theme: $themeLabel",
                            onClick = onAppearance,
                        )
                    )
                    add(
                        SettingsHubRow.Item(
                            icon = Icons.Default.SwapVert,
                            title = "Import / Export",
                            subtitle = "Backup and restore settings",
                            onClick = onImportExport,
                        )
                    )
                    add(
                        SettingsHubRow.Item(
                            icon = Icons.Default.Description,
                            title = "Logs",
                            subtitle = "View phone and TV logs for troubleshooting",
                            onClick = onLogs,
                        )
                    )

                    add(SettingsHubRow.Header("Browser"))
                    add(
                        SettingsHubRow.Item(
                            icon = Icons.Default.Block,
                            title = "Popup Blocker",
                            subtitle = "Block popups with per-site exceptions",
                            onClick = onPopupBlocker,
                        )
                    )

                    add(SettingsHubRow.Header("Library"))
                    add(
                        SettingsHubRow.Item(
                            icon = Icons.Default.VideoLibrary,
                            title = "Library",
                            subtitle = "Metadata API keys and display options",
                            onClick = onLibrary,
                        )
                    )
                    add(
                        SettingsHubRow.Item(
                            icon = Icons.Default.Tune,
                            title = "Streaming Preferences",
                            subtitle = "Audio, subtitles, and auto-select quality",
                            onClick = onStreaming,
                        )
                    )

                    add(SettingsHubRow.Header("Casting"))
                    add(
                        SettingsHubRow.Item(
                            icon = Icons.Default.Tv,
                            title = "TV",
                            subtitle = "Player defaults, background link, diagnostics",
                            onClick = onTV,
                        )
                    )
                    add(
                        SettingsHubRow.Item(
                            icon = Icons.Default.SwapHoriz,
                            title = "Stream proxy",
                            subtitle = "Default cast route and remote stream-proxy URL",
                            onClick = onProxy,
                        )
                    )
                    if (com.playbridge.sender.FlavorConfig.DEBRID_SUPPORTED) {
                        add(
                            SettingsHubRow.Item(
                                icon = Icons.Default.Cloud,
                                title = "Debrid",
                                subtitle = "Real-Debrid, All-Debrid, Premiumize, TorBox",
                                onClick = onDebrid,
                            )
                        )
                    }
                }
            }

            rows.forEachIndexed { index, row ->
                when (row) {
                    is SettingsHubRow.Header -> {
                        if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    is SettingsHubRow.Item -> {
                        SettingsNavItem(
                            icon = row.icon,
                            title = row.title,
                            subtitle = row.subtitle,
                            onClick = row.onClick,
                        )
                        // Divider only between consecutive items (not before a header).
                        val next = rows.getOrNull(index + 1)
                        if (next is SettingsHubRow.Item) {
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }

            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (_: Exception) {
                    null
                }
            }
            if (versionName != null) {
                val updateChecker: com.playbridge.sender.update.UpdateChecker =
                    org.koin.compose.koinInject()
                val updateState by updateChecker.state.collectAsState()
                val checking = updateState is
                    com.playbridge.sender.update.UpdateState.Checking

                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !checking) { updateChecker.check(manual = true) }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (checking) "Checking for updates…" else "Version $versionName · Check for updates",
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
