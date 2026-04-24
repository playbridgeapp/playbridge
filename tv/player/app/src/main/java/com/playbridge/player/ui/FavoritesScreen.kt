package com.playbridge.player.ui

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
fun FavoritesScreen(
    historyStore: HistoryStore,
    onPlayItem: (PlaybackHistoryItem) -> Unit
) {
    val history by historyStore.history.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val favoriteItems = remember(history) {
        history.filter { it.isFavorite }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 48.dp, end = 48.dp, bottom = 24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
            // Header
            Text(
                text = "Favorites",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (favoriteItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No favorites yet. Long-press an item in History to mark it as favorite.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, end = 24.dp)
                ) {
                    items(items = favoriteItems) { item ->
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
}
