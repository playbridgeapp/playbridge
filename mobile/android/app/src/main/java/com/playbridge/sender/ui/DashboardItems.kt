package com.playbridge.sender.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.playbridge.sender.FlavorConfig
import com.playbridge.sender.browser.BridgedApp
import com.playbridge.sender.browser.Screen

/** Screen mirror's tile reflects whether the chosen TV can mirror right now. */
internal sealed interface MirrorTileState {
    data object NeedsTv : MirrorTileState
    data object Ready : MirrorTileState
    data object Unsupported : MirrorTileState
    data object NeedsUpdate : MirrorTileState
    data class Live(val tvName: String) : MirrorTileState
}

/** The same support rules ScreenMirrorScreen applies, reduced to what the tile can say. */
internal fun mirrorTileState(
    mirroring: Boolean,
    tvChosen: Boolean,
    tvName: String,
    externalSelected: Boolean,
    externalSupported: Boolean,
    nativeConnected: Boolean,
    nativeSupported: Boolean,
): MirrorTileState = when {
    mirroring -> MirrorTileState.Live(tvName)
    !tvChosen -> MirrorTileState.NeedsTv
    externalSelected && !externalSupported -> MirrorTileState.Unsupported
    !externalSelected && nativeConnected && !nativeSupported -> MirrorTileState.NeedsUpdate
    else -> MirrorTileState.Ready
}

/**
 * Live status the Dashboard tiles show in their subtitles. A null field means "not known
 * yet": the tile then shows its neutral in-use subtitle instead of guessing "not set up".
 */
internal data class DashboardTileStates(
    val mediaAccess: Boolean? = null,
    val mirror: MirrorTileState = MirrorTileState.Ready,
    val iptvPlaylists: Int? = null,
    val collections: Int? = null,
    val debridLinked: Boolean? = null,
    val debridProvider: String? = null,
    val castHistoryEmpty: Boolean? = null,
    val lastCastTitle: String? = null,
)

/** Shared by the dashboard and JVM constructor regression tests. */
internal fun dashboardItems(
    states: DashboardTileStates = DashboardTileStates(),
    bridgedApps: List<BridgedApp> = emptyList(),
    debridSupported: Boolean = FlavorConfig.DEBRID_SUPPORTED,
): List<DashboardItem> {
    val builtInItems = listOf(
        DashboardItem(
            icon = Icons.Default.Language,
            title = "Browser",
            subtitle = "Find videos on the web",
            screen = Screen.Browser,
            gradientColors = listOf(Color(0xFF0D47A1), Color(0xFF1565C0)),
        ),
        DashboardItem(
            icon = Icons.Default.Folder,
            title = "Phone files",
            subtitle = if (states.mediaAccess == false) "Allow access" else "Videos, music, photos",
            screen = Screen.PhoneFiles,
            gradientColors = listOf(Color(0xFF311B92), Color(0xFF4527A0)),
        ),
        DashboardItem(
            icon = Icons.AutoMirrored.Filled.ScreenShare,
            title = "Screen mirror",
            subtitle = when (val mirror = states.mirror) {
                MirrorTileState.NeedsTv -> "Pick a TV first"
                MirrorTileState.Ready -> "Show this phone"
                MirrorTileState.Unsupported -> "Not on this TV"
                MirrorTileState.NeedsUpdate -> "TV needs an update"
                is MirrorTileState.Live -> mirror.tvName
            },
            screen = Screen.ScreenMirror,
            gradientColors = listOf(Color(0xFF005662), Color(0xFF00727F)),
            live = states.mirror is MirrorTileState.Live,
        ),
        DashboardItem(
            icon = Icons.Default.LiveTv,
            title = "IPTV",
            subtitle = countSubtitle(states.iptvPlaylists, "playlist", empty = "Add a playlist", unknown = "Live channels"),
            screen = Screen.Iptv,
            gradientColors = listOf(Color(0xFFA3300B), Color(0xFFBF360C)),
        ),
        DashboardItem(
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            title = "Collections",
            subtitle = countSubtitle(states.collections, "collection", empty = "Create your first", unknown = "Your playlists"),
            screen = Screen.Collections,
            gradientColors = listOf(Color(0xFF7B0D45), Color(0xFF9C1155)),
        ),
        DashboardItem(
            icon = Icons.Default.Cloud,
            title = "Debrid",
            subtitle = when {
                states.debridProvider != null -> states.debridProvider
                states.debridLinked == false -> "Link an account"
                else -> "Cloud files"
            },
            screen = Screen.DebridLibrary,
            gradientColors = listOf(Color(0xFF1B5E20), Color(0xFF2E7D32)),
        ),
        DashboardItem(
            icon = Icons.Default.History,
            title = "Cast history",
            subtitle = when {
                states.lastCastTitle != null -> "Last: ${states.lastCastTitle}"
                states.castHistoryEmpty == true -> "Nothing yet"
                else -> "Recent casts"
            },
            screen = Screen.CastHistory,
            gradientColors = listOf(Color(0xFF4E342E), Color(0xFF6D4C41)),
        ),
    ).filter {
        // Debrid is FOSS-only; the Play flavor hides the tile entirely.
        debridSupported || it.screen != Screen.DebridLibrary
    }

    return builtInItems + if (bridgedApps.isEmpty()) {
        listOf(DashboardItem(
            icon = Icons.Default.Add,
            title = "Add web app",
            subtitle = "From the Browser",
            screen = Screen.Browser,
            gradientColors = emptyList(),
            id = DashboardTileOrder.ADD_WEB_APP_ID,
        ))
    } else {
        bridgedApps.map { app ->
            DashboardItem(
                icon = Icons.Default.Apps,
                title = app.name,
                subtitle = "Web app",
                screen = Screen.Browser,
                gradientColors = if (app.origin == DashboardTileOrder.STREAMS_ORIGIN)
                    listOf(Color(0xFF004D40), Color(0xFF00695C))
                else
                    listOf(Color(0xFF263238), Color(0xFF37474F)),
                id = "app:${app.origin}",
                app = app,
            )
        }
    }
}

private fun countSubtitle(count: Int?, noun: String, empty: String, unknown: String): String = when (count) {
    null -> unknown
    0 -> empty
    1 -> "1 $noun"
    else -> "$count ${noun}s"
}

internal data class DashboardItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val screen: Screen?,
    /** Empty for the dashed "Add web app" placeholder. */
    val gradientColors: List<Color>,
    val id: String = when (screen) {
        Screen.Browser -> DashboardTileOrder.BROWSER_ID
        Screen.ScreenMirror -> "screen-mirror"
        Screen.PhoneFiles -> "phone-files"
        Screen.DebridLibrary -> "debrid"
        Screen.Iptv -> "iptv"
        Screen.Collections -> "collections"
        Screen.CastHistory -> "cast-history"
        else -> error("Unsupported dashboard destination")
    },
    val app: BridgedApp? = null,
    /** Screen mirror is sharing right now: the tile carries a LIVE badge. */
    val live: Boolean = false,
) {
    val isAddPlaceholder: Boolean get() = id == DashboardTileOrder.ADD_WEB_APP_ID
}
