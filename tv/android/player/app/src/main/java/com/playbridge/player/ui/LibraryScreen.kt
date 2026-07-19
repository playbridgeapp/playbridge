package com.playbridge.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.data.PlaybackHistoryItem
import com.playbridge.player.ui.theme.ThemedDialog
import kotlinx.coroutines.launch

internal data class LibrarySections(
    val continueWatching: List<PlaybackHistoryItem>,
    val recent: List<PlaybackHistoryItem>,
    val favorites: List<PlaybackHistoryItem>,
)

internal fun resumePositionForHistoryItem(item: PlaybackHistoryItem): Long? = item.position
    .takeIf {
        item.duration > 0L &&
            it >= 30_000L &&
            it * 100L < item.duration * 95L
    }

internal fun historyThumbnailCacheKey(item: PlaybackHistoryItem): String =
    "${item.thumbnailUrl}#${item.thumbnailRevision}"

internal fun buildLibrarySections(history: List<PlaybackHistoryItem>): LibrarySections {
    val recent = history.sortedByDescending { it.timestamp }
    return LibrarySections(
        continueWatching = recent.filter { resumePositionForHistoryItem(it) != null },
        recent = recent.filter { resumePositionForHistoryItem(it) == null },
        favorites = recent.filter(PlaybackHistoryItem::isFavorite),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    historyStore: HistoryStore,
    onPlayItem: (PlaybackHistoryItem) -> Unit,
) {
    val history by historyStore.history.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var actionsFor by remember { mutableStateOf<PlaybackHistoryItem?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    val sections = remember(history) { buildLibrarySections(history) }
    val recent = sections.recent
    val continueWatching = sections.continueWatching
    val favorites = sections.favorites

    Column(
        modifier = Modifier.fillMaxSize().padding(start = 32.dp, top = 40.dp, end = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Library", style = MaterialTheme.typography.displayMedium)
                Text(
                    "Resume playback, revisit recent casts, and manage favorites.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (history.any { !it.isFavorite }) {
                Button(onClick = { showClearConfirmation = true }) { Text("Clear history") }
            }
        }

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Your Library is empty. Connect a phone and cast something to get started.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(26.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
            ) {
                if (continueWatching.isNotEmpty()) item("continue") {
                    LibraryShelf("Continue watching", continueWatching, onPlayItem) { actionsFor = it }
                }
                if (recent.isNotEmpty()) item("recent") {
                    LibraryShelf("Recent", recent, onPlayItem) { actionsFor = it }
                }
                if (favorites.isNotEmpty()) item("favorites") {
                    LibraryShelf("Favorites", favorites, onPlayItem) { actionsFor = it }
                }
            }
        }
    }

    actionsFor?.let { item ->
        ThemedDialog(onDismissRequest = { actionsFor = null }) {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.width(460.dp)) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(item.title ?: "Library item", style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = { actionsFor = null; onPlayItem(item) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (item.position > 0) "Resume" else "Play")
                    }
                    Button(
                        onClick = {
                            actionsFor = null
                            scope.launch { historyStore.toggleFavorite(item.id) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (item.isFavorite) "Remove from favorites" else "Add to favorites") }
                    Button(
                        onClick = {
                            actionsFor = null
                            scope.launch { historyStore.removeItem(item.id) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Remove from Library") }
                    Button(onClick = { actionsFor = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
            }
        }
    }

    if (showClearConfirmation) {
        ThemedDialog(onDismissRequest = { showClearConfirmation = false }) {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.width(480.dp)) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Clear playback history?", style = MaterialTheme.typography.headlineSmall)
                    Text("Continue watching and Recent will be cleared. Favorites will remain.")
                    Button(onClick = { showClearConfirmation = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            showClearConfirmation = false
                            scope.launch { historyStore.clearHistory() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Clear history") }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryShelf(
    title: String,
    entries: List<PlaybackHistoryItem>,
    onPlayItem: (PlaybackHistoryItem) -> Unit,
    onActions: (PlaybackHistoryItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 8.dp,
                end = 48.dp,
                bottom = 8.dp,
            ),
        ) {
            items(entries, key = PlaybackHistoryItem::id) { item ->
                LibraryCard(item, { onPlayItem(item) }, { onActions(item) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryCard(item: PlaybackHistoryItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        scale = CardDefaults.scale(focusedScale = 1.06f),
        modifier = Modifier.width(270.dp).height(188.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(148.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (item.thumbnailUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.thumbnailUrl)
                            .memoryCacheKey(historyThumbnailCacheKey(item))
                            .diskCacheKey(historyThumbnailCacheKey(item))
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else Text("▶", style = MaterialTheme.typography.headlineMedium)
                if (item.duration > 0L) {
                    val progress = (item.position.toFloat() / item.duration).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(5.dp).align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(progress).height(5.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
                if (item.isFavorite) {
                    Text("♥", color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                }
            }
            Text(
                item.title ?: "Untitled",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}
