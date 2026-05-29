package com.playbridge.sender.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    sheetState: SheetState,
    currentScreen: Screen,
    isDesktopMode: Boolean,
    detectVideosEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onAddBookmarkClick: () -> Unit,
    onFindInPageClick: () -> Unit,
    onExtensionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onToggleVideoDetect: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)
        ) {
            // Row 1: Bookmarks, History, Downloads, Add Bookmark, Find in Page
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuGridItem(
                    icon = Icons.Default.Bookmarks,
                    label = "Bookmarks",
                    modifier = Modifier.weight(1f),
                    onClick = onBookmarksClick
                )
                MenuGridItem(
                    icon = Icons.Default.History,
                    label = "History",
                    modifier = Modifier.weight(1f),
                    onClick = onHistoryClick
                )
                MenuGridItem(
                    icon = Icons.Default.Download,
                    label = "Downloads",
                    modifier = Modifier.weight(1f),
                    onClick = onDownloadsClick
                )
                MenuGridItem(
                    icon = Icons.Default.Star,
                    label = "Add Bookmark",
                    modifier = Modifier.weight(1f),
                    onClick = onAddBookmarkClick
                )
                MenuGridItem(
                    icon = Icons.Default.Search,
                    label = "Find in Page",
                    modifier = Modifier.weight(1f),
                    onClick = onFindInPageClick
                )
            }
            // Row 2: Extensions, Settings, Desktop Site, Video Detect
            Row(modifier = Modifier.fillMaxWidth()) {
                MenuGridItem(
                    icon = Icons.Default.Extension,
                    label = "Extensions",
                    modifier = Modifier.weight(1f),
                    onClick = onExtensionsClick
                )
                MenuGridItem(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    selected = currentScreen == Screen.Settings,
                    modifier = Modifier.weight(1f),
                    onClick = onSettingsClick
                )
                MenuGridItem(
                    icon = Icons.Default.Devices,
                    label = "Desktop Site",
                    selected = isDesktopMode,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleDesktopMode
                )
                MenuGridItem(
                    icon = Icons.Default.PlayCircle,
                    label = "Video Detect",
                    selected = detectVideosEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleVideoDetect
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MenuGridItem(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer 
                    else Color.Transparent
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary else tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else if (labelColor == Color.Green) labelColor else labelColor.copy(alpha = 0.7f),
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}
