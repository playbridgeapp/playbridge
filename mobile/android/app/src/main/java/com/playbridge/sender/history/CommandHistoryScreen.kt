package com.playbridge.sender.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.history.CommandHistoryEntity
import com.playbridge.sender.diagnostics.CastAttempt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.playbridge.sender.R
import androidx.compose.foundation.Image

private sealed interface CastHistoryRow {
    val timestamp: Long
    data class Attempt(val value: CastAttempt) : CastHistoryRow {
        override val timestamp: Long get() = value.startedAtMs
    }
    data class Legacy(val value: CommandHistoryEntity) : CastHistoryRow {
        override val timestamp: Long get() = value.timestamp
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastHistoryScreen(
    historyItems: List<CommandHistoryEntity>,
    attempts: List<CastAttempt>,
    replaySources: Map<String, CastReplaySource>,
    onMenuClick: () -> Unit,
    onItemClick: (CommandHistoryEntity) -> Unit,
    onShareAttempt: (CastAttempt) -> Unit,
    onRecastAttempt: (CastAttempt) -> Unit,
    onDeleteAttempt: (CastAttempt) -> Unit,
    onDelete: (CommandHistoryEntity) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
    onAddToCollection: (CommandHistoryEntity) -> Unit = {}
) {
    val rows = remember(historyItems, attempts) {
        (attempts.map { CastHistoryRow.Attempt(it) } + historyItems.map { CastHistoryRow.Legacy(it) })
            .sortedByDescending { it.timestamp }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cast History") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        com.playbridge.sender.ui.DashboardBlocksIcon(
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    if (historyItems.isNotEmpty() || attempts.isNotEmpty()) {
                        IconButton(onClick = onClearHistory) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear History")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (historyItems.isEmpty() && attempts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Tv,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No cast history yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = innerPadding,
                modifier = Modifier.fillMaxSize()
            ) {
                items(rows, key = {
                    when (it) {
                        is CastHistoryRow.Attempt -> "attempt-${it.value.id}"
                        is CastHistoryRow.Legacy -> "legacy-${it.value.id}"
                    }
                }) { row ->
                    when (row) {
                        is CastHistoryRow.Attempt -> CastAttemptHistoryItem(
                            attempt = row.value,
                            replaySource = replaySources[row.value.id],
                            onRecast = { onRecastAttempt(row.value) },
                            onShare = { onShareAttempt(row.value) },
                            onDelete = { onDeleteAttempt(row.value) },
                        )
                        is CastHistoryRow.Legacy -> CastHistoryItem(
                            item = row.value,
                            onClick = { onItemClick(row.value) },
                            onDelete = { onDelete(row.value) },
                            onAddToCollection = { onAddToCollection(row.value) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastAttemptHistoryItem(
    attempt: CastAttempt,
    replaySource: CastReplaySource?,
    onRecast: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(attempt.id) { mutableStateOf(false) }
    val date = remember(attempt.startedAtMs) {
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(attempt.startedAtMs))
    }
    ListItem(
        headlineContent = {
            Text(
                replaySource?.title?.takeIf { it.isNotBlank() }
                    ?: attempt.media.name.replace('_', ' ').lowercase()
                        .replaceFirstChar { it.titlecase(Locale.getDefault()) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val receiver = attempt.receiver.name.replace('_', ' ').lowercase()
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            Text("$date · $receiver · ${attempt.outcome.name.lowercase()}")
        },
        leadingContent = { Icon(if (replaySource != null) Icons.Default.PlayArrow else Icons.Default.Tv, contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Attempt options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (replaySource != null) {
                        DropdownMenuItem(
                            text = { Text("Cast again") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = { menuOpen = false; onRecast() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share diagnostics") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from history") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        },
        modifier = if (replaySource != null) Modifier.clickable(onClick = onRecast) else Modifier,
    )
}

@Composable
fun CastHistoryItem(
    item: CommandHistoryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onAddToCollection: () -> Unit = {}
) {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    val icon = if (item.commandType.lowercase() == "play") Icons.Default.PlayArrow else Icons.Default.OpenInBrowser

    ListItem(
        headlineContent = {
            Text(
                text = item.title ?: item.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = item.url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = dateFormat.format(Date(item.timestamp)) + " • " + item.commandType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Icon(icon, contentDescription = null)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onAddToCollection) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Add to Collection",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
