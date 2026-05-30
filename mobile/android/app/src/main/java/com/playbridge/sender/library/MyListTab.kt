package com.playbridge.sender.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.library.WatchlistStatus

// ---------------------------------------------------------------------------
// Filter options for My List tab
// ---------------------------------------------------------------------------

private enum class ListFilter(val label: String, val status: WatchlistStatus?) {
    ALL("All", null),
    WATCHING("Watching", WatchlistStatus.WATCHING),
    PLAN_TO_WATCH("Plan to Watch", WatchlistStatus.PLAN_TO_WATCH),
    COMPLETED("Completed", WatchlistStatus.COMPLETED),
    ON_HOLD("On Hold", WatchlistStatus.ON_HOLD),
    DROPPED("Dropped", WatchlistStatus.DROPPED),
}

// ---------------------------------------------------------------------------
// My List tab root
// ---------------------------------------------------------------------------

/**
 * Full-screen "My List" tab. Shows all tracked items filtered by status,
 * in a 2-column poster grid. Long-press any card to open the TrackingSheet.
 *
 * @param watchlist          All tracked items (regardless of status) from the ViewModel.
 * @param newEpisodeTmdbIds  Set of TMDB IDs that have a new episode available.
 * @param contentPadding     Padding passed down from the Scaffold (top + bottom safe area).
 * @param onItemClick        Navigates to the detail screen.
 * @param onLongPress        Opens the TrackingSheet for that item.
 */
@Composable
fun MyListTab(
    watchlist: List<WatchlistEntity>,
    newEpisodeTmdbIds: Set<Int> = emptySet(),
    contentPadding: PaddingValues,
    onItemClick: (WatchlistEntity) -> Unit,
    onLongPress: (WatchlistEntity) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    var selectedFilter by remember { mutableStateOf(ListFilter.ALL) }

    val filtered = remember(watchlist, selectedFilter) {
        if (selectedFilter.status == null) {
            // All: Watching first, then Plan to Watch, On Hold, Completed, Dropped.
            // Within each group, most-recently-added first.
            watchlist.sortedWith(
                compareBy<WatchlistEntity> { statusSortOrder(it.status) }
                    .thenByDescending { it.addedAt }
            )
        } else {
            watchlist
                .filter { it.status == selectedFilter.status?.value }
                .sortedByDescending { it.addedAt }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Status filter chips
        ScrollableTabRow(
            selectedTabIndex = ListFilter.entries.indexOf(selectedFilter),
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            divider = {},
            modifier = Modifier.padding(
                top = contentPadding.calculateTopPadding() + 4.dp,
                bottom = 4.dp,
            ),
        ) {
            ListFilter.entries.forEach { filter ->
                val count = if (filter.status == null) watchlist.size
                            else watchlist.count { it.status == filter.status?.value }
                Tab(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    text = {
                        Text(
                            text = if (count > 0) "${filter.label} ($count)" else filter.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }

        if (watchlist.isEmpty()) {
            // Empty state — nothing tracked yet
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Nothing here yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Long-press any title to add it to your list",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            // Empty state for a specific status filter
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No ${selectedFilter.label.lowercase()} titles",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 80.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.tmdbId }) { entity ->
                    TrackingCard(
                        entity = entity,
                        hasNewEpisode = entity.mediaType == "tv" && entity.tmdbId in newEpisodeTmdbIds,
                        onClick = { onItemClick(entity) },
                        onLongPress = { onLongPress(entity) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Individual tracking card
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackingCard(
    entity: WatchlistEntity,
    hasNewEpisode: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val status = WatchlistStatus.from(entity.status)
    val statusColor = statusColor(status)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // Poster image
            AsyncImage(
                model = entity.posterUrl,
                contentDescription = entity.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // Bottom gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        )
                    )
            )

            // Status badge (top-left)
            Surface(
                shape = RoundedCornerShape(bottomEnd = 10.dp),
                color = statusColor.copy(alpha = 0.88f),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    text = status.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            // "New Episode" badge (top-right) — TV shows only
            if (hasNewEpisode) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 10.dp),
                    color = Color(0xFFE53935).copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        text = "NEW",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            // Bottom info block
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Title
                Text(
                    text = entity.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // Episode progress (TV shows in Watching)
                if (entity.mediaType == "tv" &&
                    status == WatchlistStatus.WATCHING &&
                    entity.seasonProgress != null &&
                    entity.episodeProgress != null
                ) {
                    Text(
                        text = "S${entity.seasonProgress} · E${entity.episodeProgress}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }

                // Star rating
                val stars = (entity.userRating ?: 0) / 2
                if (stars > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        repeat(stars) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(10.dp),
                            )
                        }
                        Text(
                            text = "${entity.userRating}/10",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@Composable
private fun statusColor(status: WatchlistStatus): Color = when (status) {
    WatchlistStatus.WATCHING      -> MaterialTheme.colorScheme.primary
    WatchlistStatus.COMPLETED     -> Color(0xFF4CAF50)
    WatchlistStatus.PLAN_TO_WATCH -> MaterialTheme.colorScheme.secondary
    WatchlistStatus.ON_HOLD       -> Color(0xFFFF9800)
    WatchlistStatus.DROPPED       -> MaterialTheme.colorScheme.error
}

/** Sort priority for the All view. Lower = shown first. */
private fun statusSortOrder(statusValue: String): Int = when (statusValue) {
    WatchlistStatus.WATCHING.value      -> 0
    WatchlistStatus.PLAN_TO_WATCH.value -> 1
    WatchlistStatus.ON_HOLD.value       -> 2
    WatchlistStatus.COMPLETED.value     -> 3
    WatchlistStatus.DROPPED.value       -> 4
    else                                -> 5
}
