package com.playbridge.sender.downloads

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.PlayOnSheet
import com.playbridge.sender.cast.MediaKind
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.data.downloads.DownloadEntity
import com.playbridge.sender.downloads.engine.DownloadRepository
import com.playbridge.sender.downloads.engine.DownloadStatus
import com.playbridge.sender.player.PlayerLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import java.io.File

/* ------------------------------------------------------------------ *
 *  Status accents — one source of truth for colour + label + icon.   *
 * ------------------------------------------------------------------ */

private val AmberPaused = Color(0xFFF59E0B)

private data class Accent(val color: Color, val label: String, val icon: ImageVector)

@Composable
private fun accentFor(status: String): Accent = when (status) {
    DownloadStatus.RUNNING.name -> Accent(MaterialTheme.colorScheme.primary, "Downloading", Icons.Default.Downloading)
    DownloadStatus.QUEUED.name -> Accent(MaterialTheme.colorScheme.primary, "Waiting to start", Icons.Default.Downloading)
    DownloadStatus.MERGING.name -> Accent(MaterialTheme.colorScheme.tertiary, "Finalizing", Icons.Default.Movie)
    DownloadStatus.PAUSED.name -> Accent(AmberPaused, "Paused", Icons.Default.Pause)
    DownloadStatus.FAILED.name -> Accent(MaterialTheme.colorScheme.error, "Failed", Icons.Default.ErrorOutline)
    else -> Accent(MaterialTheme.colorScheme.outline, "", Icons.Default.Movie)
}

