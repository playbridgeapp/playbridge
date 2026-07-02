package com.playbridge.sender.iptv

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.playbridge.sender.data.iptv.IptvPlaylistEntity
import com.playbridge.sender.data.iptv.IptvPlaylistSort
import com.playbridge.sender.data.iptv.IptvSourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvScreen(
    viewModel: IptvViewModel,
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
) {
    val playlists by viewModel.playlists.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<IptvPlaylistEntity?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<IptvPlaylistEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IPTV") },
                navigationIcon = {
                    // Top-level screen: blocks icon → Dashboard, matching the other main screens.
                    IconButton(onClick = onBack) {
                        com.playbridge.sender.ui.DashboardBlocksIcon(
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                    IconButton(onClick = { editTarget = null; showAddSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add playlist")
                    }
                },
            )
        },
    ) { padding ->
        if (playlists.isEmpty()) {
            EmptyIptv(
                modifier = Modifier.padding(padding),
                onAdd = { editTarget = null; showAddSheet = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                // Extra bottom padding so the floating now-playing bar doesn't cover the last card.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist.id) },
                        onEdit = { editTarget = playlist; showAddSheet = true },
                        onDelete = { deleteTarget = playlist },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        IptvAddEditSheet(
            existing = editTarget,
            onDismiss = { showAddSheet = false },
            onSaveUrl = { name, url ->
                val target = editTarget
                if (target == null) viewModel.addUrlPlaylist(name, url)
                else viewModel.editPlaylist(target.id, name, url, IptvSourceType.URL)
                showAddSheet = false
            },
            onSaveFile = { name, uri ->
                val target = editTarget
                if (target == null) viewModel.addFilePlaylist(name, uri)
                else viewModel.editPlaylist(target.id, name, uri, IptvSourceType.FILE)
                showAddSheet = false
            },
        )
    }

    if (showSortSheet) {
        IptvSortSheet(
            viewModel = viewModel,
            onDismiss = { showSortSheet = false },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove playlist?") },
            text = { Text("\"${target.name}\" and its cached channels will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(target)
                    deleteTarget = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyIptv(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.LiveTv,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("No IPTV playlists yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Add an M3U URL or file to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Add IPTV playlist")
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: IptvPlaylistEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${playlist.channelCount} channels · updated ${relativeTime(playlist.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IptvAddEditSheet(
    existing: IptvPlaylistEntity?,
    onDismiss: () -> Unit,
    onSaveUrl: (name: String, url: String) -> Unit,
    onSaveFile: (name: String, uri: String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember {
        mutableStateOf(if (existing?.sourceType == IptvSourceType.URL) existing.source else "")
    }
    var fileUri by remember {
        mutableStateOf(if (existing?.sourceType == IptvSourceType.FILE) existing.source else null)
    }
    var fileName by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            fileUri = uri.toString()
            fileName = queryDisplayName(context, uri) ?: uri.lastPathSegment
            url = "" // file source supersedes URL
        }
    }

    val canSave = name.isNotBlank() && (url.isNotBlank() || fileUri != null)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                if (existing == null) "Add IPTV playlist" else "Edit playlist",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; if (it.isNotBlank()) { fileUri = null; fileName = null } },
                label = { Text("IPTV address (M3U URL)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("or", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "audio/mpegurl", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(fileName ?: (fileUri?.let { "File selected" }) ?: "Select an M3U file")
            }
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                Button(
                    enabled = canSave,
                    onClick = {
                        val fu = fileUri
                        if (url.isNotBlank()) onSaveUrl(name.trim(), url.trim())
                        else if (fu != null) onSaveFile(name.trim(), fu)
                    },
                ) { Text("Save") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IptvSortSheet(
    viewModel: IptvViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val sortKey by viewModel.sortKey.collectAsState()
    val ascending by viewModel.sortAscending.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Sort by", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            RadioRow("Added date", sortKey == IptvPlaylistSort.ADDED_DATE.name) {
                viewModel.setSort(IptvPlaylistSort.ADDED_DATE.name)
            }
            RadioRow("Name", sortKey == IptvPlaylistSort.NAME.name) {
                viewModel.setSort(IptvPlaylistSort.NAME.name)
            }
            Spacer(Modifier.height(12.dp))
            Text("Order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            RadioRow("Ascending", ascending) { viewModel.setSortAscending(true) }
            RadioRow("Descending", !ascending) { viewModel.setSortAscending(false) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
}.getOrNull()

internal fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    if (diff < 0) return "just now"
    val mins = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
