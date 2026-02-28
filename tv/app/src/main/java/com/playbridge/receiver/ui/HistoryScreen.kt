package com.playbridge.receiver.ui

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
import com.playbridge.receiver.data.HistoryStore
import com.playbridge.receiver.data.PlaybackHistoryItem
import com.playbridge.protocol.Command
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import coil3.request.crossfade
import com.playbridge.receiver.server.ServerService
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyStore: HistoryStore,
    deviceName: String,
    onNavigateToPairing: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onPlayItem: (PlaybackHistoryItem) -> Unit
) {
    val history by historyStore.history.collectAsState(initial = emptyList())
    
    val scope = rememberCoroutineScope()

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
                    text = "Connected",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onNavigateToPairing) {
                        Text("Pair New Device")
                    }

                    Button(onClick = {
                        scope.launch {
                            historyStore.clearHistory()
                        }
                    }) {
                        Text("Clear History")
                    }
                    
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No history yet. Pair a phone and play a video!",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray
                    )
                }
            } else {
                
                // Recently Played Section
                Text(
                    text = "Recently Played",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(history) { item ->
                        HistoryItemCard(item = item, onClick = { onPlayItem(item) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HistoryItemCard(
    item: PlaybackHistoryItem,
    onClick: () -> Unit
) {
    Card(
        scale = CardDefaults.scale(focusedScale = 1.02f),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray.copy(alpha = 0.3f))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Thumbnail area
             Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.Black)
                     .aspectRatio(16f/9f),
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
                     // Placeholder
                     Text("▶", color = Color.White)
                 }
            }

            Column(
                modifier = Modifier.weight(1f),
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
        }
    }
}

fun truncateUrl(url: String, maxLength: Int = 50): String {
    if (url.length <= maxLength) return url
    val partLength = (maxLength - 3) / 2
    return "${url.take(partLength)}...${url.takeLast(partLength)}"
}
