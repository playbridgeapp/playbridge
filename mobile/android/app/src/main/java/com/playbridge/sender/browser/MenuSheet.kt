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

private const val MENU_COLUMNS = 5
private const val MENU_ROWS_PER_PAGE = 2
internal const val MENU_PAGE_SIZE = MENU_COLUMNS * MENU_ROWS_PER_PAGE

internal fun menuPageCount(itemCount: Int, pageSize: Int = MENU_PAGE_SIZE): Int {
    if (itemCount <= 0) return 1
    return (itemCount + pageSize - 1) / pageSize
}

/**
 * Hamburger menu as a horizontally paged bottom sheet (Firefox-style).
 * Items fill page 1 left-to-right, then overflow onto later pages once a
 * page hits [MENU_PAGE_SIZE] (2 rows of 5). Page dots only appear when
 * there is more than one page.
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
    val items = listOf(
        MenuAction(
            icon = Icons.Default.Bookmarks,
            label = "Bookmarks",
            onClick = onBookmarksClick
        ),
        MenuAction(
            icon = Icons.Default.Star,
            label = "Add Bookmark",
            onClick = onAddBookmarkClick
        ),
        MenuAction(
            icon = Icons.Default.History,
            label = "History",
            onClick = onHistoryClick
        ),
        MenuAction(
            icon = Icons.Default.Download,
            label = "Downloads",
            onClick = onDownloadsClick
        ),
        MenuAction(
            icon = Icons.Default.Search,
            label = "Find in Page",
            onClick = onFindInPageClick
        ),
        MenuAction(
            icon = Icons.Default.Devices,
            label = "Desktop Site",
            selected = isDesktopMode,
            onClick = onToggleDesktopMode
        ),
        MenuAction(
            icon = Icons.Default.PlayCircle,
            label = "Video Detect",
            selected = detectVideosEnabled,
            onClick = onToggleVideoDetect
        ),
        MenuAction(
            icon = Icons.Default.Language,
            label = "User Agent",
            selected = userAgentActive,
            onClick = onUserAgentClick
        ),
        MenuAction(
            icon = Icons.Default.Extension,
            label = "Extensions",
            onClick = onExtensionsClick
        ),
        // Settings lives on Dashboard (top-right gear) only.
        MenuAction(
            icon = Icons.Default.DeleteSweep,
            label = "Clear Data",
            tint = MaterialTheme.colorScheme.error,
            onClick = onClearDataClick
        )
    )
    val pages = items.chunked(MENU_PAGE_SIZE)
    val pageCount = menuPageCount(items.size)
    val pagerState = rememberPagerState(pageCount = { pageCount })
    // First page's measured height, applied as a min height to later pages so
    // the sheet stays the same size when swiping to a less-full overflow page.
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
                userScrollEnabled = pageCount > 1,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                val pageItems = pages.getOrElse(page) { emptyList() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (page == 0) {
                                Modifier.onSizeChanged { firstPageHeightPx = it.height }
                            } else {
                                Modifier.heightIn(min = with(density) { firstPageHeightPx.toDp() })
                            }
                        )
                ) {
                    pageItems.chunked(MENU_COLUMNS).forEach { rowItems ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            rowItems.forEach { item ->
                                MenuGridItem(
                                    icon = item.icon,
                                    label = item.label,
                                    selected = item.selected,
                                    tint = item.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    onClick = item.onClick
                                )
                            }
                            repeat(MENU_COLUMNS - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            if (pageCount > 1) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    repeat(pageCount) { index ->
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
}

private data class MenuAction(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val tint: Color? = null,
    val onClick: () -> Unit
)

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
