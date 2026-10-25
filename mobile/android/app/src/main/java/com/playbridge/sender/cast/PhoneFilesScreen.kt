package com.playbridge.sender.cast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.player.PlayerLauncher
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** What Phone Files may read for one kind of media. */
internal enum class MediaAccess { FULL, PICKED, NONE }

/**
 * Hoisted UI state for [PhoneFilesScreen]. Held by the navigation host (which stays in
 * composition) so the tab, search, sort, folder, open album and scroll positions survive
 * leaving and returning to the screen, e.g. round-tripping through the Remote.
 */
class PhoneFilesUiState {
    var tab by mutableStateOf(PhoneFilesTab.VIDEOS)
    var query by mutableStateOf("")
    var searchActive by mutableStateOf(false)
    var sort by mutableStateOf(SortKey.NEWEST)
    var videosAsList by mutableStateOf(false)
    var musicView by mutableStateOf(MusicView.SONGS)
    /** Album, artist or folder page open in Music, by [SongGroup.key]. */
    var openGroupKey by mutableStateOf<String?>(null)
    /** Selected items (content URIs) in pick order; non-empty means select mode. */
    var selection by mutableStateOf<List<String>>(emptyList())
    /** Last item this screen cast, for the ON TV badge. */
    var lastCastUri by mutableStateOf<String?>(null)
    val folderByTab = mutableStateMapOf<PhoneFilesTab, Long?>()
    internal val askedKinds = mutableStateMapOf<MediaKind, Boolean>()
    val videoGrid = LazyGridState()
    val photoGrid = LazyGridState()
    val musicList = LazyListState()
    val musicGrid = LazyGridState()
    val groupList = LazyListState()

    val selecting: Boolean get() = selection.isNotEmpty()
    var folderId: Long?
        get() = folderByTab[tab]
        set(value) { folderByTab[tab] = value }
}

/**
 * Phone Files (Phone Files canvas, boards 1–10): Videos, Music and Photos tabs over the phone's
 * MediaStore. Items play on the chosen TV through the local proxy, or on this phone when no TV
 * is chosen. The shared mini player (drawn by the nav host) shows what is playing.
 */
