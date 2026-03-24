package com.playbridge.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.*
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.data.PlaybackHistoryItem
import com.playbridge.protocol.Command
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import coil3.request.crossfade
import com.playbridge.player.server.ServerService
import kotlinx.coroutines.launch
import java.io.File

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    historyStore: HistoryStore,
    deviceName: String,
    connectedCount: Int = 0,
    onNavigateToPairing: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onPlayItem: (PlaybackHistoryItem) -> Unit
) {
    val history by historyStore.history.collectAsState(initial = emptyList())
    
    val scope = rememberCoroutineScope()
    var selectedTabIndex by remember { mutableStateOf(0) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    val tabs = listOf("History", "Favorites")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (connectedCount > 0) {
                        if (connectedCount == 1) "1 device connected"
                        else "$connectedCount devices connected"
                    } else "Not connected",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (connectedCount > 0) Color(0xFF00FF88) else Color.Gray
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onNavigateToPairing) {
                        Text("Pair New Device")
                    }

                    Button(onClick = { showClearConfirmDialog = true }) {
                        Text("Clear History")
                    }
                    
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }

            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onFocus = { selectedTabIndex = index },
                        onClick = { selectedTabIndex = index }
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            val displayedHistory = remember(history, selectedTabIndex) {
                if (selectedTabIndex == 1) {
                    history.filter { it.isFavorite }
                } else {
                    history.filter { !it.isFavorite }
                }
            }

            if (displayedHistory.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedTabIndex == 1) "No favorites yet. Long press an item in History to favorite it." else "No history yet. Pair a phone and play a video!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(displayedHistory) { item ->
                        HistoryItemCard(
                            item = item,
                            onClick = { onPlayItem(item) },
                            onLongClick = {
                                scope.launch {
                                    historyStore.toggleFavorite(item.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showClearConfirmDialog = false }) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .background(Color(0xFF1E1E38), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Clear History", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Are you sure you want to clear your entire playback history? This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { showClearConfirmDialog = false }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            showClearConfirmDialog = false
                            scope.launch {
                                historyStore.clearHistory()
                            }
                        }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryItemCard(
    item: PlaybackHistoryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        scale = CardDefaults.scale(focusedScale = 1.0f),
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray.copy(alpha = 0.3f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Thumbnail area — borderless, full card height
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (item.thumbnailPath != null) {
                    coil3.compose.AsyncImage(
                        model = coil3.request.ImageRequest.Builder(LocalContext.current)
                            .data(File(item.thumbnailPath))
                            .memoryCacheKey("${item.thumbnailPath}_${item.timestamp}")
                            .diskCacheKey("${item.thumbnailPath}_${item.timestamp}")
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("▶", color = Color.White)
                }
            }

            Column(
                modifier = Modifier.weight(1f).padding(top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title ?: "Unknown Title",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = truncateUrl(item.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                if (item.duration > 0) {
                    val progress = item.position.toFloat() / item.duration
                    // Custom Progress Indicator
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(4.dp)
                            .background(Color.Gray.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            if (item.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Favorite",
                    tint = Color(0xFFFF4081),
                    modifier = Modifier.size(24.dp).padding(end = 16.dp)
                )
            }
        }
    }
}

fun truncateUrl(url: String, maxLength: Int = 50): String {
    if (url.length <= maxLength) return url
    val partLength = (maxLength - 3) / 2
    return "${url.take(partLength)}...${url.takeLast(partLength)}"
}
