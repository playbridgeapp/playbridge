package com.playbridge.sender.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.playbridge.sender.FlavorConfig
import com.playbridge.sender.browser.BridgedApp
import com.playbridge.sender.browser.Screen

internal fun dashboardPageCount(bridgedApps: List<BridgedApp>): Int =
    DashboardTileOrder.pageCount(dashboardItems(bridgedApps = bridgedApps).size)

/** Shared by the dashboard, its pager and JVM constructor regression tests. */
internal fun dashboardItems(
    isConnected: Boolean = false,
    remoteSubtitle: String = "Control your TV",
    bridgedApps: List<BridgedApp> = emptyList(),
    debridSupported: Boolean = FlavorConfig.DEBRID_SUPPORTED,
): List<DashboardItem> {
    val builtInItems = listOf(
        DashboardItem(
            icon = Icons.Default.Tv,
            title = "Remote",
            subtitle = remoteSubtitle,
            screen = Screen.Remote,
            gradientColors = listOf(Color(0xFFE65100), Color(0xFFFB8C00)),
        ),
        DashboardItem(
            icon = Icons.Default.Language,
            title = "Browser",
            subtitle = "Browse the web",
            screen = Screen.Browser,
            gradientColors = listOf(Color(0xFF1565C0), Color(0xFF1E88E5))
        ),
        DashboardItem(
            icon = Icons.AutoMirrored.Filled.LibraryBooks,
            title = "Library (legacy)",
            subtitle = "Deprecated · use the new Library",
            screen = Screen.Library,
            gradientColors = listOf(Color(0xFF6A1B9A), Color(0xFF8E24AA))
        ),
        DashboardItem(
            icon = Icons.Default.Tv,
            title = "Play on",
            subtitle = if (isConnected) "Connected" else "Not connected",
            screen = null,
            id = "connection", // Preserve the saved dashboard tile order.
            gradientColors = if (isConnected)
                listOf(Color(0xFF2E7D32), Color(0xFF43A047))
            else
                listOf(Color(0xFF424242), Color(0xFF616161))
        ),
        DashboardItem(
            icon = Icons.AutoMirrored.Filled.ScreenShare,
            title = "Screen Mirror",
            subtitle = "Share your screen",
            screen = Screen.ScreenMirror,
            gradientColors = listOf(Color(0xFF00695C), Color(0xFF00897B))
        ),
        DashboardItem(
            icon = Icons.Default.Folder,
            title = "Phone Files",
            subtitle = "Cast videos & audio",
            screen = Screen.PhoneFiles,
            gradientColors = listOf(Color(0xFF4527A0), Color(0xFF5E35B1))
        ),
        DashboardItem(
            icon = Icons.Default.Cloud,
            title = "Debrid",
            subtitle = "Cloud torrents",
            screen = Screen.DebridLibrary,
            gradientColors = listOf(Color(0xFF00838F), Color(0xFF00ACC1))
        ),
        DashboardItem(
            icon = Icons.Default.LiveTv,
            title = "IPTV",
            subtitle = "Live channels",
            screen = Screen.Iptv,
            gradientColors = listOf(Color(0xFF00695C), Color(0xFF00897B))
        ),
        DashboardItem(
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            title = "Collections",
            subtitle = "Your playlists",
            screen = Screen.Collections,
            gradientColors = listOf(Color(0xFFAD1457), Color(0xFFD81B60))
        ),
    ).filter {
        // Debrid is FOSS-only; the Play flavor hides the tile entirely.
        debridSupported || it.screen != Screen.DebridLibrary
    }

    return builtInItems + DashboardItem(
        icon = Icons.Default.History,
        title = "Cast History",
        subtitle = "Recent casts",
        screen = Screen.CastHistory,
        gradientColors = listOf(Color(0xFFE65100), Color(0xFFFB8C00)),
    ) + if (bridgedApps.isEmpty()) {
        listOf(DashboardItem(
            icon = Icons.Default.Apps,
            title = "Bridged Apps",
            subtitle = "Open Browser to add",
            screen = Screen.Browser,
            gradientColors = listOf(Color(0xFF00695C), Color(0xFF00897B)),
            id = "bridged-apps",
        ))
    } else {
        bridgedApps.map { app ->
            DashboardItem(
                icon = Icons.Default.Apps,
                title = app.name,
                subtitle = "Bridged App",
                screen = Screen.Browser,
                gradientColors = listOf(Color(0xFF00695C), Color(0xFF00897B)),
                id = "app:${app.origin}",
                app = app,
            )
        }
    }
}

internal data class DashboardItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val screen: Screen?,
    val gradientColors: List<Color>,
    val id: String = when (screen) {
        Screen.Remote -> "remote"
        Screen.Browser -> "browser"
        Screen.Library -> "library"
        Screen.ScreenMirror -> "screen-mirror"
        Screen.PhoneFiles -> "phone-files"
        Screen.DebridLibrary -> "debrid"
        Screen.Iptv -> "iptv"
        Screen.Collections -> "collections"
        Screen.CastHistory -> "cast-history"
        else -> error("Unsupported dashboard destination")
    },
    val app: BridgedApp? = null,
)
