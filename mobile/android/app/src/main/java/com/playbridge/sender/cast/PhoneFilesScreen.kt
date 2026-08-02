package com.playbridge.sender.cast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.playbridge.sender.R
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.player.PlayerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the Phone Files list is ordered. */
enum class SortKey { UNSORTED, NAME, SIZE, MODIFIED }

/**
 * Hoisted UI state for [PhoneFilesScreen]. Held by the navigation host (which stays in
 * composition) so the user's tab, search, sort, folder selection and scroll position survive
 * leaving and returning to the screen — e.g. round-tripping through the Remote screen, which
 * disposes the Phone Files content.
 */
class PhoneFilesUiState {
    var tab by mutableIntStateOf(0) // 0 = Videos, 1 = Audio
    var query by mutableStateOf("")
    var searchActive by mutableStateOf(false)
    var sortKey by mutableStateOf(SortKey.UNSORTED)
    var ascending by mutableStateOf(true)
    var selectedFolderId by mutableStateOf<Long?>(null)
    val listState = LazyListState()
    val folderScrollState = ScrollState(0)
}

/**
 * Phone Files: in-app Videos/Audio tabs listing on-device media (MediaStore) with thumbnails.
 * Each item is cast to the active target (DLNA renderer or native receiver) when one is
 * connected, or played in the in-app player ("This Device") when nothing is connected.
 *
 * The top bar offers a SAF file picker, search (filters the current tab), and a sort sheet;
 * a folder row under the tabs filters the current tab by containing folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneFilesScreen(
    viewModel: ConnectionViewModel,
    uiState: PhoneFilesUiState,
    onBack: () -> Unit,
    onOpenAllDevices: () -> Unit = {},
    onAddToCollection: (PhoneMediaItem) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }

    val connectionState by viewModel.connectionState.collectAsState()
    val activeExternalDevice by viewModel.activeExternalDevice.collectAsState()
    val castRoute by viewModel.route.collectAsState()
    val hasExternalTarget = activeExternalDevice != null ||
        (castRoute is CastSessionManager.Route.NativeTv &&
            connectionState is WebSocketClient.ConnectionState.Connected)

    val requiredPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    fun hasAnyPerm() = requiredPerms.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(hasAnyPerm()) }
    var allItems by remember { mutableStateOf<List<PhoneMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val videos = remember(allItems) { allItems.filter { !it.isAudio } }
    val audio = remember(allItems) { allItems.filter { it.isAudio } }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = it.values.any { v -> v } || hasAnyPerm() }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(requiredPerms) }
    LaunchedEffect(granted) {
        if (granted) {
            loading = true
            allItems = PhoneMediaStore.query(context)
            loading = false
        }
    }

    // Switch tab and reset the per-tab folder selection (folders differ per tab).
    fun selectTab(index: Int) {
        if (uiState.tab != index) {
            uiState.tab = index
            uiState.selectedFolderId = null
        }
    }

    // Play on the active external target, or in the in-app player ("This Device").
    fun playOrCast(media: PhoneMediaItem) {
        if (hasExternalTarget) {
            val ok = viewModel.castLocalFile(media.uri.toString(), media.mimeType, media.title, media.durationMs)
            if (ok) {
                scope.launch { snackbar.showSnackbar("Casting ${media.title}") }
            } else {
                // Lost the target between the check and the send — offer the picker.
                showDevicePicker = true
            }
        } else {
            // No cast target → play locally in the in-app player (no proxy needed).
            PlayerLauncher.start(context, media.uri.toString(), media.title, media.mimeType)
        }
    }

    // SAF picker: lets the user cast/play files MediaStore hasn't indexed.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read access so the proxy can still read the file when the TV fetches it.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mime = context.contentResolver.getType(uri)
            playOrCast(
                PhoneMediaItem(
                    uri = uri,
                    title = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "File",
                    durationMs = 0L,
                    mimeType = mime,
                    isAudio = mime?.startsWith("audio") == true,
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.searchActive) {
                        TextField(
                            value = uiState.query,
                            onValueChange = { uiState.query = it },
                            placeholder = { Text("Search ${if (uiState.tab == 0) "videos" else "audio"}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(),
                        )
                    } else {
                        Text("Phone Files")
                    }
                },
                navigationIcon = {
                    if (uiState.searchActive) {
                        IconButton(onClick = {
                            uiState.searchActive = false
                            uiState.query = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    } else {
                        // Dashboard (blocks) icon → Dashboard, matching the other top-level screens.
                        IconButton(onClick = onBack) {
                            com.playbridge.sender.ui.DashboardBlocksIcon(
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // OpenDocument takes mime filters; allow video + audio.
                        filePicker.launch(arrayOf("video/*", "audio/*"))
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Pick a file")
                    }
                    IconButton(onClick = {
                        uiState.searchActive = !uiState.searchActive
                        if (!uiState.searchActive) uiState.query = ""
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                },
            )
        },
        // Lift snackbars above the overlaid cast bar so they aren't hidden behind it.
        snackbarHost = {
            SnackbarHost(
                snackbar,
                modifier = Modifier.padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 80.dp
                ),
            )
        },
    ) { pad ->
        Box(modifier = Modifier.padding(pad).fillMaxSize()) {
            when {
                !granted -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "Permission needed to list your videos and audio.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(requiredPerms) }) { Text("Grant permission") }
                }

                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = uiState.tab) {
                        Tab(selected = uiState.tab == 0, onClick = { selectTab(0) }, text = { Text("Videos (${videos.size})") })
                        Tab(selected = uiState.tab == 1, onClick = { selectTab(1) }, text = { Text("Audio (${audio.size})") })
                    }

                    val base = if (uiState.tab == 0) videos else audio

                    // Distinct folders for the current tab (id → display name), preserving order.
                    val folders = remember(base) {
                        base.mapNotNull { item ->
                            item.bucketId?.let { id -> id to (item.bucketName ?: "Folder") }
                        }.distinctBy { it.first }
                    }
                    if (folders.isNotEmpty()) {
                        FolderRow(
                            folders = folders,
                            selectedId = uiState.selectedFolderId,
                            scrollState = uiState.folderScrollState,
                            onSelect = { uiState.selectedFolderId = it },
                        )
                    }

                    // Folder filter → search filter → sort.
                    val shown = remember(base, uiState.selectedFolderId, uiState.query, uiState.sortKey, uiState.ascending) {
                        val filtered = base.asSequence()
                            .filter { uiState.selectedFolderId == null || it.bucketId == uiState.selectedFolderId }
                            .filter { uiState.query.isBlank() || it.title.contains(uiState.query, ignoreCase = true) }
                            .toList()
                        val sorted = when (uiState.sortKey) {
                            SortKey.UNSORTED -> filtered.sortedByDescending { it.dateAdded }
                            SortKey.NAME -> filtered.sortedBy { it.title.lowercase() }
                            SortKey.SIZE -> filtered.sortedBy { it.sizeBytes }
                            SortKey.MODIFIED -> filtered.sortedBy { it.dateModified }
                        }
                        if (uiState.sortKey != SortKey.UNSORTED && !uiState.ascending) sorted.reversed() else sorted
                    }

                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    uiState.query.isNotBlank() -> "No matches."
                                    uiState.tab == 0 -> "No videos found."
                                    else -> "No audio found."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = uiState.listState,
                            modifier = Modifier.fillMaxSize(),
                            // Keep the last row clear of the system navigation bar and the
                            // overlaid cast bar.
                            contentPadding = PaddingValues(
                                bottom = WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding() + 84.dp
                            ),
                        ) {
                            items(shown) { media ->
                                PhoneMediaRow(
                                    media = media,
                                    onClick = { playOrCast(media) },
                                    onAddToCollection = { onAddToCollection(media) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSortSheet) {
        SortSheet(
            sortKey = uiState.sortKey,
            ascending = uiState.ascending,
            onSortKeyChange = { uiState.sortKey = it },
            onAscendingChange = { uiState.ascending = it },
            onDismiss = { showSortSheet = false },
        )
    }

    // Opened when a file is tapped but the cast target was lost mid-flight.
    if (showDevicePicker) {
        DeviceConnectionDialog(
            onDismiss = { showDevicePicker = false },
            onOpenAllDevices = {
                showDevicePicker = false
                onOpenAllDevices()
            },
            showThisDevice = true,
        )
    }
}

@Composable
private fun FolderRow(
    folders: List<Pair<Long, String>>,
    selectedId: Long?,
    scrollState: ScrollState,
    onSelect: (Long?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
        )
        folders.forEach { (id, name) ->
            FilterChip(
                selected = selectedId == id,
                onClick = { onSelect(id) },
                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    sortKey: SortKey,
    ascending: Boolean,
    onSortKeyChange: (SortKey) -> Unit,
    onAscendingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OptionRow("Unsorted", sortKey == SortKey.UNSORTED) { onSortKeyChange(SortKey.UNSORTED) }
            OptionRow("Name", sortKey == SortKey.NAME) { onSortKeyChange(SortKey.NAME) }
            OptionRow("Size", sortKey == SortKey.SIZE) { onSortKeyChange(SortKey.SIZE) }
            OptionRow("Modified date", sortKey == SortKey.MODIFIED) { onSortKeyChange(SortKey.MODIFIED) }

            Text(
                "Order",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            OptionRow("Ascending", ascending, enabled = sortKey != SortKey.UNSORTED) { onAscendingChange(true) }
            OptionRow("Descending", !ascending, enabled = sortKey != SortKey.UNSORTED) { onAscendingChange(false) }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun PhoneMediaRow(
    media: PhoneMediaItem,
    onClick: () -> Unit,
    onAddToCollection: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(media)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                media.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (media.durationMs > 0) {
                Text(
                    clockShort(media.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onAddToCollection) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = "Add to Collection",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Thumbnail (video frame / album art) loaded lazily via ContentResolver; icon fallback. */
@Composable
private fun MediaThumbnail(media: PhoneMediaItem) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, media.uri) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.contentResolver.loadThumbnail(media.uri, Size(128, 128), null) }.getOrNull()
            } else {
                null
            }
        }
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (media.isAudio) Icons.Default.Audiotrack else Icons.Default.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Resolves a human display name for a SAF content URI; null when unavailable. */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx) else null
        } else null
    }
}.getOrNull()

private fun clockShort(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