/* ------------------------------------------------------------------ *
 *  Screen                                                            *
 * ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenAllDevices: () -> Unit = {},
    onOpenRemote: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    val repository: DownloadRepository = koinInject()
    val connection: ConnectionViewModel = koinViewModel()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val downloads by repository.observe().collectAsStateWithLifecycle(initialValue = emptyList())

    // Where a tap plays: the selected receiver, or the in-app player ("This Device").
    val connectionState by connection.connectionState.collectAsStateWithLifecycle()
    val nativeDevice by connection.tvDevice.collectAsStateWithLifecycle(initialValue = null)
    val activeExternalDevice by connection.activeExternalDevice.collectAsStateWithLifecycle()
    val castRoute by connection.route.collectAsStateWithLifecycle()
    val hasCastTarget = activeExternalDevice != null ||
        (castRoute is CastSessionManager.Route.NativeTv &&
            connectionState is WebSocketClient.ConnectionState.Connected)

    // Speed per running download from successive Room ticks (~1 s), smoothed so the
    // speed and time-left labels don't jitter.
    val speedTracker = remember { mutableMapOf<String, Triple<Long, Long, Long>>() }
    val speeds = remember(downloads) {
        downloads.associate { e ->
            val prev = speedTracker[e.id]
            val speed = if (e.status == DownloadStatus.RUNNING.name && prev != null && e.updatedAt > prev.second) {
                val dt = (e.updatedAt - prev.second).coerceAtLeast(1L)
                val sample = ((e.bytesDownloaded - prev.first) * 1000L / dt).coerceAtLeast(0L)
                smoothSpeed(prev.third, sample)
            } else prev?.third ?: 0L
            if (e.status == DownloadStatus.RUNNING.name) speedTracker[e.id] = Triple(e.bytesDownloaded, e.updatedAt, speed)
            else speedTracker.remove(e.id)
            e.id to speed
        }
    }

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(DownloadSort.NEWEST.name) }
    val sort = DownloadSort.valueOf(sortName)
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DownloadEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<List<DownloadEntity>?>(null) }
    var confirmCancel by remember { mutableStateOf<DownloadEntity?>(null) }
    var castPickerFor by remember { mutableStateOf<DownloadEntity?>(null) }
    var pendingCast by remember { mutableStateOf<DownloadEntity?>(null) }
    var awaitingConsent by remember { mutableStateOf<List<DownloadEntity>>(emptyList()) }

    val sections = remember(downloads, query, sort) { downloadSections(downloads, query, sort) }
    val freeBytes = remember(sections.saved.size) { DownloadFiles.freeBytes() }
    val selecting = selection.isNotEmpty()

    // Drop selected ids that disappeared (deleted elsewhere, finished cancelling…).
    LaunchedEffect(downloads) {
        val ids = downloads.mapTo(HashSet()) { it.id }
        if (!ids.containsAll(selection)) selection = selection intersect ids
    }

    BackHandler(enabled = selecting || searchActive) {
        when {
            selecting -> selection = emptySet()
            else -> { searchActive = false; query = "" }
        }
    }

    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun mimeFor(item: DownloadEntity, stored: String): String =
        DownloadFiles.cached(stored)?.mimeType
            ?: runCatching { context.contentResolver.getType(stored.toUri()) }.getOrNull()
            ?: item.mimeType?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
            ?: "video/mp4"

    fun playLocally(item: DownloadEntity) {
        val stored = item.filePath ?: return
        val uri = DownloadFiles.uriOf(context, stored)
        PlayerLauncher.start(context, uri.toString(), item.title, mimeFor(item, stored))
    }

    fun castOrPlay(item: DownloadEntity) {
        val stored = item.filePath ?: return
        if (DownloadFiles.cached(stored)?.exists == false) {
            showMessage("This file is no longer on your phone")
            return
        }
        if (!hasCastTarget) {
            playLocally(item)
            return
        }
        val mime = mimeFor(item, stored)
        val kind = if (mime.startsWith("audio/")) MediaKind.AUDIO else MediaKind.VIDEO
        if (castRoute is CastSessionManager.Route.NativeTv &&
            nativeDevice?.supportsNativeMediaKind(kind.wireValue) == false
        ) {
            showMessage("This receiver does not support this media type")
            return
        }
        val ok = connection.castLocalFile(
            uriString = DownloadFiles.uriOf(context, stored).toString(),
            mime = mime,
            title = item.title,
            durationMs = DownloadFiles.cached(stored)?.durationMs ?: 0L,
            mediaKind = kind,
        )
        if (ok) {
            scope.launch {
                val result = snackbar.showSnackbar("Casting ${item.title}", actionLabel = "Remote", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) onOpenRemote()
            }
        } else {
            castPickerFor = item
        }
    }

    // After "Cast to…" picks a receiver, cast once it's ready (connections take a moment).
    LaunchedEffect(pendingCast, hasCastTarget) {
        val item = pendingCast ?: return@LaunchedEffect
        if (hasCastTarget) {
            pendingCast = null
            castOrPlay(item)
        } else {
            // Restarted (and so cancelled) as soon as the receiver becomes ready.
            delay(15_000)
            if (pendingCast == item) {
                pendingCast = null
                showMessage("Couldn't connect to the receiver")
            }
        }
    }

    fun removeFromList(item: DownloadEntity) {
        scope.launch {
            repository.removeFromList(item.id)
            val result = snackbar.showSnackbar("Removed from list", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) repository.restore(item)
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val items = awaitingConsent
        awaitingConsent = emptyList()
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                items.forEach { repository.removeFromList(it.id) }
                snackbar.showSnackbar(deletedMessage(items.size))
            }
        }
    }

    fun deleteFiles(items: List<DownloadEntity>) {
        scope.launch {
            var deleted = 0
            val needConsent = mutableListOf<DownloadEntity>()
            var singleConsent: android.content.IntentSender? = null
            var failed = 0
            for (item in items) {
                val stored = item.filePath
                if (stored == null) {
                    repository.cancel(item.id, removeFiles = true)
                    deleted++
                    continue
                }
                when (val r = DownloadFiles.delete(context, stored)) {
                    FileDeleteResult.Deleted -> { repository.removeFromList(item.id); deleted++ }
                    is FileDeleteResult.NeedsConsent -> { needConsent += item; singleConsent = r.request }
                    FileDeleteResult.Failed -> failed++
                }
            }
            if (needConsent.isNotEmpty()) {
                val sender = if (needConsent.size == 1) singleConsent
                else DownloadFiles.bulkDeleteRequest(context, needConsent.mapNotNull { it.filePath }) ?: singleConsent
                if (sender != null) {
                    awaitingConsent = needConsent
                    consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } else {
                    failed += needConsent.size
                }
            }
            when {
                failed > 0 -> snackbar.showSnackbar("Couldn't delete $failed ${if (failed == 1) "file" else "files"}")
                deleted > 0 -> snackbar.showSnackbar(deletedMessage(deleted))
            }
        }
    }

    /* ---------------------------- dialogs ---------------------------- */

    confirmCancel?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            icon = { Icon(Icons.Default.Close, null) },
            title = { Text("Cancel download?") },
            text = { Text("“${item.title}” will stop and its partial file will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.cancel(item.id, removeFiles = true) }
                    confirmCancel = null
                }) { Text("Cancel download", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Keep") } },
        )
    }

    confirmDelete?.let { items ->
        val single = items.singleOrNull()
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text(if (single != null) "Delete file?" else "Delete ${items.size} files?") },
            text = {
                Text(
                    if (single != null) "“${single.title}” will be deleted from your phone. This can't be undone."
                    else "These downloads will be deleted from your phone. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFiles(items)
                    selection = emptySet()
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    renaming?.let { item ->
        RenameDialog(
            initial = item.title,
            onDismiss = { renaming = null },
            onRename = { title ->
                renaming = null
                scope.launch {
                    repository.rename(item.id, title)
                    item.filePath?.let { DownloadFiles.rename(context, it, title) }
                }
            },
        )
    }

    castPickerFor?.let { item ->
        PlayOnSheet(
            onDismiss = { castPickerFor = null },
            showThisDevice = true,
            onPickedThisDevice = {
                castPickerFor = null
                playLocally(item)
            },
            onPickedDevice = {
                castPickerFor = null
                pendingCast = item
            },
        )
    }

    /* ---------------------------- layout ----------------------------- */

    Scaffold(
        topBar = {
            when {
                selecting -> TopAppBar(
                    title = { Text("${selection.size} selected", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") }
                    },
                    actions = {
                        val selectable = sections.saved + sections.failed
                        IconButton(onClick = { selection = selectable.mapTo(HashSet()) { it.id } }) {
                            Icon(Icons.Default.SelectAll, "Select all")
                        }
                        IconButton(onClick = {
                            confirmDelete = downloads.filter { it.id in selection }
                        }) { Icon(Icons.Default.Delete, "Delete selected") }
                    },
                )
                searchActive -> TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search downloads") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { searchActive = false; query = "" }) {
                            Icon(Icons.Default.Close, "Close search")
                        }
                    },
                )
                else -> TopAppBar(
                    title = { Text("Downloads", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (downloads.isNotEmpty()) {
                            IconButton(onClick = { searchActive = true }) { Icon(Icons.Default.Search, "Search") }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, "Sort")
                                }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                    DownloadSort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = { sortName = option.name; showSortMenu = false },
                                            trailingIcon = {
                                                if (option == sort) Icon(Icons.Default.Check, null)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
        // Lift snackbars above the overlaid now-playing bar.
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = Modifier.padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp,
                ),
            )
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                // Where a tap plays (and the Remote) is the shared now-playing bar at the bottom.
                Text(
                    buildString {
                        if (sections.inProgress.isNotEmpty()) append("${sections.inProgress.size} downloading · ")
                        append(librarySummary(sections.saved, freeBytes))
                    },
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (sections.isEmpty) {
                item(key = "no-results") {
                    Text(
                        "No downloads match “${query.trim()}”",
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (sections.inProgress.isNotEmpty()) {
                item(key = "h-progress") { SectionLabel("In progress", sections.inProgress.size) }
                items(sections.inProgress, key = { it.id }) { item ->
                    ActiveCard(
                        item = item,
                        detail = progressDetail(item, speeds[item.id] ?: 0L),
                        onPause = { repository.pause(item.id) },
                        onResume = { scope.launch { repository.resume(item.id) } },
                        onCancel = { confirmCancel = item },
                    )
                }
            }

            if (sections.failed.isNotEmpty()) {
                item(key = "h-failed") { SectionLabel("Couldn't download", sections.failed.size) }
                items(sections.failed, key = { it.id }) { item ->
                    FailedRow(
                        item = item,
                        selected = item.id in selection,
                        selecting = selecting,
                        onToggleSelect = { selection = selection.toggle(item.id) },
                        onRetry = { scope.launch { repository.resume(item.id) } },
                        source = downloadSource(item),
                        onOpenSource = onOpenUrl,
                        onRemove = { scope.launch { repository.cancel(item.id, removeFiles = true) } },
                    )
                }
            }

            if (sections.saved.isNotEmpty()) {
                item(key = "h-saved") { SectionLabel("Saved", sections.saved.size) }
                items(sections.saved, key = { it.id }) { item ->
                    SavedRow(
                        item = item,
                        castLabel = if (hasCastTarget) "Cast" else "Play",
                        selected = item.id in selection,
                        selecting = selecting,
                        onToggleSelect = { selection = selection.toggle(item.id) },
                        onPlay = { castOrPlay(item) },
                        actions = SavedActions(
                            onPlayHere = { playLocally(item) },
                            onCastTo = { castPickerFor = item },
                            onOpenWith = { item.filePath?.let { openInExternalPlayer(context, it, mimeFor(item, it)) } },
                            onShare = {
                                item.filePath?.let { stored ->
                                    runCatching { context.startActivity(DownloadFiles.shareIntent(context, stored, mimeFor(item, stored), item.title)) }
                                        .onFailure { showMessage("No app can share this file") }
                                }
                            },
                            onRename = { renaming = item },
                            source = downloadSource(item),
                            onOpenSource = onOpenUrl,
                            onShowInFolder = {
                                runCatching { context.startActivity(DownloadFiles.showInFilesIntent()) }
                                    .onFailure { showMessage("No file manager available") }
                            },
                            onRemoveFromList = { removeFromList(item) },
                            onDelete = { confirmDelete = listOf(item) },
                        ),
                    )
                }
            }
        }
    }
}

private fun sourceLabel(source: DownloadSource) = if (source.exactPage) "Open source page" else "Open website"

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

private fun deletedMessage(count: Int) = if (count == 1) "File deleted" else "$count files deleted"

/* ------------------------------------------------------------------ *
 *  Header + section label                                            *
 * ------------------------------------------------------------------ */

@Composable
private fun SectionLabel(text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

/* ------------------------------------------------------------------ *
 *  In-progress card                                                  *
 * ------------------------------------------------------------------ */

@Composable
private fun ActiveCard(
    item: DownloadEntity,
    detail: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val accent = accentFor(item.status)
    val indeterminate = item.status == DownloadStatus.MERGING.name ||
        item.status == DownloadStatus.QUEUED.name ||
        item.totalBytes <= 0
    val fraction = if (item.totalBytes > 0) (item.bytesDownloaded.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f

    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(accent.icon, accent.color)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (!indeterminate) "${accent.label} · ${(fraction * 100).toInt()}%" else accent.label,
                        color = accent.color,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                when (item.status) {
                    DownloadStatus.PAUSED.name -> IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, "Resume", tint = accent.color)
                    }
                    DownloadStatus.RUNNING.name -> IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, "Pause", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(end = 10.dp)) {
                if (indeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = accent.color,
                        trackColor = accent.color.copy(alpha = 0.16f),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = accent.color,
                        trackColor = accent.color.copy(alpha = 0.16f),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Failed row                                                        *
 * ------------------------------------------------------------------ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailedRow(
    item: DownloadEntity,
    selected: Boolean,
    selecting: Boolean,
    onToggleSelect: () -> Unit,
    onRetry: () -> Unit,
    source: DownloadSource?,
    onOpenSource: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    var menu by remember { mutableStateOf(false) }
    RowSurface(selected = selected) {
        Row(
            Modifier
                .combinedClickable(
                    onClick = { if (selecting) onToggleSelect() else onRetry() },
                    onLongClick = onToggleSelect,
                )
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) SelectMark(selected) else IconTile(Icons.Default.ErrorOutline, error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    item.errorReason?.takeIf { it.isNotBlank() } ?: "Download failed",
                    color = error,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selecting) {
                IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "Retry", tint = MaterialTheme.colorScheme.primary) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("Retry", Icons.Default.Refresh) { menu = false; onRetry() }
                        source?.let { MenuItem(sourceLabel(it), Icons.Default.Language) { menu = false; onOpenSource(it.url) } }
                        MenuItem("Remove", Icons.Default.PlaylistRemove) { menu = false; onRemove() }
                    }
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Saved row                                                         *
 * ------------------------------------------------------------------ */

private class SavedActions(
    val onPlayHere: () -> Unit,
    val onCastTo: () -> Unit,
    val onOpenWith: () -> Unit,
    val onShare: () -> Unit,
    val onRename: () -> Unit,
    val source: DownloadSource?,
    val onOpenSource: (String) -> Unit,
    val onShowInFolder: () -> Unit,
    val onRemoveFromList: () -> Unit,
    val onDelete: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedRow(
    item: DownloadEntity,
    castLabel: String,
    selected: Boolean,
    selecting: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit,
    actions: SavedActions,
) {
    val context = LocalContext.current
    val stored = item.filePath
    val info by produceState(initialValue = stored?.let { DownloadFiles.cached(it) }, stored) {
        if (stored != null) value = DownloadFiles.load(context, stored)
    }
    val missing = stored == null || info?.exists == false
    val now = remember { System.currentTimeMillis() }
    var menu by remember { mutableStateOf(false) }

    RowSurface(selected = selected) {
        Row(
            Modifier
                .combinedClickable(
                    onClickLabel = castLabel,
                    onClick = { if (selecting) onToggleSelect() else onPlay() },
                    onLongClick = onToggleSelect,
                )
                .padding(start = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(info = info, missing = missing, selecting = selecting, selected = selected)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
                Spacer(Modifier.height(4.dp))
                if (missing) {
                    Text("File not found", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                } else {
                    Text(
                        savedMetaLine(item, now),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selecting) {
                Spacer(Modifier.width(12.dp))
            } else {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!missing) {
                            MenuItem("Play on this phone", Icons.Default.Smartphone) { menu = false; actions.onPlayHere() }
                            MenuItem("Cast to…", Icons.Default.Cast) { menu = false; actions.onCastTo() }
                            MenuItem("Open with…", Icons.AutoMirrored.Filled.OpenInNew) { menu = false; actions.onOpenWith() }
                            MenuItem("Share", Icons.Default.Share) { menu = false; actions.onShare() }
                            MenuItem("Rename", Icons.Default.Edit) { menu = false; actions.onRename() }
                        }
                        actions.source?.let { MenuItem(sourceLabel(it), Icons.Default.Language) { menu = false; actions.onOpenSource(it.url) } }
                        if (!missing) {
                            MenuItem("Show in Downloads folder", Icons.Default.Folder) { menu = false; actions.onShowInFolder() }
                        }
                        HorizontalDivider()
                        MenuItem("Remove from list", Icons.Default.PlaylistRemove) { menu = false; actions.onRemoveFromList() }
                        if (!missing) {
                            MenuItem("Delete file", Icons.Default.DeleteOutline, MaterialTheme.colorScheme.error) {
                                menu = false; actions.onDelete()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(info: SavedFileInfo?, missing: Boolean, selecting: Boolean, selected: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(width = 120.dp, height = 68.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val thumb = info?.thumbnail
        when {
            thumb != null && !missing -> Image(
                bitmap = remember(thumb) { thumb.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            missing -> Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            else -> Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val duration = info?.durationMs?.let { formatDuration(it) }
        if (duration != null && !missing) {
            Text(
                duration,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (selecting) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (selected) 0.45f else 0.2f)),
                contentAlignment = Alignment.Center,
            ) { SelectMark(selected) }
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Reusable bits                                                     *
 * ------------------------------------------------------------------ */

@Composable
private fun RowSurface(selected: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        tonalElevation = 1.dp,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), shape) else Modifier),
        content = content,
    )
}

@Composable
private fun SelectMark(selected: Boolean) {
    if (selected) {
        Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp).background(Color.White, CircleShape))
    } else {
        Box(Modifier.size(26.dp).border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape))
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, color: Color = Color.Unspecified, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color) },
        leadingIcon = { Icon(icon, null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) },
        onClick = onClick,
    )
}

@Composable
private fun IconTile(icon: ImageVector, color: Color) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = color) }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val valid = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onRename(text.trim()) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Downloading, null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(20.dp))
            Text("No downloads yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Videos you save from the browser show up here, ready to play or cast.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------ *
 *  Helpers                                                            *
 * ------------------------------------------------------------------ */

/** Opens a finished download (content:// or file://) in an external player via chooser. */
fun openInExternalPlayer(context: Context, stored: String, mediaType: String?) {
    try {
        val parsed = stored.toUri()
        val mime = mediaType?.takeIf { it.isNotBlank() } ?: "video/*"
        val contentUri = if (parsed.scheme == "content") {
            parsed
        } else {
            val file = File(parsed.path ?: return)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "No player found for this file", Toast.LENGTH_SHORT).show()
    }
}
