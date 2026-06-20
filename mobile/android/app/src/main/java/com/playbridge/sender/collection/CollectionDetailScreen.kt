package com.playbridge.sender.collection
import androidx.core.net.toUri

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.data.collection.CollectionItemEntity
import com.playbridge.sender.data.collection.CollectionPlayRouter
import com.playbridge.sender.data.collection.CollectionRoute
import com.playbridge.sender.data.collection.CollectionSource
import com.playbridge.sender.data.iptv.decodeHeaders
import com.playbridge.sender.player.PlayerLauncher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    collectionId: Long,
    viewModel: CollectionsViewModel,
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Stable per-collection subscription — created once, not rebuilt on every recomposition.
    val itemsFlow = remember(collectionId) { viewModel.itemsFor(collectionId) }
    val items by itemsFlow.collectAsState(initial = emptyList())
    val collection = viewModel.collectionById(collectionId)
    val title = collection?.name ?: "Collection"
    var showAddItem by remember { mutableStateOf(false) }

    // Play a single item: cast to the active target, else fall back to the built-in player.
    fun playItem(item: CollectionItemEntity) {
        val headers = decodeHeaders(item.headersJson)
        val casted = when (CollectionPlayRouter.routeOf(item.kind)) {
            CollectionRoute.LOCAL ->
                connectionViewModel.castLocalFile(item.url, item.mimeType, item.title)
            CollectionRoute.WEB ->
                connectionViewModel.castWebStream(item.url, headers, item.title, item.mimeType)
        }
        if (casted) {
            scope.launch { snackbar.showSnackbar("Casting ${item.title}") }
        } else {
            PlayerLauncher.start(
                context = context,
                url = item.url,
                title = item.title,
                contentType = item.mimeType,
                headers = headers.ifEmpty { null },
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddItem = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add item")
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No items yet. Add a URL here, or use \"Add to Collection\" elsewhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                // Extra bottom padding so the floating now-playing bar doesn't cover the last row.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                    CollectionItemRow(
                        item = item,
                        isFirst = index == 0,
                        isLast = index == items.lastIndex,
                        onClick = { playItem(item) },
                        onRemove = { viewModel.removeItem(item) },
                        onMoveUp = { viewModel.moveItem(collectionId, item.id, up = true) },
                        onMoveDown = { viewModel.moveItem(collectionId, item.id, up = false) },
                    )
                }
            }
        }
    }

    if (showAddItem) {
        AddItemDialog(
            onConfirm = { name, url ->
                viewModel.addItem(collectionId, manualDraft(name, url)) { added ->
                    scope.launch {
                        snackbar.showSnackbar(if (added) "Added" else "Already in this collection")
                    }
                }
                showAddItem = false
            },
            onDismiss = { showAddItem = false },
        )
    }
}

@Composable
private fun CollectionItemRow(
    item: CollectionItemEntity,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                sourceIcon(item.sourceTag),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hostOf(item.url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "Play",
            tint = MaterialTheme.colorScheme.primary,
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!isFirst) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                        onClick = { menuOpen = false; onMoveUp() },
                    )
                }
                if (!isLast) {
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                        onClick = { menuOpen = false; onMoveDown() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun AddItemDialog(
    onConfirm: (name: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Video / stream URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onConfirm(name.trim(), url.trim()) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun sourceIcon(sourceTag: String?): ImageVector = when (sourceTag) {
    CollectionSource.IPTV -> Icons.Default.LiveTv
    CollectionSource.PHONE_FILE -> Icons.Default.Folder
    CollectionSource.DEBRID -> Icons.Default.Cloud
    CollectionSource.BROWSER -> Icons.Default.Language
    CollectionSource.HISTORY -> Icons.Default.History
    else -> Icons.Default.Link
}

private fun hostOf(url: String): String = runCatching {
    url.toUri().host ?: url
}.getOrDefault(url)
