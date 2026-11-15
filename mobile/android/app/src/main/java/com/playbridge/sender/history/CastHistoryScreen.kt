package com.playbridge.sender.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.playbridge.sender.data.history.CommandHistoryEntity
import com.playbridge.sender.data.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

/**
 * Dashboard → Cast history. The list, the filters, the "Last watched" card, and the sheets for one
 * cast. Casting itself is done by the callbacks, which reuse the app's existing cast paths.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastHistoryScreen(
    old: List<CommandHistoryEntity>,
    target: TargetInfo,
    onMenuClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRemote: () -> Unit,
    onPlay: (id: String, resume: Boolean) -> Unit,
    onChooseWhere: (id: String) -> Unit,
    onQueue: (id: String) -> Unit,
    onOpenPage: (CastHistoryEntry, fresh: Boolean) -> Unit,
    onSaveToCollection: (id: String) -> Unit,
    onShare: (CastHistoryEntry) -> Unit,
    onRemove: (id: String) -> Unit,
    onOldClick: (CommandHistoryEntity) -> Unit,
    onOldSave: (CommandHistoryEntity) -> Unit,
    onOldRemove: (CommandHistoryEntity) -> Unit,
    onClearAll: () -> Unit,
) {
    val repo: CastHistoryRepository = koinInject()
    val settingsRepository: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val entries by repo.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by settingsRepository.castHistory.collectAsStateWithLifecycle(initialValue = CastHistorySettings())
    val checking by repo.checking.collectAsStateWithLifecycle()
    val playingId by repo.nowPlayingId.collectAsStateWithLifecycle()
    // Once a minute is enough for "5 h ago"; the per-second position lives in the playing row alone.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }

    // Open: when the setting is on, ask the sites about a few links.
    LaunchedEffect(Unit) { repo.checkLinksOnOpen() }

    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }

    val oldItems = remember(old) {
        old.map {
            HistoryItem.Old(it.id, it.title, it.url, isBrowser = it.commandType == "browser", atMs = it.timestamp)
        }
    }
    val items = remember(entries, oldItems) { mergeHistory(entries, oldItems) }
    val counts = remember(items, now, checking) { filterCounts(items, now, checking) }
    val rows = remember(items, filter, query, now, checking, playingId) {
        val ctx = RowContext(nowMs = now, playingId = playingId, checking = checking)
        items.filter { it.matches(filter, now, checking) && it.matchesQuery(query) }.map { buildRow(it, ctx) }
    }
    val groups = remember(rows, now) { groupByDay(rows, now) }
    val last = remember(entries, playingId) { lastWatched(entries, playingId) }
    val total = items.size

    // Back: close the search first; otherwise the host's back (Dashboard) applies.
    BackHandler(enabled = searching) { searching = false; query = "" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        TextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search history") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Column {
                            Text("Cast history")
                            Text(
                                "$total of ${settings.limit} kept",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search")
                        }
                    } else {
                        IconButton(onClick = onMenuClick) {
                            com.playbridge.sender.ui.DashboardBlocksIcon(modifier = Modifier.size(22.dp))
                        }
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search history") }
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "More: clear history, history settings")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                enabled = total > 0,
                                onClick = { menuOpen = false; confirmClear = true },
                            )
                            DropdownMenuItem(
                                text = { Text("History settings") },
                                onClick = { menuOpen = false; onOpenSettings() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (total == 0) {
            EmptyHistory(onOpenSettings, Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item(key = "filters") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(HistoryFilter.entries, key = { it.name }) { f ->
                        val n = counts[f] ?: 0
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(if (f == HistoryFilter.ALL) f.label else "${f.label} $n") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            if (filter == HistoryFilter.ALL && query.isBlank() && last != null) {
                item(key = "last-watched") {
                    LastWatchedCard(
                        entry = last, now = now,
                        onResume = { if (last.linkStatus(now) is LinkStatus.Expired) sheetId = last.id else onPlay(last.id, true) },
                        onMore = { sheetId = last.id },
                    )
                }
            }
            if (rows.isEmpty()) {
                item(key = "nothing") {
                    Text(
                        if (query.isNotBlank()) "Nothing in history matches “${query.trim()}”." else "Nothing here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            groups.forEach { group ->
                item(key = group.key) {
                    Text(
                        group.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                items(group.rows, key = { it.key }) { row ->
                    HistoryRowView(
                        row = row,
                        onClick = {
                            when (val item = row.item) {
                                is HistoryItem.Cast -> sheetId = item.entry.id
                                is HistoryItem.Old -> old.firstOrNull { it.id == item.id }?.let(onOldClick)
                            }
                        },
                        onTrailing = {
                            when (val item = row.item) {
                                is HistoryItem.Cast -> when (row.trailing) {
                                    RowTrailing.TryAgain -> onPlay(item.entry.id, false)
                                    RowTrailing.FreshLink -> onOpenPage(item.entry, true)
                                    RowTrailing.Remote -> onOpenRemote()
                                    else -> sheetId = item.entry.id
                                }
                                is HistoryItem.Old -> Unit
                            }
                        },
                        onOldSave = { id -> old.firstOrNull { it.id == id }?.let(onOldSave) },
                        onOldRemove = { id -> old.firstOrNull { it.id == id }?.let(onOldRemove) },
                        onOldCast = { id -> old.firstOrNull { it.id == id }?.let(onOldClick) },
                        playingLine = { PlayingLine(repo, row.item.let { (it as HistoryItem.Cast).entry.id }) },
                    )
                }
            }
        }
    }

    val sheetEntry = sheetId?.let { id -> entries.firstOrNull { it.id == id } }
    if (sheetEntry != null) {
        val close = { sheetId = null }
        val link = sheetEntry.linkStatus(now, sheetEntry.id in checking)
        if (link is LinkStatus.Expired) {
            ExpiredSheet(
                entry = sheetEntry, expiredAtMs = link.expiredAtMs, now = now,
                checking = sheetEntry.id in checking,
                onDismiss = close,
                onFreshLink = { close(); onOpenPage(sheetEntry, true) },
                onTryOld = { close(); onPlay(sheetEntry.id, true) },
                onCheckAgain = { scope.launch { repo.checkLink(sheetEntry.id, force = true) } },
                onRemove = { close(); onRemove(sheetEntry.id) },
            )
        } else {
            ActionsSheet(
                entry = sheetEntry, target = target, now = now,
                onDismiss = close,
                onAction = { action ->
                    close()
                    when (action) {
                        HistoryAction.Resume -> onPlay(sheetEntry.id, true)
                        HistoryAction.PlayFromStart -> onPlay(sheetEntry.id, false)
                        HistoryAction.ChooseWhere -> onChooseWhere(sheetEntry.id)
                        HistoryAction.AddToQueue -> onQueue(sheetEntry.id)
                        HistoryAction.OpenPage -> onOpenPage(sheetEntry, false)
                        HistoryAction.SaveToCollection -> onSaveToCollection(sheetEntry.id)
                        HistoryAction.Share -> onShare(sheetEntry)
                        HistoryAction.Remove -> onRemove(sheetEntry.id)
                    }
                },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cast history?") },
            text = { Text("Removes $total ${if (total == 1) "cast" else "casts"}, their pictures and where you stopped from this phone.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClearAll() }) { Text("Clear history") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

// ---- pieces ---------------------------------------------------------------------------------

@Composable
private fun EmptyHistory(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.History, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("No cast history yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Videos you cast show up here with where you stopped. Casts from bridged apps are left out. You can change that in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("History settings") }
    }
}

@Composable
private fun tileIcon(tile: TileKind): ImageVector = when (tile) {
    TileKind.Video -> Icons.Default.Movie
    TileKind.Audio -> Icons.Default.Audiotrack
    TileKind.Image -> Icons.Default.Image
    TileKind.Live -> Icons.Default.Tv
    TileKind.Failed -> Icons.Default.Warning
    TileKind.Page -> Icons.Default.Language
}

/** The picture, or a plain tile for the kind of media; badge and progress sit on top. */
@Composable
private fun Thumbnail(
    previewPath: String?, tile: TileKind, badge: String?, progress: Float?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (previewPath != null) {
            AsyncImage(
                model = File(previewPath), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                tileIcon(tile), null,
                tint = if (tile == TileKind.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                trackColor = Color.Black.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Error -> MaterialTheme.colorScheme.error
    StatusTone.Warning -> MaterialTheme.colorScheme.onSurface
    StatusTone.Playing -> MaterialTheme.colorScheme.primary
    StatusTone.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "6:48 of 14:48 · updating": the one place that follows the receiver's position each second. */
@Composable
private fun PlayingLine(repo: CastHistoryRepository, id: String) {
    val playing by repo.nowPlaying.collectAsStateWithLifecycle()
    val np = playing?.takeIf { it.id == id }
    val text = when {
        np == null -> "Playing"
        np.live -> "Live · ${if (np.paused) "paused" else "updating"}"
        np.durationMs > 0 -> "${formatClock(np.positionMs)} of ${formatClock(np.durationMs)} · ${if (np.paused) "paused" else "updating"}"
        else -> "${formatClock(np.positionMs)} · ${if (np.paused) "paused" else "updating"}"
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun HistoryRowView(
    row: HistoryRow,
    onClick: () -> Unit,
    onTrailing: () -> Unit,
    onOldSave: (Long) -> Unit,
    onOldRemove: (Long) -> Unit,
    onOldCast: (Long) -> Unit,
    playingLine: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = if (row.item is HistoryItem.Old) "Cast again" else "Show actions", onClick = onClick)
                .semantics { contentDescription = row.description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(
                row.previewPath, row.tile, row.badge, row.progress,
                Modifier.width(96.dp).aspectRatio(16f / 9f),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.playing) playingLine()
                row.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = toneColor(row.statusTone), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                row.link?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = toneColor(row.linkTone), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        when (row.trailing) {
            RowTrailing.Menu -> IconButton(onClick = onTrailing) { Icon(Icons.Default.MoreVert, "More actions for ${row.title}") }
            RowTrailing.TryAgain -> TextButton(onClick = onTrailing, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            RowTrailing.FreshLink -> IconButton(onClick = onTrailing) { Icon(Icons.Default.Refresh, "Get a fresh link for ${row.title}") }
            RowTrailing.Remote -> IconButton(onClick = onTrailing) { Icon(Icons.Default.SettingsRemote, "Open the remote") }
            RowTrailing.OldMenu -> {
                val old = row.item as HistoryItem.Old
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More actions for ${row.title}") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Text("Cast again") }, leadingIcon = { Icon(Icons.Default.Cast, null) },
                            onClick = { open = false; onOldCast(old.id) },
                        )
                        DropdownMenuItem(
                            text = { Text("Save to collection") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                            onClick = { open = false; onOldSave(old.id) },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from history") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                            onClick = { open = false; onOldRemove(old.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LastWatchedCard(entry: CastHistoryEntry, now: Long, onResume: () -> Unit, onMore: () -> Unit) {
    val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.host ?: "Untitled cast"
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box {
            Thumbnail(
                entry.previewPath, TileKind.Video, badge = null, progress = entry.progressFraction,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            Text(
                "Last watched",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            if (entry.durationMs > 0) {
                Text(
                    "${formatClock(entry.positionMs)} / ${formatClock(entry.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${castSubtitle(entry)} · ${formatAgo(entry.startedAtMs, now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onResume, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(resumeLabel(entry), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "More actions for $title") }
        }
    }
}

// ---- sheets ---------------------------------------------------------------------------------

@Composable
private fun SheetHeader(entry: CastHistoryEntry, lines: List<String>) {
    val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.host ?: "Untitled cast"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(entry.previewPath, if (entry.failed) TileKind.Failed else TileKind.Video, null, entry.progressFraction, Modifier.width(96.dp).aspectRatio(16f / 9f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SheetItem(
    icon: ImageVector, label: String, onClick: () -> Unit,
    subtitle: String? = null, destructive: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsSheet(
    entry: CastHistoryEntry, target: TargetInfo, now: Long,
    onDismiss: () -> Unit, onAction: (HistoryAction) -> Unit,
) {
    val actions = remember(entry, target) { actionsFor(entry, target) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetHeader(
                entry,
                listOfNotNull(
                    entry.host?.let { h -> if (entry.isPlaylistEntry()) "$h · Playlist" else h },
                    stoppedDetail(entry),
                    playedOnDetail(entry, now),
                ),
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            actions.forEachIndexed { i, action ->
                if (i > 0 && actions[i - 1].group != action.group) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                when (action) {
                    HistoryAction.Resume -> SheetItem(Icons.Default.PlayArrow, resumeLabel(entry), { onAction(action) }, resumeSubtitle(target))
                    HistoryAction.PlayFromStart -> SheetItem(Icons.Default.Replay, "Play from the start", { onAction(action) })
                    HistoryAction.ChooseWhere -> SheetItem(Icons.Default.Cast, "Choose where to play", { onAction(action) })
                    HistoryAction.AddToQueue -> SheetItem(Icons.Default.QueuePlayNext, "Add to queue", { onAction(action) })
                    HistoryAction.OpenPage -> SheetItem(Icons.Default.OpenInBrowser, "Open the page it came from", { onAction(action) })
                    HistoryAction.SaveToCollection -> SheetItem(Icons.AutoMirrored.Filled.PlaylistAdd, "Save to collection", { onAction(action) })
                    HistoryAction.Share -> SheetItem(Icons.Default.Share, "Share", { onAction(action) })
                    HistoryAction.Remove -> SheetItem(Icons.Default.DeleteOutline, "Remove from history", { onAction(action) }, destructive = true)
                }
            }
        }
    }
}

private fun CastHistoryEntry.isPlaylistEntry() = playlistCount != null && playlistCount > 1

private val HistoryAction.group: Int
    get() = when (this) {
        HistoryAction.Resume, HistoryAction.PlayFromStart, HistoryAction.ChooseWhere, HistoryAction.AddToQueue -> 0
        HistoryAction.OpenPage, HistoryAction.SaveToCollection, HistoryAction.Share -> 1
        HistoryAction.Remove -> 2
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiredSheet(
    entry: CastHistoryEntry, expiredAtMs: Long?, now: Long, checking: Boolean,
    onDismiss: () -> Unit, onFreshLink: () -> Unit, onTryOld: () -> Unit,
    onCheckAgain: () -> Unit, onRemove: () -> Unit,
) {
    val actions = remember(entry) { expiredActionsFor(entry) }
    val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.host ?: "Untitled cast"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (entry.isPlaylistEntry()) "Playlist, ${(entry.playlistIndex ?: 0) + 1} of ${entry.playlistCount}" else null,
                        stoppedDetail(entry).replaceFirstChar { it.lowercase() }.takeIf { entry.isUnfinished() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                    .padding(12.dp),
            ) {
                Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(expiredNotice(expiredAtMs, now), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    Text(
                        "The site only lets a link work for a while. A fresh one comes from the page it was found on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ExpiredAction.FreshLink in actions) {
                Button(
                    onClick = onFreshLink,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open the page to get a fresh link")
                }
                Text(
                    freshLinkNote(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (ExpiredAction.TryOldLink in actions) SheetItem(Icons.Default.PlayArrow, "Try the old link anyway", onTryOld)
            if (ExpiredAction.CheckAgain in actions) {
                SheetItem(Icons.Default.Refresh, if (checking) "Checking the link…" else "Check again", { if (!checking) onCheckAgain() })
            }
            SheetItem(Icons.Default.DeleteOutline, "Remove from history", onRemove, destructive = true)
        }
    }
}