@Composable
fun PhoneFilesScreen(
    viewModel: ConnectionViewModel,
    uiState: PhoneFilesUiState,
    onBack: () -> Unit,
    onAddToCollection: (List<PhoneMediaItem>) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<PhoneMediaItem?>(null) }

    val connectionCoordinator: ConnectionCoordinator = koinInject()
    val connectionState by viewModel.connectionState.collectAsState()
    val nativeDevice by viewModel.tvDevice.collectAsState(initial = null)
    val activeExternalDevice by viewModel.activeExternalDevice.collectAsState()
    val castRoute by viewModel.route.collectAsState()
    val tvActiveContext by connectionCoordinator.tvActiveContext.collectAsState()
    val externalMediaTitle by viewModel.externalMediaTitle.collectAsState()
    val nativeConnected = castRoute is CastSessionManager.Route.NativeTv &&
        connectionState is WebSocketClient.ConnectionState.Connected
    val hasExternalTarget = activeExternalDevice != null || nativeConnected
    val tvName = activeExternalDevice?.playOnName ?: nativeDevice?.playOnName?.takeIf { nativeConnected }
    val tvPlaying = (nativeConnected && tvActiveContext == "player") ||
        (activeExternalDevice != null && externalMediaTitle != null)
    val onTvUri = uiState.lastCastUri.takeIf { tvPlaying }

    // ---- Access -------------------------------------------------------------------------
    val activity = remember(context) { context.findActivity() }
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun access(kind: MediaKind): MediaAccess = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL else MediaAccess.NONE
        granted(fullPermission(kind)) -> MediaAccess.FULL
        kind != MediaKind.AUDIO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PICKED
        else -> MediaAccess.NONE
    }

    var accessRevision by remember { mutableIntStateOf(0) }
    val accessByKind = remember(accessRevision) { MediaKind.entries.associateWith { access(it) } }
    val items = remember { mutableStateMapOf<MediaKind, List<PhoneMediaItem>>() }
    var loadingKind by remember { mutableStateOf<MediaKind?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { accessRevision++ }

    fun requestAccess(kind: MediaKind) {
        uiState.askedKinds[kind] = true
        permLauncher.launch(permissionsFor(kind))
    }

    /** Denied and Android won't ask again: the button opens app settings instead. */
    fun blocked(kind: MediaKind): Boolean =
        uiState.askedKinds[kind] == true && accessByKind[kind] == MediaAccess.NONE && activity != null &&
            permissionsFor(kind).none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    // Re-read access and media whenever the screen resumes (Settings, the system picker).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accessRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Ask once for the open tab's kind; other tabs ask when opened (board 10).
    LaunchedEffect(uiState.tab) {
        val kind = uiState.tab.kind
        if (access(kind) == MediaAccess.NONE && uiState.askedKinds[kind] != true) requestAccess(kind)
    }
    LaunchedEffect(accessByKind) {
        for (kind in MediaKind.entries) {
            if (accessByKind[kind] == MediaAccess.NONE) {
                items.remove(kind)
                continue
            }
            if (items[kind] == null) loadingKind = kind
            items[kind] = PhoneMediaStore.query(context, kind)
            if (loadingKind == kind) loadingKind = null
        }
    }

    // ---- Playback ------------------------------------------------------------------------
    var subtitle by remember { mutableStateOf<Pair<String, String>?>(null) } // url to file name

    fun playOnPhone(media: PhoneMediaItem) {
        if (media.mediaKind == MediaKind.IMAGE) {
            // The phone player is video/audio-only; images open in the system viewer.
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(media.uri, media.mimeType ?: "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }.onFailure { scope.launch { snackbar.showSnackbar("No image viewer is available") } }
        } else {
            PlayerLauncher.start(context, media.uri.toString(), media.title, media.mimeType)
        }
    }

    fun unsupportedOnTv(media: PhoneMediaItem): Boolean =
        castRoute is CastSessionManager.Route.NativeTv &&
            nativeDevice?.supportsNativeMediaKind(media.mediaKind.wireValue) == false

    fun playOnTv(media: PhoneMediaItem, subtitleUrl: String? = null) {
        if (unsupportedOnTv(media)) {
            scope.launch { snackbar.showSnackbar("${tvName ?: "This TV"} can't play this kind of file") }
            return
        }
        val ok = viewModel.castLocalFile(
            uriString = media.uri.toString(),
            mime = media.mimeType,
            title = media.title,
            durationMs = media.durationMs,
            mediaKind = media.mediaKind,
            artist = media.artist,
            album = media.album,
            trackNumber = media.trackNumber,
            subtitleUrls = listOfNotNull(subtitleUrl),
        )
        if (ok) {
            uiState.lastCastUri = media.uri.toString()
            val verb = if (media.mediaKind == MediaKind.IMAGE) "Showing" else "Playing"
            scope.launch { snackbar.showSnackbar("$verb on ${tvName ?: "TV"}") }
        } else {
            // Lost the target between the check and the send: offer the picker.
            showDevicePicker = true
        }
    }

    fun play(media: PhoneMediaItem) = if (hasExternalTarget) playOnTv(media) else playOnPhone(media)

    fun share(list: List<PhoneMediaItem>) {
        if (list.isEmpty()) return
        val intent = if (list.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, list[0].uri).setType(list[0].mimeType ?: "*/*")
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list.map { it.uri }))
                .setType("*/*")
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null),
            )
        }
    }

    // Browse other files (system picker): files MediaStore hasn't indexed.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // Persist read access so the proxy can still read the file when the TV fetches it.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val mime = context.contentResolver.getType(uri)
            detailsFor = PhoneMediaItem(
                uri = uri,
                title = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "File",
                durationMs = 0L,
                mimeType = mime,
                mediaKind = when {
                    mime?.startsWith("audio/") == true -> MediaKind.AUDIO
                    mime?.startsWith("image/") == true -> MediaKind.IMAGE
                    else -> MediaKind.VIDEO
                },
            )
        }
    }
    val browseOtherFiles = { filePicker.launch(arrayOf("video/*", "audio/*", "image/*")) }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) subtitle = publishLocalSubtitle(context, uri)
    }

    // ---- Derived lists -------------------------------------------------------------------
    val tabItems = items[uiState.tab.kind].orEmpty()
    val folders = remember(tabItems) { PhoneFilesModel.folders(tabItems) }
    val shown = remember(tabItems, uiState.folderId, uiState.query, uiState.sort) {
        PhoneFilesModel.visible(tabItems, uiState.folderId, uiState.query, uiState.sort)
    }
    val byUri = remember(items.toMap()) { items.values.flatten().associateBy { it.uri.toString() } }
    val selectedItems = uiState.selection.mapNotNull { byUri[it] }

    BackHandler(enabled = uiState.selecting || uiState.searchActive || uiState.openGroupKey != null) {
        when {
            uiState.selecting -> uiState.selection = emptyList()
            uiState.openGroupKey != null -> uiState.openGroupKey = null
            else -> {
                uiState.searchActive = false
                uiState.query = ""
            }
        }
    }

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Leaves room for the shared mini player, or for the selection bar in select mode.
    val listBottomPadding = bottomInset + if (uiState.selecting) 150.dp else 88.dp
    val callbacks = PhoneFilesCallbacks(
        onOpen = { media ->
            if (uiState.selecting) {
                uiState.selection = PhoneFilesModel.toggle(uiState.selection, media.uri.toString())
            } else {
                when (media.mediaKind) {
                    MediaKind.VIDEO -> detailsFor = media
                    else -> play(media)
                }
            }
        },
        onLongPress = { media -> uiState.selection = PhoneFilesModel.toggle(uiState.selection, media.uri.toString()) },
        onAddToCollection = { onAddToCollection(listOf(it)) },
        onPlayOnPhone = ::playOnPhone,
    )

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val group = uiState.openGroupKey
            when {
                uiState.selecting -> SelectionHeader(
                    count = uiState.selection.size,
                    onCancel = { uiState.selection = emptyList() },
                    onSelectAll = {
                        val visible = if (uiState.tab == PhoneFilesTab.MUSIC) {
                            PhoneFilesModel.sorted(
                                tabItems.filter { PhoneFilesModel.matches(it, uiState.query) },
                                uiState.sort,
                            )
                        } else shown
                        uiState.selection = (uiState.selection + visible.map { it.uri.toString() }).distinct()
                    },
                )
                group != null -> Unit // The group page draws its own header.
                uiState.searchActive -> SearchHeader(
                    query = uiState.query,
                    placeholder = "Search ${uiState.tab.label.lowercase()}",
                    onQueryChange = { uiState.query = it },
                    onClose = {
                        uiState.searchActive = false
                        uiState.query = ""
                    },
                )
                else -> PhoneFilesHeader(
                    onDashboard = onBack,
                    onBrowseOtherFiles = browseOtherFiles,
                    onSearch = { uiState.searchActive = true },
                    onSortAndView = { showSortSheet = true },
                )
            }

            if (group != null && uiState.tab == PhoneFilesTab.MUSIC) {
                val songs = items[MediaKind.AUDIO].orEmpty()
                val songGroup = remember(songs, group) {
                    (PhoneFilesModel.albums(songs) + PhoneFilesModel.artists(songs) + PhoneFilesModel.musicFolders(songs))
                        .firstOrNull { it.key == group }
                }
                if (songGroup == null) {
                    LaunchedEffect(group) { uiState.openGroupKey = null }
                } else {
                    SongGroupPage(
                        group = songGroup,
                        listState = uiState.groupList,
                        playingUri = onTvUri,
                        tvName = tvName,
                        selection = uiState.selection,
                        bottomPadding = listBottomPadding,
                        onBack = { uiState.openGroupKey = null },
                        callbacks = callbacks,
                    )
                }
                return@Column
            }

            MediaTabs(
                selected = uiState.tab,
                counts = PhoneFilesTab.entries.associateWith { tab ->
                    items[tab.kind]?.size.takeIf { accessByKind[tab.kind] != MediaAccess.NONE }
                },
                onSelect = { tab ->
                    if (uiState.tab != tab) {
                        uiState.tab = tab
                        uiState.selection = emptyList()
                    }
                },
            )

            val kind = uiState.tab.kind
            val tabAccess = accessByKind[kind] ?: MediaAccess.NONE
            when {
                tabAccess == MediaAccess.NONE -> AccessCard(
                    kind = kind,
                    blocked = blocked(kind),
                    onAllow = {
                        if (blocked(kind)) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        } else {
                            requestAccess(kind)
                        }
                    },
                    onBrowseOtherFiles = browseOtherFiles,
                )
                loadingKind == kind && tabItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    if (tabAccess == MediaAccess.PICKED) {
                        PickedAccessNote(onChange = { requestAccess(kind) })
                    }
                    when (uiState.tab) {
                        PhoneFilesTab.VIDEOS -> VideosTab(
                            uiState = uiState,
                            folders = folders,
                            videos = shown,
                            onTvUri = onTvUri,
                            bottomPadding = listBottomPadding,
                            callbacks = callbacks,
                        )
                        PhoneFilesTab.MUSIC -> MusicTab(
                            uiState = uiState,
                            songs = tabItems,
                            onTvUri = onTvUri,
                            bottomPadding = listBottomPadding,
                            callbacks = callbacks,
                        )
                        PhoneFilesTab.PHOTOS -> PhotosTab(
                            uiState = uiState,
                            folders = folders,
                            photos = shown,
                            onTvUri = onTvUri,
                            bottomPadding = listBottomPadding,
                            callbacks = callbacks,
                        )
                    }
                }
            }
        }

        if (uiState.selecting) {
            SelectionBar(
                selected = selectedItems,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset),
                onAddToCollection = {
                    onAddToCollection(selectedItems)
                    uiState.selection = emptyList()
                },
                onShare = { share(selectedItems) },
            )
        }

        SnackbarHost(
            snackbar,
            // Lift snackbars above the shared mini player.
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 80.dp),
        )
    }

    detailsFor?.let { media ->
        MediaDetailsSheet(
            media = media,
            tvName = tvName.takeIf { hasExternalTarget },
            unsupportedOnTv = unsupportedOnTv(media),
            subtitleName = subtitle?.second,
            onDismiss = {
                detailsFor = null
                subtitle = null
            },
            onPlayOnTv = {
                playOnTv(media, subtitle?.first)
                detailsFor = null
                subtitle = null
            },
            onPlayOnPhone = {
                playOnPhone(media)
                detailsFor = null
            },
            onPickTv = { showDevicePicker = true },
            onAddToCollection = { onAddToCollection(listOf(media)) },
            onShare = { share(listOf(media)) },
            onAddSubtitles = { subtitlePicker.launch(arrayOf("*/*")) },
            onRemoveSubtitles = { subtitle = null },
        )
    }

    if (showSortSheet) {
        SortAndViewSheet(
            tab = uiState.tab,
            sort = uiState.sort,
            videosAsList = uiState.videosAsList,
            onSort = { uiState.sort = it },
            onVideosAsList = { uiState.videosAsList = it },
            onBrowseOtherFiles = {
                showSortSheet = false
                browseOtherFiles()
            },
            onDismiss = { showSortSheet = false },
        )
    }

    if (showDevicePicker) {
        PlayOnSheet(onDismiss = { showDevicePicker = false }, showThisDevice = true)
    }
}

