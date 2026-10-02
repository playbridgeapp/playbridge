package com.playbridge.sender.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    sheetState: SheetState,
    isDesktopMode: Boolean,
    detectVideosEnabled: Boolean,
    pageHost: String = "",
    hasPage: Boolean = true,
    isBookmarked: Boolean = false,
    bookmarkStateReady: Boolean = true,
    onDismissRequest: () -> Unit,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onAddBookmarkClick: () -> Unit,
    canInstallBridgedApp: Boolean = false,
    onAddBridgedAppClick: () -> Unit = {},
    onFindInPageClick: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onMediaDetectionClick: () -> Unit,
    onSiteSettingsClick: () -> Unit = {},
    onContentBlockingClick: () -> Unit = {},
    onBrowserSettingsClick: () -> Unit = {},
    onFullScreenClick: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pageHost.ifBlank { "Browser" }, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismissRequest) { Icon(Icons.Default.Close, "Close menu") }
            }
            Row(Modifier.fillMaxWidth()) {
                MenuShortcut(if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                    if (isBookmarked) "Bookmarked" else "Bookmark", Modifier.weight(1f),
                    enabled = hasPage && bookmarkStateReady, selected = isBookmarked, onClick = onAddBookmarkClick)
                MenuShortcut(Icons.Default.Search, "Find in page", Modifier.weight(1f), hasPage, onClick = onFindInPageClick)
                MenuShortcut(Icons.Default.Fullscreen, "Full screen", Modifier.weight(1f), hasPage, onClick = onFullScreenClick)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            BrowserMenuRow(Icons.Default.PlayCircle, "Media detect", status = if (detectVideosEnabled) "On" else "Off", onClick = onMediaDetectionClick)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = isDesktopMode, enabled = hasPage, role = Role.Switch,
                    onValueChange = { onToggleDesktopMode() }).alpha(if (hasPage) 1f else 0.4f)
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("Desktop Site", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = isDesktopMode, onCheckedChange = null, enabled = hasPage)
            }
            BrowserMenuRow(Icons.Default.Tune, "Site Settings", enabled = hasPage, onClick = onSiteSettingsClick)
            BrowserMenuRow(Icons.Default.Shield, "Content Blocking", onClick = onContentBlockingClick)
            if (canInstallBridgedApp) BrowserMenuRow(Icons.Default.Apps, "Add Bridged App", onClick = onAddBridgedAppClick)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            BrowserMenuRow(Icons.Default.Bookmarks, "Bookmarks", onClick = onBookmarksClick)
            BrowserMenuRow(Icons.Default.History, "History", onClick = onHistoryClick)
            BrowserMenuRow(Icons.Default.Download, "Downloads", onClick = onDownloadsClick)
            BrowserMenuRow(Icons.Default.Settings, "Settings", onClick = onBrowserSettingsClick)
        }
    }
}

@Composable
internal fun BrowserMenuRow(icon: ImageVector, label: String, status: String? = null,
    enabled: Boolean = true, trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.KeyboardArrowRight, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else 0.4f).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (status != null) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        if (trailingIcon != null) Icon(trailingIcon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MenuShortcut(icon: ImageVector, label: String, modifier: Modifier,
    enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 64.dp).alpha(if (enabled) 1f else 0.4f).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp))
    }
}
