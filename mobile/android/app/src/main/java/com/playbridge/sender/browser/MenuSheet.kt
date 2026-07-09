package com.playbridge.sender.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Hamburger menu as a horizontally paged bottom sheet (Firefox-style).
 * Page 1: everyday browsing actions. Page 2: app-level tools and the
 * destructive Clear Data action, kept off the first page so it can't be
 * fat-fingered. Swipe between pages; dots below indicate position.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MenuSheet(
    sheetState: SheetState,
    currentScreen: Screen,
    isDesktopMode: Boolean,
    detectVideosEnabled: Boolean,
    userAgentActive: Boolean = false,
    onDismissRequest: () -> Unit,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onAddBookmarkClick: () -> Unit,
    onFindInPageClick: () -> Unit,
    onExtensionsClick: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onToggleVideoDetect: () -> Unit,
    onUserAgentClick: () -> Unit = {},
    onClearDataClick: () -> Unit = {}
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    // Page 1's measured height, applied as a min height to page 2 so the sheet
    // keeps a stable size when swiping (page 2 has fewer rows). Page 1 is always
    // composed first (the sheet opens on it), so the height is known by the time
    // page 2 becomes visible.
    var firstPageHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

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
            HorizontalPager(
                state = pagerState,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { firstPageHeightPx = it.height }
                    ) {
                        // Row 1: bookmark pair first, then the other destinations
                        Row(modifier = Modifier.fillMaxWidth()) {
                            MenuGridItem(
                                icon = Icons.Default.Bookmarks,
                                label = "Bookmarks",
                                modifier = Modifier.weight(1f),
                                onClick = onBookmarksClick
                            )
                            MenuGridItem(
                                icon = Icons.Default.Star,
                                label = "Add Bookmark",
                                modifier = Modifier.weight(1f),
                                onClick = onAddBookmarkClick
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
                                icon = Icons.Default.Search,
                                label = "Find in Page",
                                modifier = Modifier.weight(1f),
                                onClick = onFindInPageClick
                            )
                        }
                        // Row 2: per-page toggles together, then app destinations
                        Row(modifier = Modifier.fillMaxWidth()) {
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
                            MenuGridItem(
                                icon = Icons.Default.Language,
                                label = "User Agent",
                                selected = userAgentActive,
                                modifier = Modifier.weight(1f),
                                onClick = onUserAgentClick
                            )
                            MenuGridItem(
                                icon = Icons.Default.Extension,
                                label = "Extensions",
                                modifier = Modifier.weight(1f),
                                onClick = onExtensionsClick
                            )
                            // Settings lives on Dashboard (top-right gear) only.
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    1 -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = with(density) { firstPageHeightPx.toDp() })
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            MenuGridItem(
                                icon = Icons.Default.DeleteSweep,
                                label = "Clear Data",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                                onClick = onClearDataClick
                            )
                            Spacer(modifier = Modifier.weight(4f))
                        }
                    }
                }
            }

            // Page indicator dots
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                repeat(2) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuGridItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
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
