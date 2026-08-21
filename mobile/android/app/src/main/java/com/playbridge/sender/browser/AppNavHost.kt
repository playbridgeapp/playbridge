package com.playbridge.sender.browser

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.settings.SettingsScreen
import com.playbridge.sender.ui.ConnectionScreen
import com.playbridge.sender.ui.DashboardScreen
import com.playbridge.sender.cast.mirror.ScreenMirrorScreen
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
import com.playbridge.sender.ui.theme.DynamicColorTheme
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import com.playbridge.sender.data.library.AddonDao
import com.playbridge.sender.data.settings.SettingsRepository
import kotlin.math.roundToInt

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNavHost(
    // Navigation & Layout States
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit,
    lastMainScreen: Screen,
    onLastMainScreenChange: (Screen) -> Unit,
    connectionInitialTab: Int = 0,
    // Where the Remote screen's Back should return (the screen it was opened from);
    // defaults to lastMainScreen at the call site when no origin was recorded.
    remoteReturnScreen: Screen = lastMainScreen,
    // Where the Dashboard's close (X) should return — the screen it was opened from.
    dashboardReturnScreen: Screen = lastMainScreen,
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
    isBrowserChromeHidden: Boolean,
    onIsBrowserChromeHiddenChange: (Boolean) -> Unit,
    backPressedTime: Long,
    onBackPressedTimeChange: (Long) -> Unit,
    onFinishActivity: () -> Unit,
    // Full app exit (Dashboard "Exit" button): tears down the cast session/service before finishing.
    onFullExit: () -> Unit = onFinishActivity,

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
    val appContext = LocalContext.current
    // Process-wide cast notices (proxy fallback, browser playback failure) as Toasts so
    // they surface even when the Devices snackbar host is not visible.
    LaunchedEffect(Unit) {
        connectionViewModel.castNotices.collect { message ->
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
    val iptvViewModel: com.playbridge.sender.iptv.IptvViewModel = koinViewModel()
    val collectionsViewModel: com.playbridge.sender.collection.CollectionsViewModel = koinViewModel()
    // Centralized "Add to Collection": any screen sets this draft → one shared sheet shows.
    var addToCollectionDraft by remember { mutableStateOf<com.playbridge.sender.data.collection.CollectionItemDraft?>(null) }
    val onAddToCollection: (com.playbridge.sender.data.collection.CollectionItemDraft) -> Unit =
        { addToCollectionDraft = it }
    val connectionCoordinator: ConnectionCoordinator = koinInject()
    val tvQueueCoordinator: com.playbridge.sender.connection.TvQueueCoordinator = koinInject()
    val externalQueueCoordinator: com.playbridge.sender.connection.ExternalQueueCoordinator = koinInject()
    val historyDao: HistoryDao = koinInject()
    val bookmarkDao: BookmarkDao = koinInject()
    val addonRepository: AddonRepository = koinInject()
    val debridRepository: com.playbridge.sender.data.debrid.DebridRepository = koinInject()
    val subtitleService: com.playbridge.sender.data.library.StremioSubtitleService = koinInject()
    val tmdbRepository: com.playbridge.sender.data.library.TmdbRepository = koinInject()
    val addonDao: AddonDao = koinInject()
    val settingsRepository: SettingsRepository = koinInject()
    val linkedPageCastCoordinator: LinkedPageCastCoordinator = koinInject()
    val linkedPageCastState by linkedPageCastCoordinator.uiState.collectAsState()

    // 2. Collect Live Flows / Collected states locally
    val connectionState by connectionViewModel.connectionState.collectAsState()
    val tvDevice by connectionViewModel.tvDevice.collectAsState(initial = null)
    val activeExternalDevice by connectionViewModel.activeExternalDevice.collectAsState()
    val externalStatus by connectionViewModel.externalStatus.collectAsState()
    val externalMediaTitle by connectionViewModel.externalMediaTitle.collectAsState()
    val castSessionState by connectionViewModel.castSessionState.collectAsState()
    val castRoute by connectionViewModel.route.collectAsState()
    // Authoritative routing intent — reactive so screens follow device-picker changes live.
    val routeTargetsTv by connectionViewModel.routeTargetsTv.collectAsState()
    val allHistory by historyDao.getAll().collectAsState(initial = emptyList())
    val installedAddons by addonDao.getAll().collectAsState(initial = emptyList())

    // 3. TV Playback/Playlist states from Coordinator
    val tvActiveContext by connectionCoordinator.tvActiveContext.collectAsState()
    val tvPlaylistState by connectionCoordinator.tvPlaylistState.collectAsState()
    val tvPlayback by connectionCoordinator.tvPlayback.collectAsState()
    val tvVideoTracks by connectionCoordinator.tvVideoTracks.collectAsState()
    val tvAudioTracks by connectionCoordinator.tvAudioTracks.collectAsState()
    val tvSubtitleTracks by connectionCoordinator.tvSubtitleTracks.collectAsState()
    val tvPlayerSettings by connectionCoordinator.tvPlayerSettings.collectAsState()
    val nowPlayingTvId by connectionCoordinator.nowPlayingTvId.collectAsState()
    val nowPlayingSeason by connectionCoordinator.nowPlayingSeason.collectAsState()
    val nowPlayingEpisodeStart by connectionCoordinator.nowPlayingEpisodeStart.collectAsState()

    val context = LocalContext.current

    val installedUserScripts by connectionCoordinator.installedUserScripts.collectAsState()
    var showUserScripts by remember { mutableStateOf(false) }

    // Picks any .js file from the phone and ships it to the TV as a user script (e.g. the
    // opt-in ad-skipper). Neither app bundles such scripts; the user supplies the file. The
    // file's own name is used so scripts can be listed/removed by name on the TV.
    val userScriptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                var name = "user.js"
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.let { name = it }
                    }
                }
                val content = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                if (!content.isNullOrBlank()) {
                    connectionViewModel.webSocketClient.send(
                        com.playbridge.shared.protocol.createUserScriptJson(name, content)
                    )
                    android.widget.Toast.makeText(context, "Sent $name to TV", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "Script file was empty", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Couldn't read script: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showUserScripts) {
        UserScriptsSheet(
            scripts = installedUserScripts,
            onInstall = { userScriptPicker.launch("*/*") },
            onRemove = { name ->
                // Empty content tells the TV to delete that script.
                connectionViewModel.webSocketClient.send(
                    com.playbridge.shared.protocol.createUserScriptJson(name, "")
                )
            },
            onDismiss = { showUserScripts = false }
        )
    }

    val tvUserAgentState by connectionCoordinator.tvUserAgentState.collectAsState()
    var showTvUserAgent by remember { mutableStateOf(false) }

    if (showTvUserAgent) {
        TvUserAgentSheet(
            active = tvUserAgentState.active,
            savedEntries = tvUserAgentState.entries,
            onSelectDefault = {
                connectionViewModel.webSocketClient.send(
                    com.playbridge.shared.protocol.createUserAgentJson("", "", save = false)
                )
            },
            onSelectPreset = { name, value ->
                // Built-in presets apply without cluttering the TV's saved list.
                connectionViewModel.webSocketClient.send(
                    com.playbridge.shared.protocol.createUserAgentJson(name, value, save = false)
                )
            },
            onApplyNamed = { name, value ->
                // Re-selecting a saved entry or adding a brand-new custom one — either way
                // the TV applies it and (re)saves it under that name.
                connectionViewModel.webSocketClient.send(
                    com.playbridge.shared.protocol.createUserAgentJson(name, value, save = true)
                )
            },
            onRemove = { name ->
                // Blank value tells the TV to delete that saved entry.
                connectionViewModel.webSocketClient.send(
                    com.playbridge.shared.protocol.createUserAgentJson(name, "", save = true)
                )
            },
            onDismiss = { showTvUserAgent = false }
        )
    }

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

    // Phone Files UI state, hoisted here so the user's tab/search/sort/folder/scroll survive
    // leaving and returning to the screen (AnimatedContent disposes the screen content).
    val phoneFilesUiState = remember { PhoneFilesUiState() }

    // Device picker opened from the idle cast bar (rendered once at the host level so
    // it survives screen transitions in the AnimatedContent below).
    var showDevicePicker by remember { mutableStateOf(false) }
    val linkedDevicePickerRequest by Components.linkedDevicePickerRequests.collectAsState()
    var handledLinkedDevicePickerRequest by rememberSaveable { mutableLongStateOf(0L) }
    var linkedPickerVisible by remember { mutableStateOf(false) }
    var linkedPickerCompleted by remember { mutableStateOf(false) }
    LaunchedEffect(linkedDevicePickerRequest) {
        if (linkedDevicePickerRequest > handledLinkedDevicePickerRequest) {
            handledLinkedDevicePickerRequest = linkedDevicePickerRequest
            linkedPickerCompleted = false
            linkedPickerVisible = true
        }
    }
    // "Find more devices" from host-level pickers must expand Other devices even when
    // the parent left connectionInitialTab at 0 (BrowserActivity only sets tab=1 on some paths).
    var expandOtherDevices by remember { mutableStateOf(false) }
    LaunchedEffect(currentScreen) {
        if (currentScreen != Screen.Connection) expandOtherDevices = false
    }
    fun openAllDevicesExpanded() {
        expandOtherDevices = true
        onScreenChange(Screen.Connection)
    }
    if (showDevicePicker) {
        DeviceConnectionDialog(
            onDismiss = { showDevicePicker = false },
            onOpenAllDevices = {
                showDevicePicker = false
                openAllDevicesExpanded()
            },
            showThisDevice = true,
        )
    }
    if (linkedPickerVisible) {
        DeviceConnectionDialog(
            onDismiss = {
                linkedPickerVisible = false
                if (!linkedPickerCompleted) Components.onLinkedDevicePickerDismissed?.invoke()
                linkedPickerCompleted = false
            },
            onOpenAllDevices = {
                linkedPickerVisible = false
                Components.onLinkedDevicePickerDismissed?.invoke()
                openAllDevicesExpanded()
            },
            showThisDevice = false,
            playBridgeOnly = true,
            onPickedDevice = { device ->
                linkedPickerCompleted = true
                linkedPickerVisible = false
                Components.onLinkedDevicePicked?.invoke(device)
            },
        )
    }

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
                    BackHandler(enabled = !isFullscreen && isBrowserChromeHidden) {
                        onIsBrowserChromeHiddenChange(false)
                    }
                    BackHandler(enabled = !isFullscreen && !isBrowserChromeHidden && !isEditing) {
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

                    BackHandler(enabled = isEditing && !isBrowserChromeHidden) {
                        onIsEditingChange(false)
                        onUrlBarTappedChange(false)
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }

                    var chromeHandleOnRight by rememberSaveable { mutableStateOf(true) }
                    var chromeHandleYFraction by rememberSaveable { mutableFloatStateOf(0f) }
                    var chromeHandleDragOffset by remember { mutableStateOf<Offset?>(null) }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (isBrowserChromeHidden && !isFullscreen) {
                                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
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

                        if (isBrowserChromeHidden && !isFullscreen) {
                            val density = LocalDensity.current
                            val handleSizePx = with(density) { 48.dp.toPx() }
                            val maxHandleX = (with(density) { maxWidth.toPx() } - handleSizePx)
                                .coerceAtLeast(0f)
                            val maxHandleY = (with(density) { maxHeight.toPx() } - handleSizePx)
                                .coerceAtLeast(0f)
                            val restingHandleOffset = Offset(
                                x = if (chromeHandleOnRight) maxHandleX else 0f,
                                y = chromeHandleYFraction.coerceIn(0f, 1f) * maxHandleY,
                            )
                            val displayedHandleOffset = chromeHandleDragOffset ?: restingHandleOffset

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset {
                                        IntOffset(
                                            x = displayedHandleOffset.x.roundToInt(),
                                            y = displayedHandleOffset.y.roundToInt(),
                                        )
                                    }
                                    .pointerInput(maxHandleX, maxHandleY, restingHandleOffset) {
                                        detectDragGestures(
                                            onDragStart = {
                                                chromeHandleDragOffset = restingHandleOffset
                                            },
                                            onDragEnd = {
                                                chromeHandleDragOffset?.let { position ->
                                                    chromeHandleOnRight = position.x >= maxHandleX / 2f
                                                    chromeHandleYFraction = if (maxHandleY > 0f) {
                                                        position.y / maxHandleY
                                                    } else {
                                                        0f
                                                    }
                                                }
                                                chromeHandleDragOffset = null
                                            },
                                            onDragCancel = {
                                                chromeHandleDragOffset = null
                                            },
                                        ) { change, dragAmount ->
                                            change.consume()
                                            val current = chromeHandleDragOffset ?: restingHandleOffset
                                            chromeHandleDragOffset = Offset(
                                                x = (current.x + dragAmount.x).coerceIn(0f, maxHandleX),
                                                y = (current.y + dragAmount.y).coerceIn(0f, maxHandleY),
                                            )
                                        }
                                    },
                                shape = when {
                                    chromeHandleDragOffset != null -> RoundedCornerShape(16.dp)
                                    chromeHandleOnRight -> RoundedCornerShape(
                                        topStart = 16.dp,
                                        bottomStart = 16.dp,
                                    )
                                    else -> RoundedCornerShape(
                                        topEnd = 16.dp,
                                        bottomEnd = 16.dp,
                                    )
                                },
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                                tonalElevation = 6.dp,
                                shadowElevation = 4.dp,
                            ) {
                                IconButton(onClick = { onIsBrowserChromeHiddenChange(false) }) {
                                    Icon(
                                        imageVector = Icons.Default.FullscreenExit,
                                        contentDescription = "Show browser controls",
                                    )
                                }
                            }
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
                                        currentUrl.toUri().host ?: currentUrl
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
                    BackHandler { onScreenChange(Screen.Dashboard) }
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
                        onBack = { onScreenChange(lastMainScreen) },
                        onAddToCollection = { item ->
                            onAddToCollection(
                                com.playbridge.sender.data.collection.CollectionItemDraft(
                                    title = item.title ?: item.url,
                                    url = item.url,
                                    kind = com.playbridge.sender.data.collection.CollectionItemKind.WEB,
                                    sourceTag = com.playbridge.sender.data.collection.CollectionSource.HISTORY,
                                )
                            )
                        }
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
                    BackHandler { onScreenChange(Screen.Dashboard) }
                    ConnectionScreen(
                        viewModel = connectionViewModel,
                        onMenuClick = { onScreenChange(Screen.Dashboard) },
                        onRemoteClick = if (
                            activeExternalDevice != null ||
                            (castRoute is CastSessionManager.Route.NativeTv &&
                                connectionState is WebSocketClient.ConnectionState.Connected)
                        ) {
                            {
                                if (activeExternalDevice == null) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                }
                                onScreenChange(Screen.Remote)
                            }
                        } else null,
                        initialTab = if (expandOtherDevices) 1 else connectionInitialTab,
                    )
                }
                Screen.Downloads -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    DownloadsScreen(
                        onBack = { onScreenChange(lastMainScreen) }
                    )
                }
                Screen.Settings -> {
                    // Global Settings (Dashboard gear only). Back returns to where Settings was
                    // opened from — typically Dashboard when using the gear.
                    BackHandler { onScreenChange(lastMainScreen) }
                    SettingsScreen(
                        onBack = { onScreenChange(lastMainScreen) },
                        tvIp = if (connectionState is WebSocketClient.ConnectionState.Connected) tvDevice?.ip else null,
                        // New receivers advertise the independent diagnostics listener.
                        // Legacy Android TVs used receiverPort + 1 without a TXT key.
                        tvPort = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                            tvDevice?.logsPort
                                ?: tvDevice?.port?.takeIf { it < 65535 }?.plus(1)
                        } else null,
                        showBack = true,
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
                        onScreenChange(remoteReturnScreen)
                    }
                    val external = activeExternalDevice
                    val remoteTimeline = resolveRemoteTimeline(
                        activeContext = tvActiveContext,
                        playback = tvPlayback,
                        playerSettings = tvPlayerSettings,
                    )
                    if (external != null) {
                        RemoteControlScreen(
                            activeContext = "player",
                            onBack = { onScreenChange(remoteReturnScreen) },
                            onRemoteKey = { key ->
                                when {
                                    key == "volume_up" -> connectionViewModel.externalAdjustVolume(true)
                                    key == "volume_down" -> connectionViewModel.externalAdjustVolume(false)
                                    external.resolvedProtocol == com.playbridge.sender.model.CastProtocol.ROKU ->
                                        connectionViewModel.rokuKeypress(key)
                                }
                            },
                            onMouseMove = { _, _ -> },
                            onMouseClick = {},
                            onMouseScroll = { _, _ -> },
                            onPlayerControl = { cmd ->
                                when (cmd) {
                                    "play" -> connectionViewModel.externalPlay()
                                    "pause" -> connectionViewModel.externalPause()
                                    "stop" -> connectionViewModel.externalStop()
                                    "seek_back" -> connectionViewModel.externalSeek(
                                        ((externalStatus?.positionMs ?: 0L) - 10_000L).coerceAtLeast(0L),
                                    )
                                    "seek_forward" -> connectionViewModel.externalSeek(
                                        (externalStatus?.positionMs ?: 0L) + 10_000L,
                                    )
                                }
                            },
                            playbackState = when (externalStatus?.state) {
                                PlaybackState.PLAYING -> "playing"
                                PlaybackState.BUFFERING -> "buffering"
                                PlaybackState.PAUSED -> "paused"
                                else -> "paused"
                            },
                            externalProtocolLabel = external.resolvedProtocol.displayName,
                            externalCapabilities = castSessionState.capabilities,
                            isLive = externalStatus?.isLive == true,
                            positionMs = externalStatus?.positionMs ?: 0L,
                            durationMs = externalStatus?.durationMs ?: 0L,
                            mediaTitle = externalMediaTitle ?: external.name,
                            onSeekTo = { connectionViewModel.externalSeek(it) },
                            tvName = external.name,
                        )
                    } else RemoteControlScreen(
                        activeContext = tvActiveContext,
                        playbackState = tvPlayback?.state,
                        isLive = remoteTimeline.isLive,
                        isSeekable = remoteTimeline.isSeekable,
                        positionMs = tvPlayback?.positionMs ?: 0L,
                        durationMs = tvPlayback?.durationMs ?: 0L,
                        mediaTitle = tvPlayback?.title,
                        mediaKind = tvPlayback?.mediaKind ?: "video",
                        episodes = tvPlaylistState?.items ?: emptyList(),
                        currentEpisodeIndex = tvPlaylistState?.currentIndex ?: 0,
                        videoTracks = tvVideoTracks,
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
                        onSetVideoQuality = { maxHeight ->
                            val value = maxHeight.takeIf { it > 0 }?.toString() ?: "auto"
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson("video_quality:$value"))
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
                            onScreenChange(remoteReturnScreen)
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
                        onPinchZoom = { factor ->
                            connectionViewModel.webSocketClient.sendMouseCommand("zoom", factor, 0f)
                        },
                        onRotateImage = { degrees ->
                            connectionViewModel.webSocketClient.sendMouseCommand("rotate", degrees, 0f)
                        },
                        onResetZoom = {
                            connectionViewModel.webSocketClient.sendMouseCommand("reset", 0f, 0f)
                        },
                        onMouseDown = {
                            connectionViewModel.webSocketClient.sendMouseCommand("down", 0f, 0f)
                        },
                        onMouseUp = {
                            connectionViewModel.webSocketClient.sendMouseCommand("up", 0f, 0f)
                        },
                        onBrowserControl = { action ->
                            when (action) {
                                // Phone-side actions: open a manager sheet and ask the TV for
                                // its current state — not a browser command.
                                "manage_user_scripts" -> {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createUserScriptQueryJson())
                                    showUserScripts = true
                                }
                                "manage_user_agent" -> {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createUserAgentQueryJson())
                                    showTvUserAgent = true
                                }
                                else -> connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createBrowserControlCommandJson(action))
                            }
                        },
                        onPlayerControl = { command ->
                            connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson(command))
                            if (command == "stop") { connectionCoordinator.tvActiveContext.value = "idle" }
                        },
                        // Connected-device status pill (switching/disconnecting lives in the Library cast picker).
                        tvName = activeExternalDevice?.name
                            ?: tvDevice?.name?.takeIf { castRoute is CastSessionManager.Route.NativeTv },
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
                            onScreenChange(Screen.Dashboard)
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
                        tvName = tvDevice?.name,
                        onOpenConnectionScreen = { onScreenChange(Screen.Connection) },
                        onMenuClick = { onScreenChange(Screen.Dashboard) },
                        onRemoteClick = if (
                            activeExternalDevice != null ||
                            (castRoute is CastSessionManager.Route.NativeTv &&
                                connectionState is WebSocketClient.ConnectionState.Connected)
                        ) {
                            {
                                if (activeExternalDevice == null) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                }
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
                    // Re-tint the whole detail screen from the poster's dominant
                    // colour (reported below via onDominantColorChange).
                    DynamicColorTheme(seedColor = libraryDetailAccent) {
                    LibraryDetailScreen(
                        id = screen.id,
                        type = screen.type,
                        forcedSource = screen.source,
                        addonRepository = addonRepository,
                        viewModel = libraryViewModel,
                        tvName = activeExternalDevice?.name
                            ?: tvDevice?.name?.takeIf { castRoute is CastSessionManager.Route.NativeTv },
                        isTvConnected = activeExternalDevice != null ||
                            (castRoute is CastSessionManager.Route.NativeTv &&
                                connectionState is WebSocketClient.ConnectionState.Connected),
                        routeTargetsTv = routeTargetsTv,
                        onSetWatchRoute = { toTv ->
                            if (toTv) connectionViewModel.selectNativeRoute() else connectionViewModel.selectThisDevice()
                        },
                        onDominantColorChange = { libraryDetailAccent = it },
                        onOpenConnectionScreen = { onScreenChange(Screen.Connection) },
                        onPlayTrailer = { trailerUrl ->
                            onCastSheetInitialModeChange("browse")
                            onCastSheetBrowseOverrideChange(trailerUrl)
                            onShowVideoSheetChange(true)
                        },
                        onPlayPayloadToTv = onPlayPayload@{ payload ->
                            // A single (non-series) cast replaces the TV queue with one item, so any
                            // binge plan still held by the episode coordinators is now stale. Tear it
                            // down explicitly — otherwise the old plan keeps `queue_add`-ing the
                            // previous series onto this new video (context never goes "idle" on a
                            // back-to-back cast, so the coordinators won't self-clear).
                            tvQueueCoordinator.stop()
                            externalQueueCoordinator.stop()
                            if (castRoute is CastSessionManager.Route.ThisDevice) {
                                Toast.makeText(context, "Choose a receiver first", Toast.LENGTH_SHORT).show()
                                return@onPlayPayload
                            }
                            // A third-party receiver is active — use its protocol target, not WS.
                            // Receiver-specific unsupported metadata is ignored by the target.
                            activeExternalDevice?.let { external ->
                                connectionViewModel.castToSelectedExternal(
                                    MediaItem(
                                        url = payload.url,
                                        headers = payload.headers,
                                        title = payload.title,
                                        startPositionMs = payload.start_position_ms ?: 0L,
                                        visualMetadata = payload.visual_metadata,
                                    )
                                )
                                Toast.makeText(context, "Casting to ${external.name}", Toast.LENGTH_SHORT).show()
                                if (autoSwitchToRemote) onScreenChange(Screen.Remote)
                                return@onPlayPayload
                            }
                            scope.launch {
                                // Fetch addon subtitles (preferred language) in parallel with the
                                // connection setup so they can be bundled into the play command without
                                // delaying it.
                                val subsDeferred = async {
                                    val sendSubtitles = runCatching { settingsRepository.sendSubtitlesToTv.first() }.getOrDefault(true)
                                    if (!sendSubtitles) return@async emptyList<String>()
                                    subtitleService.getAllSubtitleUrls(
                                        payload.visual_metadata?.imdb_id,
                                        payload.visual_metadata?.season,
                                        payload.visual_metadata?.episode,
                                        preferredSubLang,
                                        videoRelease = subtitleService.filenameFromUrl(payload.url)
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
                                linkedPageCastCoordinator.supersedeIfActive()
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
                            if (castRoute is CastSessionManager.Route.ThisDevice) {
                                Toast.makeText(context, "Choose a receiver first", Toast.LENGTH_SHORT).show()
                                return@onStartQueue
                            }
                            // External protocols use phone-driven episode advance: the coordinator
                            // watches status and resolves/loads the next episode on the same target.
                            activeExternalDevice?.let { external ->
                                externalQueueCoordinator.start(plan)
                                Toast.makeText(
                                    context,
                                    "Casting to ${external.name} — episodes will auto-advance",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (autoSwitchToRemote) onScreenChange(Screen.Remote)
                                return@onStartQueue
                            }
                            scope.launch {
                                // Fetch the start episode's subtitles in parallel with connecting.
                                val startSubsDeferred = async {
                                    val sendSubtitles = runCatching { settingsRepository.sendSubtitlesToTv.first() }.getOrDefault(true)
                                    if (!sendSubtitles) return@async emptyList<String>()
                                    subtitleService.getAllSubtitleUrls(
                                        current.visual_metadata?.imdb_id,
                                        current.visual_metadata?.season,
                                        current.visual_metadata?.episode,
                                        preferredSubLang,
                                        videoRelease = subtitleService.filenameFromUrl(current.url)
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
                                linkedPageCastCoordinator.supersedeIfActive()
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
                            if (castRoute is CastSessionManager.Route.ThisDevice) {
                                Toast.makeText(context, "Choose a receiver first", Toast.LENGTH_SHORT).show()
                                return@onSendStream
                            }
                            activeExternalDevice?.let { external ->
                                connectionViewModel.castToSelectedExternal(
                                    MediaItem(
                                        url = url,
                                        headers = headers ?: emptyMap(),
                                        // contentType here is the play-payload kind ("movie"/"series");
                                        // only forward real MIME types to the proxy.
                                        mimeType = contentType?.takeIf { it.contains('/') },
                                        title = title,
                                    )
                                )
                                Toast.makeText(context, "Casting to ${external.name}", Toast.LENGTH_SHORT).show()
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
                                linkedPageCastCoordinator.supersedeIfActive()
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
                            // A full Hub playlist replaces the TV queue, so any lazy binge plan
                            // still held by the episode coordinators is now stale — tear it down
                            // the same way single-item cast does. Without this, an earlier no-Hub
                            // series session keeps `queue_add`-ing onto the Hub list (often the
                            // same S/E at a different stream URL, which used to show as duplicates).
                            tvQueueCoordinator.stop()
                            externalQueueCoordinator.stop()
                            if (castRoute is CastSessionManager.Route.ThisDevice) {
                                Toast.makeText(context, "Choose a receiver first", Toast.LENGTH_SHORT).show()
                                return@onPlayPlaylist
                            }
                            // External protocols use the phone-driven advancer (items already carry
                            // resolved/Hub URLs).
                            activeExternalDevice?.let { external ->
                                if (playlist.items.isEmpty()) return@onPlayPlaylist
                                externalQueueCoordinator.start(
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
                                    "Casting to ${external.name} — episodes will auto-advance",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (autoSwitchToRemote) onScreenChange(Screen.Remote)
                                return@onPlayPlaylist
                            }
                            scope.launch {
                                // Fetch the start episode's subtitles in parallel with connecting.
                                val startItem = playlist.items.getOrNull(playlist.start_index)
                                val startVm = startItem?.visual_metadata
                                val startSubsDeferred = async {
                                    val sendSubtitles = runCatching { settingsRepository.sendSubtitlesToTv.first() }.getOrDefault(true)
                                    if (!sendSubtitles) return@async emptyList<String>()
                                    subtitleService.getAllSubtitleUrls(
                                        startVm?.imdb_id, startVm?.season, startVm?.episode, preferredSubLang,
                                        videoRelease = subtitleService.filenameFromUrl(startItem?.url)
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
                                linkedPageCastCoordinator.supersedeIfActive()
                                if (connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createPlaylistCommandJson(finalPlaylist))) {
                                    connectionCoordinator.startLocalPlaybackSession(
                                        tmdbId = screenNumericId,
                                        // No fabricated fallback: a metadata-less start item must
                                        // stay unidentified rather than be attributed to season 1.
                                        season = playlist.items.getOrNull(playlist.start_index)?.visual_metadata?.season,
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
                        onQueueAdd = onQueueAdd@{ item ->
                            if (castRoute !is CastSessionManager.Route.NativeTv) {
                                Toast.makeText(
                                    context,
                                    "Queue editing is only available with PlayBridge receivers",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@onQueueAdd
                            }
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
                    // The Dashboard is "home" — Back here double-taps to exit (soft finish;
                    // an active cast keeps running in the background).
                    BackHandler {
                        val now = System.currentTimeMillis()
                        if (now - backPressedTime > 2000) {
                            onBackPressedTimeChange(now)
                            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                        } else {
                            onFinishActivity()
                        }
                    }
                    val isConnected = connectionState is WebSocketClient.ConnectionState.Connected
                    DashboardScreen(
                        currentScreen = lastMainScreen,
                        isConnected = isConnected,
                        isSecure = (connectionState as? WebSocketClient.ConnectionState.Connected)?.secure == true,
                        connectedDeviceName = tvDevice?.name,
                        onNavigate = { screen ->
                            onScreenChange(screen)
                        },
                        onExit = onFullExit,
                        onClose = { onScreenChange(dashboardReturnScreen) },
                        onSettings = {
                            // So Settings back lands on Dashboard (not Browser/Library).
                            onLastMainScreenChange(Screen.Dashboard)
                            onScreenChange(Screen.Settings)
                        },
                    )
                }
                Screen.ScreenMirror -> {
                    BackHandler { onScreenChange(Screen.Dashboard) }
                    ScreenMirrorScreen(onBack = { onScreenChange(Screen.Dashboard) })
                }
                Screen.PhoneFiles -> {
                    BackHandler { onScreenChange(Screen.Dashboard) }
                    PhoneFilesScreen(
                        viewModel = connectionViewModel,
                        uiState = phoneFilesUiState,
                        onBack = { onScreenChange(Screen.Dashboard) },
                        onOpenAllDevices = { openAllDevicesExpanded() },
                        onAddToCollection = { media ->
                            onAddToCollection(
                                com.playbridge.sender.data.collection.CollectionItemDraft(
                                    title = media.title,
                                    url = media.uri.toString(),
                                    kind = com.playbridge.sender.data.collection.CollectionItemKind.LOCAL,
                                    mimeType = media.mimeType,
                                    sourceTag = com.playbridge.sender.data.collection.CollectionSource.PHONE_FILE,
                                )
                            )
                        },
                    )
                }
                Screen.DebridLibrary -> {
                    BackHandler { onScreenChange(Screen.Dashboard) }
                    DebridLibraryScreen(
                        onMenuClick = { onScreenChange(Screen.Dashboard) },
                        onCopyUrl = { linkUrl ->
                            clipboardManager.setText(AnnotatedString(linkUrl))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                        },
                        onShowCastSheet = { video ->
                            onForcedVideosChange(listOf(video))
                            onShowVideoSheetChange(true)
                        },
                        onAddToCollection = { title, url ->
                            onAddToCollection(
                                com.playbridge.sender.data.collection.CollectionItemDraft(
                                    title = title,
                                    url = url,
                                    kind = com.playbridge.sender.data.collection.CollectionItemKind.WEB,
                                    sourceTag = com.playbridge.sender.data.collection.CollectionSource.DEBRID,
                                )
                            )
                        }
                    )
                }
                Screen.Iptv -> {
                    BackHandler { onScreenChange(Screen.Dashboard) }
                    com.playbridge.sender.iptv.IptvScreen(
                        viewModel = iptvViewModel,
                        onBack = { onScreenChange(Screen.Dashboard) },
                        onOpenPlaylist = { id -> onScreenChange(Screen.IptvDetail(id)) },
                    )
                }
                is Screen.IptvDetail -> {
                    val detail = targetScreen as Screen.IptvDetail
                    BackHandler { onScreenChange(Screen.Iptv) }
                    com.playbridge.sender.iptv.IptvDetailScreen(
                        playlistId = detail.playlistId,
                        viewModel = iptvViewModel,
                        connectionViewModel = connectionViewModel,
                        collectionsViewModel = collectionsViewModel,
                        onBack = { onScreenChange(Screen.Iptv) },
                    )
                }
                Screen.Collections -> {
                    BackHandler { onScreenChange(Screen.Dashboard) }
                    com.playbridge.sender.collection.CollectionsScreen(
                        viewModel = collectionsViewModel,
                        onBack = { onScreenChange(Screen.Dashboard) },
                        onOpenCollection = { id -> onScreenChange(Screen.CollectionDetail(id)) },
                    )
                }
                is Screen.CollectionDetail -> {
                    val detail = targetScreen as Screen.CollectionDetail
                    BackHandler { onScreenChange(Screen.Collections) }
                    com.playbridge.sender.collection.CollectionDetailScreen(
                        collectionId = detail.collectionId,
                        viewModel = collectionsViewModel,
                        connectionViewModel = connectionViewModel,
                        onBack = { onScreenChange(Screen.Collections) },
                    )
                }
            }

            // ── Cast mini-bar ────────────────────────────────────────────────────
            // Permanent across the main (non-browser) screens. Two modes:
            //  • Playing (a cast session has media loaded): title + "on <device>" +
            //    play/pause; tap → Remote.
            //  • Idle (nothing playing): shows the current destination (connected TV /
            //    renderer, or "This Device" when nothing is connected); tap → device picker.
            // Hidden on Browser except while a website-linked session needs a visible owner/unlink
            // affordance; hidden on Remote (redundant) and full-bleed screens.
            // Note: excluded on Dashboard (overlays the "Exit" button) and on Connection
            // (the device picker lives there already).
            // Hide on Library Addons tab so the bar doesn't overlay addon management.
            val libSelectedTab by libraryViewModel.selectedTab.collectAsState()
            val libraryAddonsTab = targetScreen == Screen.Library && libSelectedTab == 3
            val showNowPlayingBar = ((targetScreen == Screen.Browser && linkedPageCastState.active) ||
                targetScreen == Screen.Library ||
                targetScreen == Screen.PhoneFiles ||
                targetScreen == Screen.DebridLibrary ||
                targetScreen is Screen.LibraryDetail ||
                targetScreen == Screen.Iptv ||
                targetScreen is Screen.IptvDetail ||
                targetScreen == Screen.Collections ||
                targetScreen is Screen.CollectionDetail) && !libraryAddonsTab
            if (showNowPlayingBar) {
                val externalActive = activeExternalDevice != null
                // A stopped/ended/errored renderer (incl. after Stop) is not "playing", even
                // though the status poll keeps reporting a (STOPPED) status.
                val externalState = externalStatus?.state
                val externalStopped = externalState == PlaybackState.STOPPED ||
                    externalState == PlaybackState.IDLE ||
                    externalState == PlaybackState.ERROR
                val externalHasMedia = externalActive && externalMediaTitle != null && !externalStopped
                val wsConnected = connectionState is WebSocketClient.ConnectionState.Connected
                val nativeSelected = castRoute is CastSessionManager.Route.NativeTv
                val nativePlaying = tvActiveContext == "player" && wsConnected && nativeSelected
                val playing = externalHasMedia || nativePlaying

                // Pause + progress for the bar: freeze the equalizer while paused and
                // drive the bottom progress line off the live status snapshots.
                val paused =
                    if (externalActive) externalState == PlaybackState.PAUSED
                    else tvPlayback?.state == "paused"
                val playbackProgress = when {
                    externalActive -> externalStatus?.takeIf { it.durationMs > 0 }
                        ?.let { it.positionMs.toFloat() / it.durationMs }
                    else -> tvPlayback?.takeIf { it.durationMs > 0 }
                        ?.let { it.positionMs.toFloat() / it.durationMs }
                }

                val deviceName = activeExternalDevice?.name ?: if (nativeSelected) tvDevice?.name else null
                val protocolName = activeExternalDevice?.resolvedProtocol?.displayName
                    ?: CastProtocol.PLAYBRIDGE.displayName.takeIf { nativeSelected }
                val leadingIcon = when {
                    externalActive -> Icons.Default.Cast
                    nativeSelected && wsConnected -> Icons.Default.Tv
                    else -> Icons.Default.Smartphone
                }

                val primaryText: String
                val secondaryText: String?
                when {
                    playing -> {
                        primaryText = (if (externalActive) externalMediaTitle else tvPlayback?.title) ?: "Now playing"
                        secondaryText = linkedPageCastState.controllerName?.let {
                            "Controlled by $it · on ${deviceName ?: "TV"}"
                        } ?: "on ${deviceName ?: "TV"}" +
                            protocolName?.let { " · $it" }.orEmpty()
                    }
                    externalActive || (nativeSelected && wsConnected) -> {
                        primaryText = deviceName ?: "TV"
                        secondaryText = protocolName?.let { "$it · Ready to cast" } ?: "Ready to cast"
                    }
                    else -> {
                        primaryText = "This Device"
                        secondaryText = "Tap to cast to a device"
                    }
                }

                NowPlayingBar(
                    primaryText = primaryText,
                    secondaryText = secondaryText,
                    leadingIcon = leadingIcon,
                    onClick = {
                        if (playing) {
                            if (!externalActive) {
                                connectionViewModel.webSocketClient.send(
                                    com.playbridge.shared.protocol.createContextQueryJson()
                                )
                            }
                            onScreenChange(Screen.Remote)
                        } else {
                            // Idle → choose / switch the cast destination.
                            showDevicePicker = true
                        }
                    },
                    isPlaying = playing,
                    isPaused = paused,
                    progress = if (playing) playbackProgress else null,
                    showTvIcon = playing,
                    onTvIconClick = {
                        showDevicePicker = true
                    },
                    onUnlinkClick = if (linkedPageCastState.active) {
                        { linkedPageCastCoordinator.unlink("unlinked") }
                    } else {
                        null
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

    // Shared Add-to-Collection sheet, driven by any screen's onAddToCollection(draft).
    addToCollectionDraft?.let { draft ->
        com.playbridge.sender.collection.AddToCollectionSheet(
            viewModel = collectionsViewModel,
            draft = draft,
            onDismiss = { addToCollectionDraft = null },
            onAdded = { name, added ->
                Toast.makeText(
                    context,
                    if (added) "Added to $name" else "Already in $name",
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
}

/**
 * Manage the user scripts installed on the TV: list them, remove one, or install another
 * from a file on the phone. The app ships no scripts — the user supplies the .js files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserScriptsSheet(
    scripts: List<String>,
    onInstall: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "User scripts on TV",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        if (scripts.isEmpty()) {
            Text(
                "None installed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        } else {
            scripts.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemove(name) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove $name", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        TextButton(onClick = onInstall, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Install from file…")
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Manage the TV browser's User-Agent remotely: pick a built-in preset, reselect something
 * already saved on the TV, or add a new custom one. Mirrors [UserScriptsSheet]'s remote
 * pattern — the TV is the source of truth for [active]/[savedEntries]; this sheet just
 * sends `user_agent` messages and waits for the next `user_agents` reply to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvUserAgentSheet(
    active: String,
    savedEntries: List<Pair<String, String>>,
    onSelectDefault: () -> Unit,
    onSelectPreset: (name: String, value: String) -> Unit,
    onApplyNamed: (name: String, value: String) -> Unit,
    onRemove: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text(
                "User Agent (TV Browser)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                "Changes what sites think the TV's browser is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider()

            TvUserAgentRow(
                label = "Default (Mobile)",
                selected = active.isBlank(),
                onClick = onSelectDefault,
            )
            com.playbridge.sender.browser.UserAgentPresets.presets
                .filter { it.value != null }
                .forEach { preset ->
                    TvUserAgentRow(
                        label = preset.label,
                        selected = active == preset.label,
                        onClick = { onSelectPreset(preset.label, preset.value!!) },
                    )
                }

            if (savedEntries.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Saved on TV",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                savedEntries.forEach { (name, value) ->
                    TvUserAgentRow(
                        label = name,
                        selected = active == name,
                        onClick = { onApplyNamed(name, value) },
                        trailing = {
                            IconButton(onClick = { onRemove(name) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove $name",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            TextButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add custom user agent")
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add custom user agent") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. My Custom UA") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("User agent string") },
                        placeholder = { Text("Mozilla/5.0 (...) ...") },
                        singleLine = false,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApplyNamed(name.trim(), value.trim())
                        showAddDialog = false
                    },
                    enabled = name.isNotBlank() && value.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TvUserAgentRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        trailing?.invoke()
    }
}
