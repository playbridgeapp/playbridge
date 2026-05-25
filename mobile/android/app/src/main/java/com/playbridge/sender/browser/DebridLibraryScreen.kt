package com.playbridge.sender.browser

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.playbridge.sender.R
import androidx.compose.foundation.Image
import com.playbridge.sender.data.debrid.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG_LIBRARY = "DebridLibraryScreen"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DebridLibraryScreen(
    onMenuClick: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onShowCastSheet: (DetectedVideo) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DebridRepository(context) }

    // ── Torrent list state ──────────────────────────────────────────────────
    var torrents by remember { mutableStateOf<List<DebridTorrentInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMorePages by remember { mutableStateOf(true) }
    var currentPage by remember { mutableStateOf(1) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ── Bottom sheet / info state ───────────────────────────────────────────
    var selectedTorrent by remember { mutableStateOf<DebridTorrentInfo?>(null) }
    var torrentDetails by remember { mutableStateOf<DebridTorrentInfo?>(null) }
    var isLoadingDetails by remember { mutableStateOf(false) }
    var unrestrictError by remember { mutableStateOf<String?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var infoTorrent by remember { mutableStateOf<DebridTorrentInfo?>(null) }

    // ── Selection mode ──────────────────────────────────────────────────────
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    var showActionMenu by remember { mutableStateOf(false) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    // ── Expand-in-place (tap to reveal full filename) ───────────────────────
    var expandedTorrentId by remember { mutableStateOf<String?>(null) }

    // ── Search ────────────────────────────────────────────────────────────────
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    // ── Provider switcher (tap the title to change active provider) ───────────
    var activeProviderName by remember { mutableStateOf(repository.getConfiguredProviderName()) }
    var showProviderSwitcher by remember { mutableStateOf(false) }
    var configuredProviders by remember { mutableStateOf(repository.getConfiguredProviders()) }

    // ── Derived list ────────────────────────────────────────────────────────
    val displayedTorrents = remember(torrents, searchQuery) {
        if (searchQuery.isBlank()) torrents
        else torrents.filter { it.filename.contains(searchQuery, ignoreCase = true) }
    }

    // ── Data functions ──────────────────────────────────────────────────────
    fun loadTorrents(isRefresh: Boolean = false) {
        scope.launch {
            if (isRefresh) isRefreshing = true else isLoading = true
            errorMessage = null
            currentPage = 1
            hasMorePages = true
            try {
                val provider = repository.getActiveProvider()
                if (provider == null) {
                    Log.w(TAG_LIBRARY, "loadTorrents: no active Debrid provider configured")
                    errorMessage = "No Debrid provider configured."
                } else {
                    Log.d(TAG_LIBRARY, "loadTorrents: calling getTorrents(page=1) on ${provider.name}")
                    val result = provider.getTorrents(page = 1)
                    Log.d(TAG_LIBRARY, "loadTorrents: got ${result.size} torrents")
                    torrents = result
                    if (result.isEmpty()) hasMorePages = false
                }
            } catch (e: Exception) {
                Log.e(TAG_LIBRARY, "loadTorrents: exception loading torrents", e)
                errorMessage = "Failed to load torrents: ${e.message}"
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    fun loadMoreTorrents() {
        scope.launch {
            isLoadingMore = true
            try {
                val provider = repository.getActiveProvider() ?: return@launch
                val nextPage = currentPage + 1
                Log.d(TAG_LIBRARY, "loadMoreTorrents: fetching page=$nextPage")
                val result = provider.getTorrents(page = nextPage)
                if (result.isEmpty()) {
                    hasMorePages = false
                } else {
                    torrents = torrents + result
                    currentPage = nextPage
                }
            } catch (e: Exception) {
                Log.e(TAG_LIBRARY, "loadMoreTorrents: exception on page ${currentPage + 1}", e)
            } finally {
                isLoadingMore = false
            }
        }
    }

    LaunchedEffect(Unit) { loadTorrents() }

    // ── Scaffold ─────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        isSelectionMode -> Text("${selectedIds.size} selected")
                        isSearching -> TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search torrents…") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                        )
                        else -> {
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        configuredProviders = repository.getConfiguredProviders()
                                        showProviderSwitcher = true
                                    }
                                ) {
                                    Text(
                                        if (activeProviderName == DebridRepository.PROVIDER_NONE)
                                            "Debrid Library"
                                        else
                                            activeProviderName
                                    )
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Switch provider"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showProviderSwitcher,
                                    onDismissRequest = { showProviderSwitcher = false }
                                ) {
                                    if (configuredProviders.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("No providers configured") },
                                            enabled = false,
                                            onClick = {}
                                        )
                                    } else {
                                        configuredProviders.forEach { provider ->
                                            DropdownMenuItem(
                                                text = { Text(provider) },
                                                leadingIcon = {
                                                    if (provider == activeProviderName) {
                                                        Icon(Icons.Default.Check, contentDescription = "Active")
                                                    } else {
                                                        Spacer(Modifier.size(24.dp))
                                                    }
                                                },
                                                onClick = {
                                                    showProviderSwitcher = false
                                                    if (provider != activeProviderName) {
                                                        repository.setActiveProvider(provider)
                                                        activeProviderName = provider
                                                        selectedIds = emptySet()
                                                        isSearching = false
                                                        searchQuery = ""
                                                        loadTorrents()
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    when {
                        isSelectionMode -> IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                        isSearching -> IconButton(onClick = {
                            isSearching = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Close search")
                        }
                        else -> IconButton(onClick = onMenuClick) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_playbridge_logo),
                                contentDescription = "PlayBridge",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                actions = {
                    when {
                        isSelectionMode -> {
                            Box {
                                IconButton(onClick = { showActionMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                                }
                                DropdownMenu(
                                    expanded = showActionMenu,
                                    onDismissRequest = { showActionMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Delete selected",
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            showActionMenu = false
                                            showBulkDeleteConfirm = true
                                        }
                                    )
                                }
                            }
                        }
                        isSearching -> {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                        else -> {
                            IconButton(onClick = {
                                isSearching = true
                                scope.launch { searchFocusRequester.requestFocus() }
                            }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Content ───────────────────────────────────────────────────────
            when {
                isLoading && torrents.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { loadTorrents() }) { Text("Retry") }
                        }
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { loadTorrents(isRefresh = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (displayedTorrents.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank())
                                        "No results for \"$searchQuery\""
                                    else
                                        "No recent torrents found.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            val listState = rememberLazyListState()
                            val shouldLoadMore by remember {
                                derivedStateOf {
                                    val lastVisible = listState.layoutInfo.visibleItemsInfo
                                        .lastOrNull()?.index ?: return@derivedStateOf false
                                    lastVisible >= listState.layoutInfo.totalItemsCount - 3
                                }
                            }
                            LaunchedEffect(shouldLoadMore) {
                                if (shouldLoadMore && hasMorePages && !isLoadingMore && !isLoading) {
                                    loadMoreTorrents()
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(displayedTorrents, key = { it.id }) { torrent ->
                                    val isExpanded = expandedTorrentId == torrent.id
                                    val isSelected = torrent.id in selectedIds

                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                text = torrent.filename,
                                                maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                "Status: ${torrent.status.name} · Progress: %.1f%%".format(torrent.progress),
                                                maxLines = 1
                                            )
                                        },
                                        leadingContent = {
                                            if (isSelectionMode) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { checked ->
                                                        selectedIds = if (checked)
                                                            selectedIds + torrent.id
                                                        else
                                                            selectedIds - torrent.id
                                                    }
                                                )
                                            } else {
                                                val statusIcon = when (torrent.status) {
                                                    TorrentStatus.READY -> Icons.Default.CheckCircle
                                                    TorrentStatus.DOWNLOADING -> Icons.Default.Download
                                                    TorrentStatus.ERROR -> Icons.Default.Error
                                                    else -> Icons.Default.HourglassEmpty
                                                }
                                                Icon(
                                                    statusIcon,
                                                    contentDescription = "Status: ${torrent.status.name}",
                                                    tint = if (torrent.status == TorrentStatus.READY)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        trailingContent = if (isSelectionMode) null else ({
                                            IconButton(onClick = { infoTorrent = torrent }) {
                                                Icon(
                                                    Icons.Default.Info,
                                                    contentDescription = "Info",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }),
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                if (isSelectionMode) {
                                                    selectedIds = if (isSelected)
                                                        selectedIds - torrent.id
                                                    else
                                                        selectedIds + torrent.id
                                                } else if (torrent.status == TorrentStatus.READY) {
                                                    selectedTorrent = torrent
                                                    showBottomSheet = true
                                                    scope.launch {
                                                        isLoadingDetails = true
                                                        unrestrictError = null
                                                        try {
                                                            val provider = repository.getActiveProvider()
                                                            if (provider != null) {
                                                                torrentDetails = provider.getTorrentInfo(torrent.id)
                                                            }
                                                        } catch (e: Exception) {
                                                            unrestrictError = e.message
                                                        } finally {
                                                            isLoadingDetails = false
                                                        }
                                                    }
                                                } else {
                                                    expandedTorrentId =
                                                        if (isExpanded) null else torrent.id
                                                }
                                            },
                                            onLongClick = {
                                                selectedIds = selectedIds + torrent.id
                                            }
                                        )
                                    )
                                    HorizontalDivider(thickness = 0.5.dp)
                                }

                                if (isLoadingMore) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Info dialog ───────────────────────────────────────────────────────────
    infoTorrent?.let { t ->
        AlertDialog(
            onDismissRequest = { infoTorrent = null },
            title = { Text("Torrent Info") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(t.filename, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider()
                    InfoRow("Status", t.status.name)
                    InfoRow("Progress", "%.1f%%".format(t.progress))
                    InfoRow("Size", formatBytes(t.bytes))
                    if (t.hash.isNotBlank()) InfoRow("Hash", t.hash)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.status == TorrentStatus.READY) {
                        Button(onClick = {
                            infoTorrent = null
                            selectedTorrent = t
                            showBottomSheet = true
                            scope.launch {
                                isLoadingDetails = true
                                unrestrictError = null
                                try {
                                    val provider = repository.getActiveProvider()
                                    if (provider != null) {
                                        torrentDetails = provider.getTorrentInfo(t.id)
                                    }
                                } catch (e: Exception) {
                                    unrestrictError = e.message
                                } finally {
                                    isLoadingDetails = false
                                }
                            }
                        }) {
                            Text("Browse Files")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { infoTorrent = null }) {
                    Text("Close")
                }
            }
        )
    }

    // ── Bulk delete confirmation ───────────────────────────────────────────────
    if (showBulkDeleteConfirm) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("Delete $count torrent${if (count != 1) "s" else ""}?") },
            text = { Text("This will permanently delete the selected torrent${if (count != 1) "s" else ""} from your Debrid cloud. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showBulkDeleteConfirm = false
                        val toDelete = selectedIds.toSet()
                        selectedIds = emptySet()
                        scope.launch {
                            val provider = repository.getActiveProvider() ?: return@launch
                            var anyFailed = false
                            for (id in toDelete) {
                                try {
                                    provider.deleteTorrent(id)
                                } catch (_: Exception) {
                                    anyFailed = true
                                }
                            }
                            torrents = torrents.filter { it.id !in toDelete }
                            if (anyFailed) {
                                Toast.makeText(context, "Some items could not be deleted.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // ── File picker bottom sheet ──────────────────────────────────────────────
    if (showBottomSheet && selectedTorrent != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var isResolvingAll by remember { mutableStateOf(false) }
        var unrestrictionError by remember { mutableStateOf<String?>(null) }
        var isReprocessing by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
                selectedTorrent = null
                torrentDetails = null
            },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(selectedTorrent!!.filename, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Spacer(Modifier.height(8.dp))

                if (isLoadingDetails) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
                    )
                } else if (unrestrictError != null) {
                    Text("Error loading details: $unrestrictError", color = MaterialTheme.colorScheme.error)
                } else if (torrentDetails != null) {
                    val files = torrentDetails!!.files.filter { it.isVideo || it.selected == 1 }
                    if (files.isEmpty()) {
                        Text("No playable files found.", modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        var fileSearch by remember { mutableStateOf("") }
                        val displayedFiles = remember(files, fileSearch) {
                            if (fileSearch.isBlank()) files
                            else files.filter {
                                it.path.substringAfterLast('/').contains(fileSearch, ignoreCase = true)
                            }
                        }

                        if (files.size > 1) {
                            Button(
                                onClick = {
                                    if (isResolvingAll) return@Button
                                    scope.launch {
                                        isResolvingAll = true
                                        try {
                                            Toast.makeText(context, "Resolving all links, please wait...", Toast.LENGTH_SHORT).show()
                                            val provider = repository.getActiveProvider() ?: return@launch
                                            val configName = repository.getConfiguredProviderName()
                                            val playlistItems = mutableListOf<playbridge.PlayPayload>()
                                            for (file in files) {
                                                val link = when (configName) {
                                                    DebridRepository.PROVIDER_PREMIUMIZE -> file.id
                                                    DebridRepository.PROVIDER_TORBOX -> "${torrentDetails!!.id}:${file.id}"
                                                    else -> if (file.link.isNotEmpty()) file.link else file.id
                                                }
                                                val unrestricted = provider.unrestrictLink(link)
                                                playlistItems.add(
                                                    playbridge.PlayPayload(
                                                        url = unrestricted.downloadUrl,
                                                        title = file.path.substringAfterLast('/')
                                                    )
                                                )
                                            }
                                            showBottomSheet = false
                                            onShowCastSheet(DetectedVideo(
                                                url = "playlist://debrid",
                                                tabId = -1,
                                                timestamp = System.currentTimeMillis(),
                                                isPlayable = true,
                                                contentType = null,
                                                detectedBy = "debrid_playlist",
                                                playlistPayload = playlistItems
                                            ))
                                        } catch (e: Exception) {
                                            unrestrictionError = e.message ?: "Unknown error"
                                        } finally {
                                            isResolvingAll = false
                                        }
                                    }
                                },
                                enabled = !isResolvingAll,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                if (isResolvingAll) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Resolving...")
                                } else {
                                    Icon(Icons.Default.List, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Play All on TV (${files.size} items)")
                                }
                            }
                        }

                        if (files.size > 5) {
                            OutlinedTextField(
                                value = fileSearch,
                                onValueChange = { fileSearch = it },
                                placeholder = { Text("Search files…") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                                trailingIcon = {
                                    if (fileSearch.isNotEmpty()) {
                                        IconButton(onClick = { fileSearch = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear")
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                shape = MaterialTheme.shapes.medium
                            )
                        }

                        var expandedFileId by remember { mutableStateOf<String?>(null) }

                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(displayedFiles, key = { it.id }) { file ->
                                val isExpanded = expandedFileId == file.id
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            file.path.substringAfterLast('/'),
                                            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            "${file.bytes / (1024 * 1024)} MB",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    leadingContent = {
                                        Icon(Icons.Default.Movie, contentDescription = null)
                                    },
                                    modifier = Modifier.clickable {
                                        expandedFileId = if (isExpanded) null else file.id
                                    },
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = {
                                                scope.launch {
                                                    try {
                                                        Toast.makeText(context, "Resolving link...", Toast.LENGTH_SHORT).show()
                                                        val provider = repository.getActiveProvider() ?: return@launch
                                                        val configName = repository.getConfiguredProviderName()
                                                        val link = when (configName) {
                                                            DebridRepository.PROVIDER_PREMIUMIZE -> file.id
                                                            DebridRepository.PROVIDER_TORBOX -> "${torrentDetails!!.id}:${file.id}"
                                                            else -> if (file.link.isNotEmpty()) file.link else file.id
                                                        }
                                                        val unrestricted = provider.unrestrictLink(link)
                                                        onCopyUrl(unrestricted.downloadUrl)
                                                        showBottomSheet = false
                                                    } catch (e: Exception) {
                                                        unrestrictionError = e.message ?: "Unknown error"
                                                    }
                                                }
                                            }) {
                                                Icon(Icons.Default.ContentCopy, "Copy Link", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(onClick = {
                                                scope.launch {
                                                    try {
                                                        Toast.makeText(context, "Resolving link...", Toast.LENGTH_SHORT).show()
                                                        val provider = repository.getActiveProvider() ?: return@launch
                                                        val configName = repository.getConfiguredProviderName()
                                                        val link = when (configName) {
                                                            DebridRepository.PROVIDER_PREMIUMIZE -> file.id
                                                            DebridRepository.PROVIDER_TORBOX -> "${torrentDetails!!.id}:${file.id}"
                                                            else -> if (file.link.isNotEmpty()) file.link else file.id
                                                        }
                                                        val unrestricted = provider.unrestrictLink(link)
                                                        showBottomSheet = false
                                                        onShowCastSheet(DetectedVideo(
                                                            url = unrestricted.downloadUrl,
                                                            tabId = -1,
                                                            timestamp = System.currentTimeMillis(),
                                                            isPlayable = true,
                                                            contentType = null,
                                                            detectedBy = "debrid_library",
                                                            originalMessage = null
                                                        ))
                                                    } catch (e: Exception) {
                                                        unrestrictionError = e.message ?: "Unknown error"
                                                    }
                                                }
                                            }) {
                                                Icon(Icons.Default.Tv, "Play on TV", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        unrestrictionError?.let { errMsg ->
            AlertDialog(
                onDismissRequest = { if (!isReprocessing) unrestrictionError = null },
                title = { Text("Failed to Unrestrict Link") },
                text = {
                    Column {
                        Text(text = errMsg, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (isReprocessing)
                                "Re-selecting files and waiting for Debrid to cache them…"
                            else
                                "If the torrent is no longer cached, tap Reprocess to re-queue it on your Debrid provider.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isReprocessing) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !isReprocessing,
                        onClick = {
                            val torrent = selectedTorrent ?: return@Button
                            val details = torrentDetails ?: return@Button
                            scope.launch {
                                isReprocessing = true
                                try {
                                    val provider = repository.getActiveProvider() ?: return@launch
                                    val fileIds = details.files
                                        .filter { it.selected == 1 || it.isVideo }
                                        .map { it.id }
                                    provider.selectFiles(torrent.id, fileIds)
                                    var attempts = 0
                                    while (attempts < 30) {
                                        delay(3000)
                                        val info = provider.getTorrentInfo(torrent.id)
                                        when (info.status) {
                                            TorrentStatus.READY -> {
                                                torrentDetails = info
                                                unrestrictionError = null
                                                return@launch
                                            }
                                            TorrentStatus.ERROR -> {
                                                unrestrictionError = "Reprocess failed: torrent returned an error status on Debrid."
                                                return@launch
                                            }
                                            else -> attempts++
                                        }
                                    }
                                    unrestrictionError = "Reprocess timed out. The torrent may still be downloading — try again in a moment."
                                } catch (e: Exception) {
                                    unrestrictionError = "Reprocess error: ${e.message}"
                                } finally {
                                    isReprocessing = false
                                }
                            }
                        }
                    ) {
                        Text("Reprocess")
                    }
                },
                dismissButton = {
                    TextButton(enabled = !isReprocessing, onClick = { unrestrictionError = null }) {
                        Text("Dismiss")
                    }
                }
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "Unknown"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}