/** Item actions shared by every tab. */
internal class PhoneFilesCallbacks(
    val onOpen: (PhoneMediaItem) -> Unit,
    val onLongPress: (PhoneMediaItem) -> Unit,
    val onAddToCollection: (PhoneMediaItem) -> Unit,
    val onPlayOnPhone: (PhoneMediaItem) -> Unit,
)

@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun fullPermission(kind: MediaKind): String = when (kind) {
    MediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
    MediaKind.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
    MediaKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
}

/**
 * Permissions to request for [kind]. Videos and photos are asked together, as Android shows
 * them as one prompt; on Android 14+ that prompt offers "Select photos and videos".
 */
private fun permissionsFor(kind: MediaKind): Array<String> = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    kind == MediaKind.AUDIO -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    else -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun PhoneFilesHeader(
    onDashboard: () -> Unit,
    onBrowseOtherFiles: () -> Unit,
    onSearch: () -> Unit,
    onSortAndView: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDashboard) {
            com.playbridge.sender.ui.DashboardBlocksIcon(modifier = Modifier.size(22.dp))
        }
        Text(
            "Phone files",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onBrowseOtherFiles) {
            Icon(Icons.Outlined.Folder, contentDescription = "Browse other files")
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = "Search")
        }
        IconButton(onClick = onSortAndView) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort and view")
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close search") }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
    }
}

@Composable
private fun SelectionHeader(count: Int, onCancel: () -> Unit, onSelectAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
        Text(
            "$count selected",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) { Text("Select all") }
    }
}

/** Videos · Music · Photos segmented tabs with counts (null count: no access yet). */
@Composable
private fun MediaTabs(
    selected: PhoneFilesTab,
    counts: Map<PhoneFilesTab, Int?>,
    onSelect: (PhoneFilesTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PhoneFilesTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    counts[tab]?.let { "${tab.label} · $it" } ?: tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Resolves a human display name for a SAF content URI; null when unavailable. */
private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx) else null
        } else null
    }
}.getOrNull()
