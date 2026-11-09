package com.playbridge.sender.settings

import com.playbridge.sender.cast.proxy.StreamProxySettingsScreen
import com.playbridge.sender.browser.PopupBlockerSettingsScreen
import com.playbridge.sender.browser.PageCastConsentSettingsScreen
import com.playbridge.sender.cast.TVSettingsScreen
import com.playbridge.sender.connection.TvLogsSource
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Subtitles
import com.playbridge.sender.cast.SubtitlePreferences
import com.playbridge.sender.cast.remote.RemotePresentationPreferenceStore
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.player.PhoneLandscapeOpen
import com.playbridge.sender.player.PhonePlayerMode
import org.koin.compose.koinInject
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.playbridge.sender.connection.ConnectionViewModel
import org.koin.androidx.compose.koinViewModel
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

private enum class SettingsSection {
    Hub, PhonePlayer, TV, AudioSubtitles, Casting, StreamProxy, WebsitePermissions,
    Browser, PopupBlocker, Remote, Appearance, Backup, Logs,
}

/** Pages opened from another page return there; the rest return to the hub. */
private val SettingsSection.parent: SettingsSection
    get() = when (this) {
        SettingsSection.StreamProxy, SettingsSection.WebsitePermissions -> SettingsSection.Casting
        SettingsSection.PopupBlocker -> SettingsSection.Browser
        else -> SettingsSection.Hub
    }

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    tvLogs: TvLogsSource? = null,
    showBack: Boolean = true,
) {
    var section by remember { mutableStateOf(SettingsSection.Hub) }
    val up = { section = section.parent }

    BackHandler(enabled = section != SettingsSection.Hub, onBack = up)

    when (section) {
        SettingsSection.Hub -> SettingsHubContent(onBack = onBack, onOpen = { section = it }, showBack = showBack)
        SettingsSection.PhonePlayer -> PhonePlayerSettingsScreen(onBack = up)
        SettingsSection.TV -> TVSettingsScreen(onBack = up)
        SettingsSection.AudioSubtitles -> AudioSubtitleSettingsScreen(onBack = up)
        SettingsSection.Casting -> CastingSettingsScreen(
            onBack = up,
            onStreamProxy = { section = SettingsSection.StreamProxy },
            onWebsitePermissions = { section = SettingsSection.WebsitePermissions },
        )
        SettingsSection.StreamProxy -> StreamProxySettingsScreen(onBack = up)
        SettingsSection.WebsitePermissions -> PageCastConsentSettingsScreen(onBack = up)
        SettingsSection.Browser -> BrowserSettingsScreen(onBack = up, onPopupBlocker = { section = SettingsSection.PopupBlocker })
        SettingsSection.PopupBlocker -> PopupBlockerSettingsScreen(onBack = up)
        SettingsSection.Remote -> RemoteSettingsScreen(onBack = up)
        SettingsSection.Appearance -> AppearanceSettingsScreen(onBack = up)
        SettingsSection.Backup -> ImportExportSettingsScreen(onBack = up)
        SettingsSection.Logs -> LogsScreen(onBack = up, tvLogs = tvLogs)
    }
}

/** One row of the hub, with a summary of the page's current values. */
private data class SettingsHubItem(
    val icon: ImageVector,
    val title: String,
    val summary: String,
    val section: SettingsSection,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHubContent(
    onBack: () -> Unit,
    onOpen: (SettingsSection) -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val connections: ConnectionViewModel = koinViewModel()
    val remotePreferences: RemotePresentationPreferenceStore = koinInject()

    val phoneMode by settings.phonePlayerMode.collectAsState(initial = PhonePlayerMode.AUTOMATIC)
    val landscapeOpen by settings.phoneLandscapeOpen.collectAsState(initial = PhoneLandscapeOpen.FULLSCREEN)
    val leaveAction by settings.phoneLeaveAction.collectAsState(initial = com.playbridge.sender.player.PhoneLeaveAction.PAUSE)
    val autoConnect by connections.autoConnectEnabled.collectAsState()
    val resolution by settings.defaultVideoQuality.collectAsState(initial = "Auto")
    val audio by settings.preferredAudioLang.collectAsState(initial = "")
    val subtitles by settings.preferredSubtitleLang.collectAsState(initial = "")
    val queueWindow by settings.tvPrefetchWindow.collectAsState(initial = 1)
    val blockPopups by settings.blockPopups.collectAsState(initial = true)
    val maxTabs by settings.maxAliveTabs.collectAsState(initial = 5)
    val remote by remotePreferences.state.collectAsState()
    val themeLabel = com.playbridge.sender.ui.theme.ThemeController.current().label

    val label = { options: List<Pair<String, String>>, code: String, fallback: String ->
        options.find { it.second == code }?.first ?: fallback
    }
    val topics = listOf(
        SettingsHubItem(
            Icons.Default.Smartphone, "Phone player",
            "${phoneMode.label} · ${landscapeOpen.label} · Leaving: ${leaveAction.label}",
            SettingsSection.PhonePlayer,
        ),
        SettingsHubItem(
            Icons.Default.Tv, "TV",
            "Auto-connect ${if (autoConnect) "on" else "off"} · ${label(SubtitlePreferences.videoQualities, resolution, "Auto")}",
            SettingsSection.TV,
        ),
        SettingsHubItem(
            Icons.Default.Subtitles, "Audio & subtitles",
            "Audio: ${label(SubtitlePreferences.audioLanguages, audio, "Auto")} · Subtitles: ${label(SubtitlePreferences.subtitleLanguages, subtitles, "None")}",
            SettingsSection.AudioSubtitles,
        ),
        SettingsHubItem(Icons.Default.Cast, "Casting", "Cast sheet, website queue ($queueWindow ahead), stream proxy", SettingsSection.Casting),
        SettingsHubItem(
            Icons.Default.Language, "Browser",
            "Popups ${if (blockPopups) "blocked" else "allowed"} · $maxTabs open tabs",
            SettingsSection.Browser,
        ),
        SettingsHubItem(
            Icons.Default.SettingsRemote, "Remote",
            if (remote.hapticsEnabled) "Haptics: ${remote.hapticsLevel.name}" else "Haptics off",
            SettingsSection.Remote,
        ),
    )
    val general = listOf(
        SettingsHubItem(Icons.Default.Palette, "Appearance", "Theme: $themeLabel", SettingsSection.Appearance),
        SettingsHubItem(Icons.Default.SwapVert, "Backup & restore", "Files and cloud backup", SettingsSection.Backup),
        SettingsHubItem(Icons.Default.Description, "Logs & diagnostics", "Phone and TV logs", SettingsSection.Logs),
    )

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
            listOf(null to topics, "General" to general).forEach { (header, items) ->
                if (header != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = header,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                items.forEachIndexed { index, item ->
                    SettingsNavItem(item.icon, item.title, item.summary) { onOpen(item.section) }
                    if (index < items.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
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
                    com.playbridge.shared.update.UpdateState.Checking

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
