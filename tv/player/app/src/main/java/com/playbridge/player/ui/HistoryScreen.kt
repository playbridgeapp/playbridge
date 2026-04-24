package com.playbridge.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.*
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.data.PlaybackHistoryItem
import com.playbridge.player.ui.components.HistoryItemCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyStore: HistoryStore,
    onPlayItem: (PlaybackHistoryItem) -> Unit
) {
    val history by historyStore.history.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val historyItems = remember(history) {
        history.filter { !it.isFavorite }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 48.dp, end = 48.dp, bottom = 24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (historyItems.isNotEmpty()) {
                    Button(
                        onClick = { showClearConfirmDialog = true },
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Text("Clear All")
                    }
                }
            }

            if (historyItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "History is empty. Cast a video to see it here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(historyItems) { item ->
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
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Clear History", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Are you sure you want to clear your entire playback history? This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
