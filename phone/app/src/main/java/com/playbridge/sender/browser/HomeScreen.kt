package com.playbridge.sender.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.history.BookmarkDao
import com.playbridge.sender.data.history.HistoryDao

data class TopSite(val title: String, val url: String, val iconUrl: String = "")

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    historyDao: HistoryDao,
    bookmarkDao: BookmarkDao
) {
    val bookmarks by bookmarkDao.getAll().collectAsState(initial = emptyList())
    val recentHistory by historyDao.getAll().collectAsState(initial = emptyList())
    
    // Top visited sites (first 4 of recent history)
    val topSites = remember(recentHistory) {
        recentHistory.take(4).map { 
            TopSite(title = it.title.takeIf { title -> !title.isNullOrBlank() } ?: it.url, url = it.url) 
        }
    }
    
    // Remaining recent history 
    val recentItems = remember(recentHistory) { 
        recentHistory.drop(4).take(10) 
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // Bookmarks Section
            if (bookmarks.isNotEmpty()) {
                item {
                    Text(
                        text = "Bookmarks",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(bookmarks) { bookmark ->
                            TopSiteItem(
                                site = TopSite(
                                    title = bookmark.title.takeIf { !it.isNullOrBlank() } ?: bookmark.url,
                                    url = bookmark.url
                                ),
                                onClick = { onNavigate(bookmark.url) },
                                isBookmark = true
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
            
            // Top Sites Section
            if (topSites.isNotEmpty()) {
                item {
                    Text(
                        text = "Top Visited",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        topSites.forEach { site ->
                            TopSiteItem(site = site, onClick = { onNavigate(site.url) })
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
            
            // Recent History Section
            if (recentItems.isNotEmpty()) {
                item {
                    Text(
                        text = "History",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(
                    count = recentItems.size,
                    key = { index -> recentItems[index].url }
                ) { index ->
                    val item = recentItems[index]

                    // Apply rounded corners only to top/bottom items if they are at the edge
                    val shape = when {
                        recentItems.size == 1 -> androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        index == 0 -> androidx.compose.foundation.shape.RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        index == recentItems.lastIndex -> androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                        else -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = shape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = { 
                                val displayText = item.title.takeIf { !it.isNullOrBlank() } ?: item.url
                                Text(displayText, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                            },
                            leadingContent = { Icon(Icons.Default.History, null) },
                            modifier = Modifier.clickable { onNavigate(item.url) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun TopSiteItem(site: TopSite, onClick: () -> Unit, isBookmark: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (isBookmark) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Bookmark",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = site.title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = site.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isEmpty() -> "about:blank"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("about:") -> trimmed
        trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
    }
}
