package com.playbridge.sender.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.data.history.BookmarkDao
import com.playbridge.sender.data.history.BookmarkEntity
import com.playbridge.sender.data.history.HistoryDao
import com.playbridge.sender.data.history.HistoryEntity
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.downloads.DownloadsScreen
import com.playbridge.sender.history.BookmarksScreen
import com.playbridge.sender.history.CastHistoryScreen
import com.playbridge.sender.history.HistoryScreen
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.settings.SettingsScreen
import com.playbridge.sender.ui.ConnectionScreen
import com.playbridge.sender.ui.DashboardScreen
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.protocol.createSingleVideoCommandJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.engine.gecko.GeckoEngineView
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import playbridge.PlaylistPayload
import playbridge.PlayPayload
import com.playbridge.sender.cast.*
import com.playbridge.sender.library.*
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import com.playbridge.sender.data.library.AddonDao
import com.playbridge.sender.data.settings.SettingsRepository

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNavHost(
    // Navigation & Layout States
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit,
    lastMainScreen: Screen,
    onLastMainScreenChange: (Screen) -> Unit,
    innerPadding: PaddingValues,

    // Session & Tab Management
    session: EngineSession?,
    sessions: Map<String, EngineSession>,
    tabManager: TabManager,
    store: BrowserStore,
    browserCanGoBack: Boolean,
    isEditing: Boolean,
    onIsEditingChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
    onIsFullscreenChange: (Boolean) -> Unit,
    backPressedTime: Long,
    onBackPressedTimeChange: (Long) -> Unit,
    onFinishActivity: () -> Unit,

    // Global dialog / Sheet triggers (stateless state bindings)
    showVideoSheet: Boolean,
    onShowVideoSheetChange: (Boolean) -> Unit,
    forcedVideos: List<DetectedVideo>?,
    onForcedVideosChange: (List<DetectedVideo>?) -> Unit,
    castSheetInitialMode: String,
    onCastSheetInitialModeChange: (String) -> Unit,
    castSheetBrowseOverride: String?,
    onCastSheetBrowseOverrideChange: (String?) -> Unit,

    // Coroutine scope
    scope: CoroutineScope,

    // Layout update triggers
    showFindBar: Boolean,
    onShowFindBarChange: (Boolean) -> Unit,
    isDesktopMode: Boolean,
    onIsDesktopModeChange: (Boolean) -> Unit,
    detectVideosEnabled: Boolean,
    onDetectVideosEnabledChange: (Boolean) -> Unit,

    // Helpers / inputs
    currentUrl: String,
    onCurrentUrlChange: (String) -> Unit,
    urlBarTapped: Boolean,
    onUrlBarTappedChange: (Boolean) -> Unit,
    urlPanelClipboard: String?,
    onUrlPanelClipboardChange: (String?) -> Unit,
    onMagnetDetected: (String) -> Unit = {},
    contextMenuUrl: String?,
    onContextMenuUrlChange: (String?) -> Unit,
    suggestions: List<HistoryEntity>,

    // Settings Flags
    isSettingsFromLibrary: Boolean,
    onIsSettingsFromLibraryChange: (Boolean) -> Unit,

    // Helper functions
    onHandleBookmarkClick: () -> Unit,
    browserViewContent: @Composable (EngineSession, (String) -> Unit) -> Unit,

    // Hoisted Tabs screen states
    isTabsSearchVisible: Boolean = false,
    onTabsSearchVisibleChange: (Boolean) -> Unit = {},
    isTabsMultiSelectMode: Boolean = false,
    onTabsMultiSelectModeChange: (Boolean) -> Unit = {},
    showTabsCloseAllConfirm: Boolean = false,
    onTabsCloseAllConfirmChange: (Boolean) -> Unit = {}
) {
    // 1. Inject ViewModels & Singletons
    val connectionViewModel: ConnectionViewModel = koinViewModel()
    val libraryViewModel: LibraryViewModel = koinViewModel()
    val connectionCoordinator: ConnectionCoordinator = koinInject()
    val tvQueueCoordinator: com.playbridge.sender.connection.TvQueueCoordinator = koinInject()
    val dlnaQueueCoordinator: com.playbridge.sender.connection.DlnaQueueCoordinator = koinInject()
    val historyDao: HistoryDao = koinInject()
    val bookmarkDao: BookmarkDao = koinInject()
    val addonRepository: AddonRepository = koinInject()
    val debridRepository: com.playbridge.sender.data.debrid.DebridRepository = koinInject()
    val subtitleService: com.playbridge.sender.data.library.StremioSubtitleService = koinInject()
    val tmdbRepository: com.playbridge.sender.data.library.TmdbRepository = koinInject()
    val addonDao: AddonDao = koinInject()
    val settingsRepository: SettingsRepository = koinInject()

    // 2. Collect Live Flows / Collected states locally
    val connectionState by connectionViewModel.connectionState.collectAsState()
    val discoveredDevices by connectionViewModel.discoveredDevices.collectAsState()
    val history by connectionViewModel.deviceHistory.collectAsState(initial = emptyList())
    val tvDevice by connectionViewModel.tvDevice.collectAsState(initial = null)
    val activeDlnaTarget by connectionViewModel.activeDlnaTarget.collectAsState()
    val dlnaStatus by connectionViewModel.dlnaStatus.collectAsState()
    val dlnaMediaTitle by connectionViewModel.dlnaMediaTitle.collectAsState()
    val allHistory by historyDao.getAll().collectAsState(initial = emptyList())
    val installedAddons by addonDao.getAll().collectAsState(initial = emptyList())

    // 3. TV Playback/Playlist states from Coordinator
    val tvActiveContext by connectionCoordinator.tvActiveContext.collectAsState()
    val tvPlaylistState by connectionCoordinator.tvPlaylistState.collectAsState()
    val tvPlayback by connectionCoordinator.tvPlayback.collectAsState()
    val tvAudioTracks by connectionCoordinator.tvAudioTracks.collectAsState()
    val tvSubtitleTracks by connectionCoordinator.tvSubtitleTracks.collectAsState()
    val tvPlayerSettings by connectionCoordinator.tvPlayerSettings.collectAsState()
    val nowPlayingTvId by connectionCoordinator.nowPlayingTvId.collectAsState()
    val nowPlayingSeason by connectionCoordinator.nowPlayingSeason.collectAsState()
    val nowPlayingEpisodeStart by connectionCoordinator.nowPlayingEpisodeStart.collectAsState()

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val preferredAudioLang by settingsRepository.preferredAudioLang.collectAsState(initial = "")
    val preferredSubLang by settingsRepository.preferredSubtitleLang.collectAsState(initial = "")
    val defaultVideoQuality by settingsRepository.defaultVideoQuality.collectAsState(initial = "Auto")
    val maxBitrateCapMbps by settingsRepository.maxBitrateCapMbps.collectAsState(initial = 0.0)
    val autoSwitchToRemote by settingsRepository.autoSwitchToRemote.collectAsState(initial = true)
    val tvPlayerMode by settingsRepository.tvPlayerMode.collectAsState(initial = "tv")
    val selectedTab = store.state.tabs.find { it.id == store.state.selectedTabId }
    // Persistent UI-scoped scroll states for the Library screen inside AppNavHost
    val libraryMainListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val libraryDiscoveredMoviesListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val libraryDiscoveredTvShowsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val libraryDiscoverGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val librarySearchResultsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val libraryCatalogRowScrollStates = remember { mutableStateMapOf<String, LazyListState>() }

    // State to determine if search focus should be requested
    var shouldFocusSearch by remember { mutableStateOf(false) }

    // Poster dominant color reported by the library detail screen — themes the
    // NowPlayingBar to match while that screen is up.
    var libraryDetailAccent by remember { mutableStateOf<androidx.compose.ui.graphics.Color?>(null) }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState == Screen.Tabs && initialState == Screen.Browser) {
                slideInVertically { height -> height } + fadeIn() togetherWith
                        slideOutVertically { height -> -height } + fadeOut()
            } else if (targetState == Screen.Browser && initialState == Screen.Tabs) {
                slideInVertically { height -> -height } + fadeIn() togetherWith
                        slideOutVertically { height -> height } + fadeOut()
            } else if ((targetState == Screen.Downloads || targetState == Screen.Extensions || targetState == Screen.Settings || targetState == Screen.Bookmarks || targetState == Screen.Remote || targetState == Screen.AddonSettings || targetState is Screen.LibraryDetail) && (initialState == Screen.Browser || initialState == Screen.Library || initialState == Screen.DebridLibrary || initialState == Screen.Connection)) {
                androidx.compose.animation.slideInHorizontally { width -> width } + fadeIn() togetherWith
                        androidx.compose.animation.slideOutHorizontally { width -> -width } + fadeOut()
            } else if ((targetState == Screen.Browser || targetState == Screen.Library || targetState == Screen.DebridLibrary || targetState == Screen.Connection) && (initialState == Screen.Downloads || initialState == Screen.Extensions || initialState == Screen.Settings || initialState == Screen.Bookmarks || initialState == Screen.Remote || initialState == Screen.AddonSettings || initialState is Screen.LibraryDetail)) {
                androidx.compose.animation.slideInHorizontally { width -> -width } + fadeIn() togetherWith
                        androidx.compose.animation.slideOutHorizontally { width -> width } + fadeOut()
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "ScreenTransition"
    ) { targetScreen ->
        val isOwnTopBar = targetScreen !in listOf(Screen.Browser, Screen.Tabs, Screen.Extensions)
        val resolvedPadding = if (isOwnTopBar) {
            PaddingValues(
                start = innerPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = 0.dp,
                end = innerPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                bottom = innerPadding.calculateBottomPadding()
            )
        } else {
            innerPadding
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(resolvedPadding)
        ) {
            when (targetScreen) {
                Screen.Browser -> {
                    if (isFullscreen) {
                        BackHandler {
                            onIsFullscreenChange(false)
                            // Public AC API instead of reflection into GeckoSession.
                            session?.exitFullScreenMode()
                        }
                    }
                    BackHandler(enabled = !isFullscreen && !isEditing) {
                        if (browserCanGoBack) {
                            session?.goBack()
                        } else {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - backPressedTime > 2000) {
                                onBackPressedTimeChange(currentTime)
                                Toast.makeText(
                                    context,
                                    "Press back again to exit",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                onFinishActivity()
                            }
                        }
                    }

                    BackHandler(enabled = isEditing) {
                        onIsEditingChange(false)
                        onUrlBarTappedChange(false)
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (currentScreen == Screen.Browser && session != null) {
                            browserViewContent(session) { url ->
                                onContextMenuUrlChange(url)
                            }
                        }

                        if (currentUrl == "about:blank") {
                            HomeScreen(
                                onNavigate = { url ->
                                    session?.loadUrl(url)
                                },
                                historyDao = historyDao,
                                bookmarkDao = bookmarkDao
                            )
                        }

                        if (isEditing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    .zIndex(1f)
                            ) {
                                if (urlBarTapped) {
                                    val pageTitle = selectedTab?.content?.title?.takeIf { it.isNotBlank() }
                                    val domain = try {
                                        Uri.parse(currentUrl).host ?: currentUrl
                                    } catch (e: Exception) {
                                        currentUrl
                                    }
                                    val faviconUrl = if (currentUrl != "about:blank") "https://www.google.com/s2/favicons?domain=$domain&sz=64" else null
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        if (currentUrl != "about:blank") {
                                            item {
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                                    shape = MaterialTheme.shapes.large,
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (faviconUrl != null) {
                                                            AsyncImage(
                                                                model = ImageRequest.Builder(context)
                                                                    .data(faviconUrl)
                                                                    .crossfade(true)
                                                                    .build(),
                                                                contentDescription = "Site icon",
                                                                modifier = Modifier.size(28.dp)
                                                            )
                                                        } else {
                                                            Icon(
                                                                Icons.Default.Language,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(28.dp),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = pageTitle ?: domain,
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = currentUrl.removePrefix("https://").removePrefix("http://"),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                                    type = "text/plain"
                                                                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                                                                }
                                                                context.startActivity(Intent.createChooser(intent, "Share URL"))
                                                            },
                                                            modifier = Modifier.size(40.dp)
                                                        ) {
                                                            Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(20.dp))
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                clipboardManager.setText(AnnotatedString(currentUrl))
                                                                onIsEditingChange(false)
                                                                keyboardController?.hide()
                                                                focusManager.clearFocus()
                                                            },
                                                            modifier = Modifier.size(40.dp)
                                                        ) {
                                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(20.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (urlPanelClipboard != null) {
                                            item {
                                                HorizontalDivider(
                                                    modifier = Modifier.padding(horizontal = 16.dp),
                                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                )
                                                ListItem(
                                                    headlineContent = {
                                                        Text(
                                                            "Link you copied",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    },
                                                    supportingContent = {
                                                        Text(
                                                            urlPanelClipboard,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    },
                                                    leadingContent = {
                                                        Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    },
                                                    trailingContent = {
                                                        IconButton(onClick = {
                                                            val clip = urlPanelClipboard
                                                            if (clip.startsWith("magnet:", ignoreCase = true)) {
                                                                onMagnetDetected(clip.trim())
                                                            } else {
                                                                val url = if (clip.startsWith("http://") || clip.startsWith("https://") || clip.startsWith("about:")) clip else "https://$clip"
                                                                session?.loadUrl(url)
                                                            }
                                                            onIsEditingChange(false)
                                                            keyboardController?.hide()
                                                            focusManager.clearFocus()
                                                        }) {
                                                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open link")
                                                        }
                                                    },
                                                    modifier = Modifier.clickable {
                                                        val clip2 = urlPanelClipboard
                                                        if (clip2.startsWith("magnet:", ignoreCase = true)) {
                                                            onMagnetDetected(clip2.trim())
                                                        } else {
                                                            val url = if (clip2.startsWith("http://") || clip2.startsWith("https://") || clip2.startsWith("about:")) clip2 else "https://$clip2"
                                                            session?.loadUrl(url)
                                                        }
                                                        onIsEditingChange(false)
                                                        keyboardController?.hide()
                                                        focusManager.clearFocus()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(suggestions) { historyItem ->
                                            ListItem(
                                                headlineContent = {
                                                    Text(
                                                        historyItem.title ?: historyItem.url,
                                                        maxLines = 1,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                },
                                                supportingContent = {
                                                    Text(
                                                        historyItem.url,
                                                        maxLines = 1,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                },
                                                leadingContent = {
                                                    Icon(Icons.Default.History, null)
                                                },
                                                modifier = Modifier.clickable {
                                                    session?.loadUrl(historyItem.url)
                                                    onIsEditingChange(false)
                                                }
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Screen.History -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    HistoryScreen(
                        historyItems = allHistory,
                        onItemClick = { url ->
                            session?.loadUrl(url)
                            onScreenChange(Screen.Browser)
                        },
                        onClearHistory = {
                            scope.launch(Dispatchers.IO) {
                                historyDao.clear()
                            }
                        },
                        onBack = { onScreenChange(lastMainScreen) }
                    )
                }
                Screen.CastHistory -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    val db = com.playbridge.sender.data.history.DatabaseProvider.getDatabase(context)
                    val commandHistoryFlow = remember { db.commandHistoryDao().getAll() }
                    val commandHistory by commandHistoryFlow.collectAsState(initial = emptyList())
                    CastHistoryScreen(
                        historyItems = commandHistory,
                        onMenuClick = { onScreenChange(Screen.Dashboard) },
                        onItemClick = { item ->
                            onForcedVideosChange(listOf(
                                DetectedVideo(
                                    url = item.url,
                                    title = item.title,
                                    detectedBy = "history",
                                    timestamp = item.timestamp
                                )
                            ))
                            onShowVideoSheetChange(true)
                        },
                        onDelete = { item ->
                            scope.launch(Dispatchers.IO) { db.commandHistoryDao().delete(item) }
                        },
                        onClearHistory = {
                            scope.launch(Dispatchers.IO) { db.commandHistoryDao().clear() }
                        },
                        onBack = { onScreenChange(lastMainScreen) }
                    )
                }
                 Screen.Tabs -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    TabsScreen(
                        onTabSelected = { tabId ->
                            tabManager.selectTab(tabId, store)
                            onScreenChange(Screen.Browser)
                        },
                        onTabClosed = { tabId ->
                            tabManager.closeTab(tabId, store)
                        },
                        onNewTab = {
                            tabManager.createTab("about:blank", store)
                            onScreenChange(Screen.Browser)
                        },
                        onTabDuplicate = { tabId ->
                            tabManager.duplicateTab(tabId, store)
                        },
                        onTabBookmark = { tabId ->
                            val targetTab = store.state.tabs.find { it.id == tabId }
                            targetTab?.let { tab ->
                                val url = tab.content.url
                                if (url.isNotEmpty() && url != "about:blank") {
                                    scope.launch(Dispatchers.IO) {
                                        bookmarkDao.insert(
                                            BookmarkEntity(
                                                url = url,
                                                title = tab.content.title.ifEmpty { null },
                                                timestamp = System.currentTimeMillis()
                                            )
                                        )
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Bookmark added", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        isSearchVisibleExternal = isTabsSearchVisible,
                        onSearchVisibleChangeExternal = onTabsSearchVisibleChange,
                        isMultiSelectModeExternal = isTabsMultiSelectMode,
                        onMultiSelectModeChangeExternal = onTabsMultiSelectModeChange,
                        showCloseAllConfirmExternal = showTabsCloseAllConfirm,
                        onCloseAllConfirmChangeExternal = onTabsCloseAllConfirmChange
                    )
                }
                Screen.Extensions -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    if (session != null) {
                        ExtensionsScreen(
                            session = session,
                            onBack = { onScreenChange(lastMainScreen) },
                            onAddExtension = {
                                tabManager.createTab("https://addons.mozilla.org/android/", store)
                                onScreenChange(Screen.Browser)
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No active session")
                        }
                    }
                }
                Screen.Connection -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    ConnectionScreen(
                        viewModel = connectionViewModel,
                        onMenuClick = { onScreenChange(Screen.Dashboard) },
                        onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                            {
                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                onScreenChange(Screen.Remote)
                            }
                        } else null
                    )
                }
                Screen.Downloads -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    DownloadsScreen(
                        onBack = { onScreenChange(lastMainScreen) }
                    )
                }
                Screen.Settings -> {
                    BackHandler {
                        if (isSettingsFromLibrary) {
                            onScreenChange(Screen.Library)
                            libraryViewModel.setSelectedTab(0)
                        } else {
                            onScreenChange(lastMainScreen)
                        }
                    }
                    SettingsScreen(
                        onBack = {
                            if (isSettingsFromLibrary) {
                                onScreenChange(Screen.Library)
                                libraryViewModel.setSelectedTab(0)
                            } else {
                                onScreenChange(lastMainScreen)
                            }
                        },
                        tvIp = if (connectionState is WebSocketClient.ConnectionState.Connected) tvDevice?.ip else null,
                        tvPort = if (connectionState is WebSocketClient.ConnectionState.Connected) tvDevice?.port else null,
                        showBack = !isSettingsFromLibrary,
                        isFromLibrary = isSettingsFromLibrary
                    )
                }
                Screen.Bookmarks -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    BookmarksScreen(
                        bookmarkDao = bookmarkDao,
                        onNavigate = { url ->
                            session?.loadUrl(url)
                            onScreenChange(Screen.Browser)
                        },
                        onBack = { onScreenChange(lastMainScreen) }
                    )
                }
                Screen.Remote -> {
                    BackHandler {
                        onScreenChange(lastMainScreen)
                    }
                    val dlna = activeDlnaTarget
                    if (dlna != null) {
                        // Unified remote: feed the native RemoteControlScreen from the DLNA target,
                        // with dlnaMode hiding native-only controls (tracks/volume/loop).
                        RemoteControlScreen(
                            activeContext = "player",
                            onBack = { onScreenChange(lastMainScreen) },
                            onRemoteKey = {},
                            onMouseMove = { _, _ -> },
                            onMouseClick = {},
                            onMouseScroll = { _, _ -> },
                            onPlayerControl = { cmd ->
                                when (cmd) {
                                    "play" -> connectionViewModel.dlnaPlay()
                                    "pause" -> connectionViewModel.dlnaPause()
                                    "stop" -> connectionViewModel.dlnaStop()
                                    "seek_back" -> connectionViewModel.dlnaSeek(
                                        ((dlnaStatus?.positionMs ?: 0L) - 10_000L).coerceAtLeast(0L),
                                    )
                                    "seek_forward" -> connectionViewModel.dlnaSeek(
                                        (dlnaStatus?.positionMs ?: 0L) + 10_000L,
                                    )
                                }
                            },
                            playbackState = when (dlnaStatus?.state) {
                                PlaybackState.PLAYING -> "playing"
                                PlaybackState.BUFFERING -> "buffering"
                                PlaybackState.PAUSED -> "paused"
                                else -> "paused"
                            },
                            dlnaMode = true,
                            isLive = dlnaStatus?.isLive == true,
                            positionMs = dlnaStatus?.positionMs ?: 0L,
                            durationMs = dlnaStatus?.durationMs ?: 0L,
                            mediaTitle = dlnaMediaTitle ?: dlna.name,
                            onSeekTo = { connectionViewModel.dlnaSeek(it) },
                            tvName = dlna.name,
                        )
                    } else RemoteControlScreen(
                        activeContext = tvActiveContext,
                        playbackState = tvPlayback?.state,
                        positionMs = tvPlayback?.positionMs ?: 0L,
                        durationMs = tvPlayback?.durationMs ?: 0L,
                        mediaTitle = tvPlayback?.title,
                        episodes = tvPlaylistState?.items ?: emptyList(),
                        currentEpisodeIndex = tvPlaylistState?.currentIndex ?: 0,
                        audioTracks = tvAudioTracks,
                        subtitleTracks = tvSubtitleTracks,
                        onSeekTo = { positionMs ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("seek_to:$positionMs"))
                        },
                        onJumpToEpisode = { index ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createPlaylistJumpCommandJson(index))
                        },
                        onSelectAudio = { id ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("audio_track:$id"))
                        },
                        onSelectSubtitle = { id ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("sub_track:$id"))
                        },
                        playerSettings = tvPlayerSettings,
                        onSetSpeed = { speed ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("speed:$speed"))
                        },
                        onSetScaling = { mode ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("scaling:$mode"))
                        },
                        onToggleAudioBoost = {
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("audio_boost"))
                        },
                        onAdjustSubtitleOffset = { delta ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("sub_offset:$delta"))
                        },
                        onSetFilter = { name ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("filter:$name"))
                        },
                        onSwitchEngine = { engineId ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("switch_player:$engineId"))
                        },
                        onAddSubtitleUrl = { url ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("add_subtitle:$url"))
                        },
                        onSearchSubtitles = nowPlayingTvId?.let { tvId ->
                            val suspendLambda: suspend () -> List<SubtitleOption> = {
                                val isSeries = nowPlayingSeason != null
                                val imdb = if (isSeries) tmdbRepository.getTvDetails(tvId)?.imdbId
                                else tmdbRepository.getMovieDetails(tvId)?.imdbId
                                if (imdb == null) emptyList()
                                else {
                                    val streams = if (isSeries) subtitleService.getSubtitlesForEpisode(
                                        imdb,
                                        nowPlayingSeason ?: 1,
                                        nowPlayingEpisodeStart + (tvPlaylistState?.currentIndex ?: 0)
                                    ) else subtitleService.getSubtitlesForMovie(imdb)
                                    streams.mapNotNull { s ->
                                        s.url?.let { u -> SubtitleOption(s.title ?: s.name ?: u.substringAfterLast('/'), u) }
                                    }
                                }
                            }
                            suspendLambda
                        },
                        onBack = {
                            onScreenChange(lastMainScreen)
                        },
                        onRemoteKey = { key ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createRemoteCommandJson(key))
                        },
                        onMouseMove = { dx, dy ->
                            connectionViewModel.webSocketClient.sendMouseCommand("move", dx, dy)
                        },
                        onMouseClick = {
                            connectionViewModel.webSocketClient.sendMouseCommand("click", 0f, 0f)
                        },
                        onMouseScroll = { dx, dy ->
                            connectionViewModel.webSocketClient.sendMouseCommand("scroll", dx, dy)
                        },
                        onMouseDown = {
                            connectionViewModel.webSocketClient.sendMouseCommand("down", 0f, 0f)
                        },
                        onMouseUp = {
                            connectionViewModel.webSocketClient.sendMouseCommand("up", 0f, 0f)
                        },
                        onBrowserControl = { action ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createBrowserControlCommandJson(action))
                        },
                        onPlayerControl = { command ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson(command))
                            if (command == "stop") { connectionCoordinator.tvActiveContext.value = "idle" }
                        },
                        // Connected-device status pill (switching/disconnecting lives in the Library cast picker).
                        tvName = tvDevice?.name,
                        connectionState = connectionState
                    )
                }
                Screen.Home -> {
                    BackHandler {
                        if (browserCanGoBack) { session?.goBack() } else { onFinishActivity() }
                    }
                    HomeScreen(
                        onNavigate = { url ->
                            session?.loadUrl(url)
                            onScreenChange(Screen.Browser)
                        },
                        historyDao = historyDao,
                        bookmarkDao = bookmarkDao
                    )
                }
                Screen.Library -> {
                    val selectedTabVal by libraryViewModel.selectedTab.collectAsState()
                    BackHandler {
                        if (selectedTabVal != 0) {
                            libraryViewModel.setSelectedTab(0)
                        } else {
                            onFinishActivity()
                        }
                    }
                    LibraryScreen(
                        viewModel = libraryViewModel,
                        addonRepository = addonRepository,
                        installedAddons = installedAddons,
                        onOpenUrl = { url ->
                            session?.loadUrl(url)
                            onScreenChange(Screen.Browser)
                        },
                        tvIp = tvDevice?.ip,
                        tvPort = tvDevice?.port,
                        tvName = tvDevice?.name,
                        connectionState = connectionState,
                        onOpenConnectionScreen = { onScreenChange(Screen.Connection) },
                        onMenuClick = { onScreenChange(Screen.Dashboard) },
                        onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                            {
                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                onScreenChange(Screen.Remote)
                            }
                        } else null,
                        onMovieClick = { movieId -> onScreenChange(Screen.LibraryDetail(movieId.toString(), "movie")) },
                        onTvShowClick = { tvId -> onScreenChange(Screen.LibraryDetail(tvId.toString(), "tv")) },
                        onAddonItemClick = { id, type, source -> onScreenChange(Screen.LibraryDetail(id, type, source)) },
                        mainListState = libraryMainListState,
                        discoveredMoviesListState = libraryDiscoveredMoviesListState,
                        discoveredTvShowsListState = libraryDiscoveredTvShowsListState,
                        discoverGridState = libraryDiscoverGridState,
                        searchResultsListState = librarySearchResultsListState,
                        catalogRowScrollStates = libraryCatalogRowScrollStates,
                        shouldFocusSearch = shouldFocusSearch,
                        onSearchFocused = { shouldFocusSearch = false },
                        onStartSearch = { shouldFocusSearch = true }
                    )
                }
                is Screen.LibraryDetail -> {
                    val screen = targetScreen as Screen.LibraryDetail
                    BackHandler { onScreenChange(Screen.Library) }
                    val screenNumericId = screen.id.toIntOrNull()
                    LibraryDetailScreen(
                        id = screen.id,
                        type = screen.type,
                        forcedSource = screen.source,
                        addonRepository = addonRepository,
                        viewModel = libraryViewModel,
                        tvName = tvDevice?.name,
                        isTvConnected = connectionState is WebSocketClient.ConnectionState.Connected,
                        isDlnaActive = activeDlnaTarget != null,
                        onDominantColorChange = { libraryDetailAccent = it },
                        selectedTvDevice = tvDevice,
                        onTvDeviceSelect = { device -> connectionViewModel.connect(device) },
                        onOpenConnectionScreen = { onScreenChange(Screen.Connection) },
                        onPlayTrailer = { trailerUrl ->
                            onCastSheetInitialModeChange("browse")
                            onCastSheetBrowseOverrideChange(trailerUrl)
                            onShowVideoSheetChange(true)
                        },
                        onPlayPayloadToTv = onPlayPayload@{ payload ->
                            // DLNA renderer is the active target — play via the local proxy, no WS.
                            // (Subtitles/prefs are native-receiver features; skipped on DLNA.)
                            activeDlnaTarget?.let { dlna ->
                                connectionViewModel.playOnDlna(
                                    MediaItem(
                                        url = payload.url,
                                        headers = payload.headers,
                                        title = payload.title,
                                        startPositionMs = payload.start_position_ms ?: 0L,
                                        visualMetadata = payload.visual_metadata,
                                    )
                                )
                                Toast.makeText(context, "Casting to ${dlna.name}", Toast.LENGTH_SHORT).show()
                                if (autoSwitchToRemote) onScreenChange(Screen.Remote)
                                return@onPlayPayload
                            }
                            scope.launch {
                                // Fetch addon subtitles (preferred language) in parallel with the
                                // connection setup so they can be bundled into the play command without
                                // delaying it.
                                val subsDeferred = async {
                                    subtitleService.getAllSubtitleUrls(
                                        payload.visual_metadata?.imdb_id,
                                        payload.visual_metadata?.season,
                                        payload.visual_metadata?.episode,
                                        preferredSubLang
                                    )
                                }

                                // Ensure connected before sending
                                val device = tvDevice
                                if (device != null && connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Connecting to TV...", Toast.LENGTH_SHORT).show() }
                                    connectionViewModel.connect(device)
                                    withTimeoutOrNull(8000) {
                                        connectionViewModel.connectionState.first { it is WebSocketClient.ConnectionState.Connected }
                                    }
                                }

                                if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                    subsDeferred.cancel()
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Could not connect to TV", Toast.LENGTH_SHORT).show() }
                                    return@launch
                                }

                                val addonSubs = runCatching { subsDeferred.await() }.getOrDefault(emptyList())
                                val cmd = createSingleVideoCommandJson(
                                    payload.copy(
                                        subtitles = (payload.subtitles + addonSubs).distinct(),
                                        player_mode = tvPlayerMode.takeIf { it != "tv" },
                                        preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                        preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                        default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                        max_bitrate_cap_mbps = maxBitrateCapMbps,
                                    )
                                )
                                if (connectionViewModel.webSocketClient.send(cmd)) {
                                    // Record identity (movies too; season stays null) and clear the
                                    // previous session's stale playback snapshots in one step.
                                    connectionCoordinator.startLocalPlaybackSession(
                                        tmdbId = payload.visual_metadata?.tmdb_id?.toIntOrNull(),
                                        season = payload.visual_metadata?.season,
                                        episodeStart = payload.visual_metadata?.episode,
                                    )
                                    if (autoSwitchToRemote) {
                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                        onScreenChange(Screen.Remote)
                                    }
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        },
                        onStartTvEpisodeQueue = onStartQueue@{ current, plan ->
                            // DLNA can't hold a queue — the phone drives episode advance:
                            // DlnaQueueCoordinator loads the start episode and watches the
                            // renderer's position to resolve & load each next one.
                            activeDlnaTarget?.let { dlna ->
                                dlnaQueueCoordinator.start(plan)
                                Toast.makeText(
                                    context,
                                    "Casting to ${dlna.name} — episodes will auto-advance",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (autoSwitchToRemote) onScreenChange(Screen.Remote)
                                return@onStartQueue
                            }
                            scope.launch {
                                // Fetch the start episode's subtitles in parallel with connecting.
                                val startSubsDeferred = async {
                                    subtitleService.getAllSubtitleUrls(
                                        current.visual_metadata?.imdb_id,
                                        current.visual_metadata?.season,
                                        current.visual_metadata?.episode,
                                        preferredSubLang
                                    )
                                }
                                // Ensure connected before sending
                                val device = tvDevice
                                if (device != null && connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Connecting to TV...", Toast.LENGTH_SHORT).show() }
                                    connectionViewModel.connect(device)
                                    withTimeoutOrNull(8000) {
                                        connectionViewModel.connectionState.first { it is WebSocketClient.ConnectionState.Connected }
                                    }
                                }
                                if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                    startSubsDeferred.cancel()
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Could not connect to TV", Toast.LENGTH_SHORT).show() }
                                    return@launch
                                }

                                // Decorate with the same playback prefs used for single sends.
                                fun decorate(p: PlayPayload) = p.copy(
                                    player_mode = tvPlayerMode.takeIf { it != "tv" },
                                    preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                    preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                    default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                    max_bitrate_cap_mbps = maxBitrateCapMbps,
                                )

                                val startSubs = runCatching { startSubsDeferred.await() }.getOrDefault(emptyList())
                                val currentCmd = decorate(current).copy(
                                    subtitles = (current.subtitles + startSubs).distinct()
                                )

                                // Send the current episode as a one-item playlist, then let the
                                // coordinator resolve & queue_add the rest (it appends after this).
                                if (connectionViewModel.webSocketClient.send(createSingleVideoCommandJson(currentCmd))) {
                                    connectionCoordinator.startLocalPlaybackSession(
                                        tmdbId = current.visual_metadata?.tmdb_id?.toIntOrNull(),
                                        season = current.visual_metadata?.season,
                                        episodeStart = current.visual_metadata?.episode,
                                    )

                                    tvQueueCoordinator.start(
                                        plan.copy(items = plan.items.map { it.copy(template = decorate(it.template)) })
                                    )

                                    if (autoSwitchToRemote) {
                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                        onScreenChange(Screen.Remote)
                                    }
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        },
                        onPlayStream = { url, title ->
                            val mainVideo = DetectedVideo(
                                url = url,
                                title = title,
                                tabId = -1,
                                timestamp = System.currentTimeMillis(),
                                isPlayable = true,
                                detectedBy = "library"
                            )
                            scope.launch {
                                onForcedVideosChange(listOf(mainVideo))
                                onShowVideoSheetChange(true)
                            }
                        },
                        onSendStreamToTv = onSendStream@{ url, title, headers, contentType ->
                            activeDlnaTarget?.let { dlna ->
                                connectionViewModel.playOnDlna(
                                    MediaItem(
                                        url = url,
                                        headers = headers ?: emptyMap(),
                                        // contentType here is the play-payload kind ("movie"/"series");
                                        // only forward real MIME types to the proxy.
                                        mimeType = contentType?.takeIf { it.contains('/') },
                                        title = title,
                                    )
                                )
                                Toast.makeText(context, "Casting to ${dlna.name}", Toast.LENGTH_SHORT).show()
                                if (autoSwitchToRemote) onScreenChange(Screen.Remote)
                                return@onSendStream
                            }
                            // Proxied sends carry no metadata here — clear identity now
                            // (synchronously: for series the caller immediately re-sets it
                            // via onNowPlayingStarted) so movies can't inherit a stale one.
                            connectionCoordinator.nowPlayingTvId.value = null
                            connectionCoordinator.nowPlayingSeason.value = null
                            connectionCoordinator.clearPlaybackSnapshots()
                            scope.launch {
                                // Ensure connected before sending
                                val device = tvDevice
                                if (device != null && connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Connecting to TV...", Toast.LENGTH_SHORT).show() }
                                    connectionViewModel.connect(device)
                                    withTimeoutOrNull(8000) {
                                        connectionViewModel.connectionState.first { it is WebSocketClient.ConnectionState.Connected }
                                    }
                                }

                                if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Could not connect to TV", Toast.LENGTH_SHORT).show() }
                                    return@launch
                                }

                                val cmd = createSingleVideoCommandJson(
                                    PlayPayload(
                                        url = url,
                                        title = title,
                                        headers = headers ?: emptyMap(),
                                        content_type = contentType,
                                        detected_by = "library",
                                        player_mode = tvPlayerMode.takeIf { it != "tv" },
                                        preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                        preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                        default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                        max_bitrate_cap_mbps = maxBitrateCapMbps,
                                    )
                                )
                                if (connectionViewModel.webSocketClient.send(cmd)) {
                                    connectionCoordinator.tvActiveContext.value = "player"
                                    if (autoSwitchToRemote) {
                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                        onScreenChange(Screen.Remote)
                                    }
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        },
                        onPlayPlaylistToTv = onPlayPlaylist@{ playlist ->
                            // DLNA: no renderer-side playlist — run the Hub playlist through the
                            // phone-driven advancer (items already carry resolved/Hub urls).
                            activeDlnaTarget?.let { dlna ->
                                if (playlist.items.isEmpty()) return@onPlayPlaylist
                                dlnaQueueCoordinator.start(
                                    com.playbridge.sender.connection.TvEpisodeQueuePlan(
                                        streamType = "series",
                                        forcedSource = null,
                                        bingeGroup = null,
                                        startIndex = playlist.start_index.coerceIn(0, playlist.items.lastIndex),
                                        items = playlist.items.map {
                                            com.playbridge.sender.connection.TvQueueEpisode(streamId = "", template = it)
                                        }
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    "Casting to ${dlna.name} — episodes will auto-advance",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (autoSwitchToRemote) onScreenChange(Screen.Remote)
                                return@onPlayPlaylist
                            }
                            scope.launch {
                                // Fetch the start episode's subtitles in parallel with connecting.
                                val startVm = playlist.items.getOrNull(playlist.start_index)?.visual_metadata
                                val startSubsDeferred = async {
                                    subtitleService.getAllSubtitleUrls(
                                        startVm?.imdb_id, startVm?.season, startVm?.episode, preferredSubLang
                                    )
                                }
                                // Ensure connected before sending
                                val device = tvDevice
                                if (device != null && connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Connecting to TV...", Toast.LENGTH_SHORT).show() }
                                    connectionViewModel.connect(device)
                                    withTimeoutOrNull(8000) {
                                        connectionViewModel.connectionState.first { it is WebSocketClient.ConnectionState.Connected }
                                    }
                                }

                                if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                    startSubsDeferred.cancel()
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Could not connect to TV", Toast.LENGTH_SHORT).show() }
                                    return@launch
                                }

                                // Bundle the start episode's subtitles into its item; subsequent Hub
                                // episodes get theirs as they advance (handled by the queue coordinator
                                // for no-Hub series; Hub playlists rely on the remote's Search Subtitles).
                                val startSubs = runCatching { startSubsDeferred.await() }.getOrDefault(emptyList())
                                val playerMode = tvPlayerMode.takeIf { it != "tv" }
                                val itemsWithPrefs = playlist.items.mapIndexed { idx, it ->
                                    it.copy(
                                        subtitles = if (idx == playlist.start_index) (it.subtitles + startSubs).distinct() else it.subtitles,
                                        player_mode = playerMode,
                                        preferred_audio_language = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                        preferred_subtitle_language = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                        default_video_quality = defaultVideoQuality.takeIf { q -> q != "Auto" },
                                        max_bitrate_cap_mbps = maxBitrateCapMbps,
                                    )
                                }
                                val finalPlaylist = playlist.copy(items = itemsWithPrefs)
                                if (connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createPlaylistCommandJson(finalPlaylist))) {
                                    connectionCoordinator.startLocalPlaybackSession(
                                        tmdbId = screenNumericId,
                                        season = playlist.items.getOrNull(playlist.start_index)?.visual_metadata?.season ?: 1,
                                        episodeStart = playlist.items.getOrNull(playlist.start_index)?.visual_metadata?.episode,
                                    )
                                    if (autoSwitchToRemote) {
                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                        onScreenChange(Screen.Remote)
                                    }
                                    withContext(Dispatchers.Main) { Toast.makeText(context, "Playlist sent to TV", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        },
                        onQueueAdd = { item ->
                            val playerMode = tvPlayerMode.takeIf { it != "tv" }
                            val itemWithPrefs = item.copy(
                                player_mode = playerMode,
                                preferred_audio_language = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                preferred_subtitle_language = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                default_video_quality = defaultVideoQuality.takeIf { q -> q != "Auto" },
                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                            )
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createQueueAddCommandJson(itemWithPrefs))
                        },
                        onNowPlayingStarted = { tmdbId, season, startEp ->
                            connectionCoordinator.nowPlayingTvId.value = tmdbId
                            connectionCoordinator.nowPlayingSeason.value = season
                            connectionCoordinator.nowPlayingEpisodeStart.value = startEp
                        },
                        onBack = { onScreenChange(Screen.Library) },
                        onShare = { title, imdbId ->
                            val shareText = if (imdbId != null && imdbId.startsWith("tt")) {
                                "Check out $title on IMDb: https://www.imdb.com/title/$imdbId/"
                            } else {
                                "Check out $title on PlayBridge"
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share $title"))
                        }
                    )
                }
                Screen.AddonSettings -> {
                    BackHandler {
                        onScreenChange(Screen.Library)
                        libraryViewModel.setSelectedTab(0)
                    }
                    AddonSettingsScreen(
                        addonRepository = addonRepository,
                        installedAddons = installedAddons,
                        onBack = {
                            onScreenChange(Screen.Library)
                            libraryViewModel.setSelectedTab(0)
                        },
                        showBack = false,
                        onOpenUrl = { url ->
                            tabManager.createTab(url, store)
                            onScreenChange(Screen.Browser)
                        },
                        onRefreshCatalogs = { libraryViewModel.refreshCatalogsNow() },
                        onClearCatalogCache = { libraryViewModel.clearCatalogCache() },
                        onCatalogsChanged = { libraryViewModel.refreshCatalogsNow() }
                    )
                }
                Screen.Dashboard -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    val isConnected = connectionState is WebSocketClient.ConnectionState.Connected
                    DashboardScreen(
                        currentScreen = lastMainScreen,
                        isConnected = isConnected,
                        isSecure = (connectionState as? WebSocketClient.ConnectionState.Connected)?.secure == true,
                        connectedDeviceName = tvDevice?.name,
                        onNavigate = { screen ->
                            onScreenChange(screen)
                        }
                    )
                }
                Screen.PhoneFiles -> {
                    BackHandler { onScreenChange(Screen.Dashboard) }
                    PhoneFilesScreen(
                        viewModel = connectionViewModel,
                        onBack = { onScreenChange(Screen.Dashboard) },
                        onOpenAllDevices = { onScreenChange(Screen.Connection) },
                    )
                }
                Screen.DebridLibrary -> {
                    BackHandler { onFinishActivity() }
                    DebridLibraryScreen(
                        onMenuClick = { onScreenChange(Screen.Dashboard) },
                        onCopyUrl = { linkUrl ->
                            clipboardManager.setText(AnnotatedString(linkUrl))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        },
                        onShowCastSheet = { video ->
                            onForcedVideosChange(listOf(video))
                            onShowVideoSheetChange(true)
                        }
                    )
                }
            }

            // ── Now-playing mini-bar ─────────────────────────────────────────────
            // Persistent across the main (non-browser) screens while a cast session is
            // active; tap → Remote. Hidden on Browser (bottom toolbar), Remote
            // (redundant), and the other full-bleed screens.
            val showNowPlayingBar = targetScreen == Screen.Dashboard ||
                targetScreen == Screen.Library ||
                targetScreen == Screen.Connection ||
                targetScreen == Screen.PhoneFiles ||
                targetScreen == Screen.DebridLibrary ||
                targetScreen is Screen.LibraryDetail
            if (showNowPlayingBar) {
                val dlnaActive = activeDlnaTarget != null
                val dlnaHasMedia = dlnaActive && (dlnaMediaTitle != null || dlnaStatus != null)
                val nativePlaying = tvActiveContext == "player" &&
                    connectionState is WebSocketClient.ConnectionState.Connected
                if (dlnaHasMedia || nativePlaying) {
                    NowPlayingBar(
                        deviceName = activeDlnaTarget?.name ?: tvDevice?.name ?: "TV",
                        title = if (dlnaActive) dlnaMediaTitle else tvPlayback?.title,
                        isPlaying = if (dlnaActive) {
                            dlnaStatus?.state == PlaybackState.PLAYING
                        } else {
                            tvPlayback?.state == "playing"
                        },
                        isDlna = dlnaActive,
                        onPlayPause = {
                            if (dlnaActive) {
                                if (dlnaStatus?.state == PlaybackState.PLAYING) {
                                    connectionViewModel.dlnaPause()
                                } else {
                                    connectionViewModel.dlnaPlay()
                                }
                            } else {
                                val cmd = if (tvPlayback?.state == "playing") "pause" else "play"
                                connectionViewModel.webSocketClient.send(
                                    com.playbridge.shared.protocol.createControlCommandJson(cmd)
                                )
                            }
                        },
                        onClick = {
                            if (!dlnaActive) {
                                connectionViewModel.webSocketClient.send(
                                    com.playbridge.shared.protocol.createContextQueryJson()
                                )
                            }
                            onScreenChange(Screen.Remote)
                        },
                        // Poster-matched accent on the library detail screen (the old FAB's
                        // dynamic styling); FAB-like primaryContainer elsewhere.
                        accentColor = if (targetScreen is Screen.LibraryDetail) libraryDetailAccent else null,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Clear the system navigation bar, plus the Library's bottom
                            // NavigationBar (80dp content height) on the Library screen.
                            .padding(
                                bottom = WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding() +
                                    if (targetScreen == Screen.Library) 80.dp else 0.dp
                            )
                    )
                }
            }
        }
    }
}

