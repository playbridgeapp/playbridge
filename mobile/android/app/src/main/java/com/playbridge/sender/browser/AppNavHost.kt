package com.playbridge.sender.browser

import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteController
import com.playbridge.sender.cast.remote.RemoteControllerRegistry
import com.playbridge.sender.cast.remote.receiverActionSupported
import com.playbridge.sender.cast.remote.ready
import com.playbridge.sender.cast.remote.ui.UserScriptsSheet
import com.playbridge.sender.cast.remote.ui.TvUserAgentSheet

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.data.history.BookmarkDao
import com.playbridge.sender.data.history.BookmarkEntity
import com.playbridge.sender.data.history.HistoryDao
import com.playbridge.sender.data.history.HistoryEntity
import com.playbridge.sender.downloads.DownloadsScreen
import com.playbridge.sender.history.BookmarksScreen
import com.playbridge.sender.history.CastHistoryScreen
import com.playbridge.sender.diagnostics.CastAttemptDiagnostics
import com.playbridge.sender.diagnostics.shareCastAttempt
import com.playbridge.sender.history.HistoryScreen
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.settings.SettingsScreen
import com.playbridge.sender.ui.DashboardScreen
import com.playbridge.sender.ui.rememberDashboardTileStates
import com.playbridge.sender.cast.mirror.ScreenMirrorScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import com.playbridge.sender.cast.*
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import com.playbridge.sender.data.settings.SettingsRepository
import kotlin.math.roundToInt

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
@Composable
fun AppNavHost(
    // Navigation & Layout States
    currentScreen: Screen,
    onScreenChange: (Screen) -> Unit,
    lastMainScreen: Screen,
    onLastMainScreenChange: (Screen) -> Unit,
    // Restores the source screen and, when applicable, its bridged app session.
    onRemoteBack: () -> Unit,
    // Where Back from the Dashboard returns — the screen it was opened from.
    onDashboardClose: () -> Unit = { },
    /** The Dashboard was opened from another screen, so Back returns there. */
    dashboardCanReturn: Boolean = false,
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
    isBridgedAppMode: Boolean = false,
    onAppSettings: () -> Unit = {},
    bridgedApps: List<BridgedApp> = emptyList(),
    onOpenBridgedApp: (BridgedApp) -> Unit = {},
    onRemoveBridgedApp: (BridgedApp) -> Unit = {},
    onEditBridgedApp: (BridgedApp, String, String, String?) -> Boolean = { _, _, _, _ -> false },
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
    remoteCastSourceTabId: String? = null,
    remoteCastTitle: String? = null,

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
    urlPanelClipboard: ClipboardSuggestion?,
    onUrlPanelClipboardChange: (ClipboardSuggestion?) -> Unit,
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
    var addToCollectionDrafts by remember { mutableStateOf<List<com.playbridge.sender.data.collection.CollectionItemDraft>>(emptyList()) }
    val onAddToCollection: (com.playbridge.sender.data.collection.CollectionItemDraft) -> Unit =
        { addToCollectionDrafts = listOf(it) }
    val connectionCoordinator: ConnectionCoordinator = koinInject()
    val playbackFailure by connectionCoordinator.playbackFailure.collectAsStateWithLifecycle()
    val historyDao: HistoryDao = koinInject()
    val bookmarkDao: BookmarkDao = koinInject()
    val settingsRepository: SettingsRepository = koinInject()
    val linkedPageCastCoordinator: LinkedPageCastCoordinator = koinInject()
    val phoneQueueCoordinator: com.playbridge.sender.cast.PhoneQueueCoordinator = koinInject()
    /** The mini player's Up next pill opens the Remote with Up next showing. */
    var openRemoteQueue by remember { mutableStateOf(false) }
    LaunchedEffect(phoneQueueCoordinator) {
        phoneQueueCoordinator.saveRequests.collect { items ->
            addToCollectionDrafts = items.map { item ->
                com.playbridge.sender.data.collection.CollectionItemDraft(
                    title = item.title,
                    url = item.uri,
                    kind = com.playbridge.sender.data.collection.CollectionItemKind.LOCAL,
                    mimeType = item.mime,
                    sourceTag = com.playbridge.sender.data.collection.CollectionSource.PHONE_FILE,
                )
            }
        }
    }
    val linkedPageCastState by linkedPageCastCoordinator.uiState.collectAsStateWithLifecycle()

    // 2. Collect Live Flows / Collected states locally.
    // Perf: only connection-level state is collected at the host root. High-frequency
    // per-second TV tickers (tvPlayback, tvPlaylistState, tracks, nowPlaying*) are collected
    // locally inside the Remote branch and the NowPlayingBar so ticks don't recompose the
    // whole AnimatedContent + current screen.
    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()
    val tvDevice by connectionViewModel.tvDevice.collectAsStateWithLifecycle(initialValue = null)
    val activeExternalDevice by connectionViewModel.activeExternalDevice.collectAsStateWithLifecycle()
    val castRoute by connectionViewModel.route.collectAsStateWithLifecycle()
    LaunchedEffect(connectionViewModel) {
        connectionViewModel.independentRemoteRequests.collect { onScreenChange(Screen.Remote) }
    }
    val selectedRemoteDevice by connectionViewModel.selectedRemoteDevice.collectAsStateWithLifecycle()
    val remoteTVRows by connectionViewModel.remoteTVDirectory.rows.collectAsStateWithLifecycle()
    val remoteTV = remoteTVRows.firstOrNull { it.device.endpointKey == selectedRemoteDevice?.endpointKey && castRoute is CastSessionManager.Route.NativeTv }
    // Authoritative routing intent — reactive so screens follow device-picker changes live.
    val routeTargetsTv by connectionViewModel.routeTargetsTv.collectAsStateWithLifecycle()
    val allHistory by historyDao.getAll().collectAsStateWithLifecycle(initialValue = emptyList())

    val context = LocalContext.current

    // Manager reads and writes use the same live resolved receiver authority as Remote.
    val managerRegistry: RemoteControllerRegistry = koinInject()
    val managerController by managerRegistry.controller.collectAsStateWithLifecycle()
    val managerState = managerController?.snapshot?.collectAsStateWithLifecycle()?.value?.state
    fun canManage(action: RemoteAction) = managerState?.connection?.ready == true && managerState.receiverActionSupported(action)
    fun sendManager(action: RemoteAction, owner: RemoteController? = managerController, complete: (Boolean) -> Unit = {}) {
        if (owner == null || owner !== managerRegistry.controller.value) { complete(false); return }
        scope.launch {
            val result = if (owner === managerRegistry.controller.value) owner.execute(action) else null
            complete(result == com.playbridge.sender.cast.remote.RemoteCommandResult.Submitted || result == com.playbridge.sender.cast.remote.RemoteCommandResult.Confirmed)
        }
    }
    var scriptPickerOwner by remember { mutableStateOf<RemoteController?>(null) }
    val installedUserScripts by connectionCoordinator.installedUserScripts.collectAsStateWithLifecycle()
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
                    val owner = scriptPickerOwner
                    scriptPickerOwner = null
                    sendManager(RemoteAction.UpdateUserScript(name, content), owner) { sent ->
                        android.widget.Toast.makeText(context, if (sent) "Sent $name to TV" else "TV browser is no longer available", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.widget.Toast.makeText(context, "Script file was empty", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Couldn't read script: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showUserScripts && canManage(RemoteAction.ManageUserScripts)) {
        UserScriptsSheet(
            scripts = installedUserScripts,
            onInstall = { scriptPickerOwner = managerController; userScriptPicker.launch("*/*") },
            onRemove = { name ->
                // Empty content tells the TV to delete that script.
                sendManager(RemoteAction.UpdateUserScript(name, ""))
            },
            onDismiss = { showUserScripts = false }
        )
    }

    val tvUserAgentState by connectionCoordinator.tvUserAgentState.collectAsStateWithLifecycle()
    var showTvUserAgent by remember { mutableStateOf(false) }

    if (showTvUserAgent && canManage(RemoteAction.ManageUserAgent)) {
        TvUserAgentSheet(
            active = tvUserAgentState.active,
            savedEntries = tvUserAgentState.entries,
            onSelectDefault = {
                sendManager(RemoteAction.UpdateUserAgent("", "", false))
            },
            onSelectPreset = { name, value ->
                // Built-in presets apply without cluttering the TV's saved list.
                sendManager(RemoteAction.UpdateUserAgent(name, value, false))
            },
            onApplyNamed = { name, value ->
                // Re-selecting a saved entry or adding a brand-new custom one — either way
                // the TV applies it and (re)saves it under that name.
                sendManager(RemoteAction.UpdateUserAgent(name, value, true))
            },
            onRemove = { name ->
                // Blank value tells the TV to delete that saved entry.
                sendManager(RemoteAction.UpdateUserAgent(name, "", true))
            },
            onDismiss = { showTvUserAgent = false }
        )
    }

    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val preferredAudioLang by settingsRepository.preferredAudioLang.collectAsStateWithLifecycle(initialValue = "")
    val preferredSubLang by settingsRepository.preferredSubtitleLang.collectAsStateWithLifecycle(initialValue = "")
    val defaultVideoQuality by settingsRepository.defaultVideoQuality.collectAsStateWithLifecycle(initialValue = "Auto")
    val maxBitrateCapMbps by settingsRepository.maxBitrateCapMbps.collectAsStateWithLifecycle(initialValue = 0.0)
    val autoSwitchToRemote by settingsRepository.autoSwitchToRemote.collectAsStateWithLifecycle(initialValue = true)
    val tvPlayerMode by settingsRepository.tvPlayerMode.collectAsStateWithLifecycle(initialValue = "tv")
    val selectedTab = store.state.tabs.find { it.id == store.state.selectedTabId }

    // State to determine if search focus should be requested
    var shouldFocusSearch by remember { mutableStateOf(false) }

    // NowPlayingBar to match while that screen is up.

    // Phone Files UI state, hoisted here so the user's tab/search/sort/folder/scroll survive
    // leaving and returning to the screen (AnimatedContent disposes the screen content).
    val phoneFilesUiState = remember { PhoneFilesUiState() }

    // Device picker opened from the idle cast bar (rendered once at the host level so
    // it survives screen transitions in the AnimatedContent below).
    var showDevicePicker by remember { mutableStateOf(false) }
    var devicePickerIsPicking by remember { mutableStateOf(false) }
    val playbackPickerRequest by Components.playbackDevicePicker.requests.collectAsStateWithLifecycle()
    LaunchedEffect(playbackPickerRequest) {
        if (Components.playbackDevicePicker.consume(playbackPickerRequest)) {
            devicePickerIsPicking = true
            showDevicePicker = true
        }
    }
    val linkedDevicePickerRequest by Components.linkedDevicePicker.requests.collectAsStateWithLifecycle()
    var linkedPickerVisible by remember { mutableStateOf(false) }
    var linkedPickerCompleted by remember { mutableStateOf(false) }
    LaunchedEffect(linkedDevicePickerRequest) {
        if (Components.linkedDevicePicker.consume(linkedDevicePickerRequest)) {
            linkedPickerCompleted = false
            linkedPickerVisible = true
        }
    }
    if (showDevicePicker) {
        PlayOnSheet(
            onDismiss = { showDevicePicker = false; devicePickerIsPicking = false },
            picking = devicePickerIsPicking,
            showThisDevice = true,
        )
    }
    if (linkedPickerVisible) {
        PlayOnSheet(
            onDismiss = {
                linkedPickerVisible = false
                if (!linkedPickerCompleted) Components.onLinkedDevicePickerDismissed?.invoke()
                linkedPickerCompleted = false
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

    val fullscreenBrowser = currentScreen == Screen.Browser && isFullscreen
    var fullscreenInteraction by remember(currentScreen, isFullscreen) { mutableIntStateOf(0) }
    fun leaveMediaFullscreen() {
        if (isFullscreen) {
            onIsFullscreenChange(false)
            session?.exitFullScreenMode()
        }
    }
    // Browser/Tabs/Extensions use the Activity's Scaffold toolbar; other screens own theirs.
    val bannerTopPadding = if (currentScreen in listOf(Screen.Browser, Screen.Tabs, Screen.Extensions)) {
        innerPadding.calculateTopPadding()
    } else 0.dp
    Column(Modifier.fillMaxSize().padding(top = if (playbackFailure != null) bannerTopPadding else 0.dp)) {
        playbackFailure?.let { failure ->
            com.playbridge.sender.ui.ReceiverPlaybackFailureBanner(
                message = failure.message,
                onDismiss = connectionCoordinator::dismissPlaybackFailure,
                modifier = if (bannerTopPadding > 0.dp) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )
        }
    Box(
        modifier = Modifier.weight(1f).fillMaxWidth()
            .then(if (playbackFailure != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
            .observeFullscreenInteractions(fullscreenBrowser) { fullscreenInteraction++ },
    ) {
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            // Perf: heavy destinations (Gecko AndroidView, poster grids, blurred detail)
            // keep two full trees alive during the transition — fade only, no slide.
            val heavy = initialState.isHeavyScreen() || targetState.isHeavyScreen()
            if (heavy) {
                fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(250))
            } else if (targetState == Screen.Tabs && initialState == Screen.Browser) {
                slideInVertically { height -> height } + fadeIn() togetherWith
                        slideOutVertically { height -> -height } + fadeOut()
            } else if (targetState == Screen.Browser && initialState == Screen.Tabs) {
                slideInVertically { height -> -height } + fadeIn() togetherWith
                        slideOutVertically { height -> height } + fadeOut()
            } else if ((targetState == Screen.Downloads || targetState == Screen.Extensions || targetState == Screen.Settings || targetState == Screen.Bookmarks || targetState == Screen.Remote) && (initialState == Screen.Browser)) {
                androidx.compose.animation.slideInHorizontally { width -> width } + fadeIn() togetherWith
                        androidx.compose.animation.slideOutHorizontally { width -> -width } + fadeOut()
            } else if ((targetState == Screen.Browser) && (initialState == Screen.Downloads || initialState == Screen.Extensions || initialState == Screen.Settings || initialState == Screen.Bookmarks || initialState == Screen.Remote)) {
                androidx.compose.animation.slideInHorizontally { width -> -width } + fadeIn() togetherWith
                        androidx.compose.animation.slideOutHorizontally { width -> width } + fadeOut()
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "ScreenTransition"
    ) { targetScreen ->
        val isOwnTopBar = targetScreen !in listOf(Screen.Browser, Screen.Tabs, Screen.Extensions)
        val resolvedPadding = if (isOwnTopBar || playbackFailure != null) {
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
                    BackHandler(enabled = !isFullscreen && isBridgedAppMode) {
                        if (browserCanGoBack) session?.goBack() else onScreenChange(Screen.Dashboard)
                    }
                    BackHandler(enabled = !isFullscreen && isBrowserChromeHidden && !isBridgedAppMode) {
                        onIsBrowserChromeHiddenChange(false)
                    }
                    BackHandler(enabled = !isFullscreen && !isBrowserChromeHidden && !isBridgedAppMode && !isEditing) {
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

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        if (targetScreen == Screen.Browser && session != null) {
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

                        if (isBrowserChromeHidden && !isFullscreen && !isBridgedAppMode) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                            ) {
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
                                                                    .crossfade(false)
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
                                                val headline = when (urlPanelClipboard) {
                                                    is ClipboardSuggestion.Link -> "Link you copied"
                                                    is ClipboardSuggestion.Text -> "Text you copied"
                                                }
                                                val displayText = when (urlPanelClipboard) {
                                                    is ClipboardSuggestion.Link -> urlPanelClipboard.url
                                                    is ClipboardSuggestion.Text -> urlPanelClipboard.query
                                                }
                                                val leadingIcon = when (urlPanelClipboard) {
                                                    is ClipboardSuggestion.Link -> Icons.Default.Link
                                                    is ClipboardSuggestion.Text -> Icons.Default.Search
                                                }
                                                val trailingIcon = when (urlPanelClipboard) {
                                                    is ClipboardSuggestion.Text -> Icons.Default.Search
                                                    else -> Icons.Default.OpenInBrowser
                                                }
                                                val trailingDescription = when (urlPanelClipboard) {
                                                    is ClipboardSuggestion.Text -> "Search"
                                                    is ClipboardSuggestion.Link -> "Open link"
                                                }
                                                val handleSuggestionClick = {
                                                    when (urlPanelClipboard) {
                                                        is ClipboardSuggestion.Link -> {
                                                            session?.loadUrl(urlPanelClipboard.url)
                                                        }
                                                        is ClipboardSuggestion.Text -> {
                                                            session?.loadUrl(searchUrl(urlPanelClipboard.query))
                                                        }
                                                    }
                                                    onIsEditingChange(false)
                                                    keyboardController?.hide()
                                                    focusManager.clearFocus()
                                                }
                                                ListItem(
                                                    headlineContent = {
                                                        Text(
                                                            headline,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    },
                                                    supportingContent = {
                                                        Text(
                                                            displayText,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    },
                                                    leadingContent = {
                                                        Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    },
                                                    trailingContent = {
                                                        IconButton(onClick = handleSuggestionClick) {
                                                            Icon(trailingIcon, contentDescription = trailingDescription)
                                                        }
                                                    },
                                                    modifier = Modifier.clickable(onClick = handleSuggestionClick)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(suggestions, key = { it.url }, contentType = { "history" }) { historyItem ->
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
                    val castDiagnostics: CastAttemptDiagnostics = koinInject()
                    val replayStore: com.playbridge.sender.history.CastReplayStore = koinInject()
                    val castAttempts by castDiagnostics.attempts.collectAsStateWithLifecycle()
                    val replaySources by replayStore.sources.collectAsStateWithLifecycle()
                    val commandHistoryFlow = remember { db.commandHistoryDao().getAll() }
                    val commandHistory by commandHistoryFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                    CastHistoryScreen(
                        historyItems = commandHistory,
                        attempts = castAttempts,
                        replaySources = replaySources,
                        onMenuClick = { onScreenChange(Screen.Dashboard) },
                        onShareAttempt = { attempt ->
                            castDiagnostics.report(attempt.id)?.let { shareCastAttempt(context, it) }
                        },
                        onRecastAttempt = { attempt ->
                            val source = replaySources[attempt.id]
                            if (source != null) {
                                val playlist = source.playlistPayloadJson?.let {
                                    com.playbridge.shared.protocol.decodePlaylistPayloadJson(it)?.items
                                }
                                val media = DetectedVideo(
                                    url = if (playlist != null) "playlist://history-${attempt.id}" else source.url,
                                    title = source.title,
                                    contentType = source.contentType,
                                    headers = source.headers,
                                    mediaKind = source.mediaKind,
                                    detectedBy = "history",
                                    timestamp = attempt.startedAtMs,
                                    isPlayable = if (playlist != null) true else null,
                                    playlistPayload = playlist,
                                )
                                val subtitles = if (playlist == null) source.subtitles.map { subtitleUrl ->
                                    DetectedVideo(
                                        url = subtitleUrl,
                                        mediaKind = "subtitle",
                                        detectedBy = "history",
                                        timestamp = attempt.startedAtMs,
                                    )
                                } else emptyList()
                                onForcedVideosChange(listOf(media) + subtitles)
                                onCastSheetBrowseOverrideChange(null)
                                onCastSheetInitialModeChange("play")
                                onShowVideoSheetChange(true)
                            }
                        },
                        onDeleteAttempt = { attempt ->
                            castDiagnostics.delete(attempt.id)
                            replayStore.delete(attempt.id)
                        },
                        onItemClick = { item ->
                            if (item.commandType == "browser") {
                                onForcedVideosChange(null)
                                onCastSheetBrowseOverrideChange(item.url)
                                onCastSheetInitialModeChange("browse")
                            } else {
                                onForcedVideosChange(listOf(
                                    DetectedVideo(
                                        url = item.url,
                                        title = item.title,
                                        detectedBy = "history",
                                        timestamp = item.timestamp
                                    )
                                ))
                                onCastSheetInitialModeChange("play")
                            }
                            onShowVideoSheetChange(true)
                        },
                        onDelete = { item ->
                            scope.launch(Dispatchers.IO) { db.commandHistoryDao().delete(item) }
                        },
                        onClearHistory = {
                            scope.launch(Dispatchers.IO) { db.commandHistoryDao().clear() }
                            castDiagnostics.clear()
                            replayStore.clear()
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
                    val bridgedAppTabIds = bridgedApps.mapNotNull { it.tabId }.toSet()
                    TabsScreen(
                        hiddenTabIds = bridgedAppTabIds,
                        onTabSelected = { tabId ->
                            tabManager.selectTab(tabId, store)
                            onScreenChange(Screen.Browser)
                        },
                        onTabClosed = { tabId ->
                            tabManager.closeTab(tabId, store, hiddenTabIds = bridgedAppTabIds)
                            if (store.state.tabs.none { it.id !in bridgedAppTabIds }) {
                                tabManager.createTab("about:blank", store)
                            }
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
                Screen.Downloads -> {
                    BackHandler { onScreenChange(lastMainScreen) }
                    DownloadsScreen(
                        onBack = { onScreenChange(lastMainScreen) },
                        onOpenRemote = { onScreenChange(Screen.Remote) },
                        // New tab: works with no tabs open and keeps the current page.
                        onOpenUrl = { url ->
                            tabManager.createTab(url, store)
                            onScreenChange(Screen.Browser)
                        },
                    )
                }
                Screen.Settings -> {
                    // Global Settings (Dashboard gear only). Back returns to where Settings was
                    // opened from — typically Dashboard when using the gear.
                    BackHandler { onScreenChange(lastMainScreen) }
                    SettingsScreen(
                        onBack = { onScreenChange(lastMainScreen) },
                        // TV logs travel only over the paired connection.
                        tvLogs = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                            { clear: Boolean -> connectionCoordinator.requestTvLogs(clear) }
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
                    BackHandler(onBack = onRemoteBack)
                    com.playbridge.sender.cast.remote.RemoteRoute(
                        openQueue = openRemoteQueue,
                        onQueueOpened = { openRemoteQueue = false },
                        subtitleSourceKey = Triple(remoteCastSourceTabId, remoteCastTitle, VideoDetector.processingVersion),
                        detectedSubtitles = { title ->
                            if (remoteCastSourceTabId != null && remoteCastTitle == title) {
                                VideoDetector.getVideosForTab(remoteCastSourceTabId).filter { it.isSubtitle }
                            } else emptyList()
                        },
                        onEffect = { effect ->
                            when (effect) {
                                is com.playbridge.sender.cast.remote.RemoteEffect.Navigate -> when (effect.destination) {
                                    com.playbridge.sender.cast.remote.RemoteEffect.Navigate.Destination.Origin -> onRemoteBack()
                                    com.playbridge.sender.cast.remote.RemoteEffect.Navigate.Destination.Connections -> showDevicePicker = true
                                }
                                is com.playbridge.sender.cast.remote.RemoteEffect.OpenManager -> when (effect.manager) {
                                    com.playbridge.sender.cast.remote.RemoteEffect.OpenManager.Manager.UserScripts -> showUserScripts = true
                                    com.playbridge.sender.cast.remote.RemoteEffect.OpenManager.Manager.UserAgent -> showTvUserAgent = true
                                }
                                com.playbridge.sender.cast.remote.RemoteEffect.UnlinkWebsite -> linkedPageCastCoordinator.unlink("unlinked")
                                com.playbridge.sender.cast.remote.RemoteEffect.RetryConnection -> {
                                    if (castRoute is CastSessionManager.Route.External || selectedRemoteDevice == null) {
                                        showDevicePicker = true
                                    } else connectionViewModel.retryIndependentRemote()
                                }
                                is com.playbridge.sender.cast.remote.RemoteEffect.Notice -> if (effect.subtitle) Toast.makeText(appContext, effect.message, Toast.LENGTH_LONG).show()
                            }
                        },
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
                Screen.Dashboard -> {
                    // Back returns to the screen the Dashboard was opened from, like the tile
                    // marked OPEN. With nowhere to return to, the Dashboard is "home": Back
                    // double-taps to exit (soft finish; an active cast keeps running).
                    BackHandler {
                        if (dashboardCanReturn) {
                            onDashboardClose()
                            return@BackHandler
                        }
                        val now = System.currentTimeMillis()
                        if (now - backPressedTime > 2000) {
                            onBackPressedTimeChange(now)
                            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                        } else {
                            onFinishActivity()
                        }
                    }
                    val castSessionState by connectionViewModel.castSessionState.collectAsStateWithLifecycle()
                    val reconnectStatus by connectionViewModel.reconnectStatus.collectAsStateWithLifecycle()
                    val playOnStatus = playOnStatus(
                        route = castRoute,
                        socketConnected = connectionState is WebSocketClient.ConnectionState.Connected,
                        nativeDevice = tvDevice,
                        externalDevice = activeExternalDevice,
                        externalReady = castSessionState.isReadyForPlayback,
                        socketSecure = (connectionState as? WebSocketClient.ConnectionState.Connected)?.secure == true,
                        connecting = connectionState is WebSocketClient.ConnectionState.Connecting ||
                            connectionState is WebSocketClient.ConnectionState.WaitingForApproval ||
                            castSessionState.phase == com.playbridge.sender.cast.SessionPhase.CONNECTING,
                        reconnecting = reconnectStatus != null,
                    )
                    val ublockSetupStatus by Components.ublockOnboarding.status.collectAsStateWithLifecycle()
                    val tileStates = rememberDashboardTileStates(
                        playOnStatus = playOnStatus,
                        castSessionManager = connectionViewModel.castSessionManager,
                        nativeConnected = connectionState is WebSocketClient.ConnectionState.Connected,
                        iptvPlaylists = iptvViewModel.playlists.collectAsStateWithLifecycle().value.size,
                        collections = collectionsViewModel.collections.collectAsStateWithLifecycle().value.size,
                    )
                    DashboardScreen(
                        tileStates = tileStates,
                        ublockSetupStatus = ublockSetupStatus,
                        onCheckUblock = { Components.ublockOnboarding.refresh() },
                        onInstallUblock = { Components.ublockOnboarding.requestInstall() },
                        onCancelUblock = { Components.ublockOnboarding.cancel() },
                        canFinishUblockSetup = { Components.ublockOnboarding.canFinish() },
                        onOnboardingVisible = { Components.extensionPrompts.reShowPendingPrompt() },
                        currentScreen = lastMainScreen,
                        playOnStatus = playOnStatus,
                        onNavigate = { screen ->
                            onScreenChange(screen)
                        },
                        onPlayOn = { showDevicePicker = true },
                        onExit = onFullExit,
                        onRemote = { onScreenChange(Screen.Remote) },
                        onUseThisPhone = { connectionViewModel.selectThisDevice() },
                        onSettings = {
                            // So Settings back lands on Dashboard (not Browser).
                            onLastMainScreenChange(Screen.Dashboard)
                            onScreenChange(Screen.Settings)
                        },
                        bridgedApps = bridgedApps,
                        onOpenBridgedApp = onOpenBridgedApp,
                        onRemoveBridgedApp = onRemoveBridgedApp,
                        onEditBridgedApp = onEditBridgedApp,
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
                        onAddToCollection = { media ->
                            addToCollectionDrafts = media.map { item ->
                                com.playbridge.sender.data.collection.CollectionItemDraft(
                                    title = item.title,
                                    url = item.uri.toString(),
                                    kind = com.playbridge.sender.data.collection.CollectionItemKind.LOCAL,
                                    mimeType = item.mimeType,
                                    sourceTag = com.playbridge.sender.data.collection.CollectionSource.PHONE_FILE,
                                )
                            }
                        },
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
            val showNowPlayingBar = (
                // Phone Files' select mode puts its own action bar where the mini player sits.
                (targetScreen == Screen.PhoneFiles && !phoneFilesUiState.selecting) ||
                targetScreen == Screen.Downloads ||
                targetScreen == Screen.Iptv ||
                targetScreen is Screen.IptvDetail ||
                targetScreen == Screen.Collections ||
                targetScreen is Screen.CollectionDetail)
            if (showNowPlayingBar) {
                NowPlayingBarHost(
                    connectionViewModel = connectionViewModel,
                    connectionCoordinator = connectionCoordinator,
                    connectionState = connectionState,
                    tvDevice = tvDevice,
                    activeExternalDevice = activeExternalDevice,
                    castRoute = castRoute,
                    accentColor = null,
                    showDevicePicker = { showDevicePicker = true },
                    onOpenRemote = {
                        onScreenChange(Screen.Remote)
                    },
                    onOpenUpNext = {
                        openRemoteQueue = true
                        onScreenChange(Screen.Remote)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding()
                        )
                )
            }
        }
    }

        // Keep the browser menu outside AnimatedContent so it never slides/fades with a page.
        // Bridged apps and chrome-hidden websites need access to PlayBridge's controls.
        val browserMenu = currentScreen == Screen.Browser &&
            (isBridgedAppMode || isBrowserChromeHidden || isFullscreen)
        if (browserMenu) {
            key(currentScreen, isFullscreen) {
                PlayBridgeEdgeShortcut(
                    fullscreen = fullscreenBrowser,
                    fullscreenInteraction = fullscreenInteraction,
                    onDashboard = {
                        leaveMediaFullscreen()
                        onScreenChange(Screen.Dashboard)
                    },
                    onRemote = {
                        leaveMediaFullscreen()
                        if (castRoute is CastSessionManager.Route.NativeTv) {
                            connectionViewModel.webSocketClient.send(
                                com.playbridge.shared.protocol.createContextQueryJson()
                            )
                        }
                        onScreenChange(Screen.Remote)
                    },
                    onDevices = { showDevicePicker = true },
                    onBack = {
                        leaveMediaFullscreen()
                        if (browserCanGoBack) session?.goBack() else onScreenChange(Screen.Dashboard)
                    },
                    onAppSettings = onAppSettings,
                    onRefresh = {
                        leaveMediaFullscreen()
                        session?.reload()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
        }
    }

    } // Main content column: the same persistent notice also covers Remote.

    // Shared Add-to-Collection sheet, driven by any screen's onAddToCollection(draft).
    if (addToCollectionDrafts.isNotEmpty()) {
        val drafts = addToCollectionDrafts
        com.playbridge.sender.collection.AddToCollectionSheet(
            viewModel = collectionsViewModel,
            drafts = drafts,
            onDismiss = { addToCollectionDrafts = emptyList() },
            onAdded = { name, addedCount ->
                Toast.makeText(
                    context,
                    when {
                        addedCount == 0 && drafts.size == 1 -> "Already in $name"
                        addedCount == 0 -> "All already in $name"
                        drafts.size == 1 -> "Added to $name"
                        else -> "Added $addedCount to $name"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
}

/**
 * Cast mini-bar host. Collects the high-frequency TV/external playback tickers locally so
 * per-second position updates recompose only this small subtree, not the whole [AppNavHost].
 */
@Composable
private fun NowPlayingBarHost(
    connectionViewModel: ConnectionViewModel,
    connectionCoordinator: ConnectionCoordinator,
    connectionState: WebSocketClient.ConnectionState,
    tvDevice: TvDevice?,
    activeExternalDevice: TvDevice?,
    castRoute: CastSessionManager.Route,
    accentColor: androidx.compose.ui.graphics.Color?,
    showDevicePicker: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenUpNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val linkedCoordinator = koinInject<LinkedPageCastCoordinator>()
    val linkedPage by linkedCoordinator.uiState.collectAsStateWithLifecycle()
    val phoneQueue by koinInject<com.playbridge.sender.cast.PhoneQueueCoordinator>().state.collectAsStateWithLifecycle()
    val tvPlaylist by connectionCoordinator.tvPlaylistState.collectAsStateWithLifecycle()
    val selectedRemoteDevice by connectionViewModel.selectedRemoteDevice.collectAsStateWithLifecycle()
    val remoteTVRows by connectionViewModel.remoteTVDirectory.rows.collectAsStateWithLifecycle()
    val remoteTV = remoteTVRows.firstOrNull { it.device.endpointKey == selectedRemoteDevice?.endpointKey && castRoute is CastSessionManager.Route.NativeTv }
    val externalStatus by connectionViewModel.externalStatus.collectAsStateWithLifecycle()
    val externalMediaTitle by connectionViewModel.externalMediaTitle.collectAsStateWithLifecycle()
    val tvActiveContext by connectionCoordinator.tvActiveContext.collectAsStateWithLifecycle()
    val tvPlayback by connectionCoordinator.tvPlayback.collectAsStateWithLifecycle()
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

    val resolvedDeviceName = activeExternalDevice?.name
        ?: if (nativeSelected) tvDevice?.name else null
    val protocolName = activeExternalDevice?.resolvedProtocol?.displayName
        ?: CastProtocol.PLAYBRIDGE.displayName.takeIf { nativeSelected }
    val leadingIcon = when {
        externalActive -> Icons.Default.Cast
        nativeSelected && wsConnected -> Icons.Default.Tv
        else -> Icons.Default.Smartphone
    }

    // The phone list's current item, when the TV is playing one of this phone's lists.
    val phoneItem = phoneQueue.current?.takeIf { phoneQueue.active && !externalActive && nativePlaying }
    val linked = linkedPage.active && nativePlaying
    val listTint = com.playbridge.sender.cast.remote.ui.rememberQueueTint(phoneQueue.current?.takeIf { phoneQueue.active }?.uri, MaterialTheme.colorScheme.primary)
    val upNextCount = when {
        !playing || externalActive -> 0
        phoneItem != null -> phoneQueue.remaining
        else -> tvPlaylist?.let { list ->
            val at = list.items.indexOfFirst { if (list.currentItemId != null) it.itemId == list.currentItemId else it.index == list.currentIndex }
            if (at >= 0) list.items.size - at - 1 else (list.totalCount - list.currentIndex - 1)
        }?.coerceAtLeast(0) ?: 0
    }

    val primaryText: String
    val secondaryText: String?
    when {
        playing -> {
            primaryText = (if (externalActive) externalMediaTitle else tvPlayback?.title ?: phoneItem?.title) ?: "Now playing"
            val on = "on ${resolvedDeviceName ?: "TV"}"
            secondaryText = if (linked) "Controlled by ${linkedPage.controllerName ?: "a website"} · $on" else on
        }
        remoteTV?.paired == true && !wsConnected -> {
            primaryText = remoteTV.device.name
            secondaryText = remoteTV.status
        }
        externalActive || (nativeSelected && wsConnected) -> {
            primaryText = resolvedDeviceName ?: "TV"
            secondaryText = protocolName?.let { "$it · Ready to cast" } ?: "Ready to cast"
        }
        else -> {
            primaryText = "This phone"
            secondaryText = "Tap to cast to a device"
        }
    }

    NowPlayingBar(
        primaryText = primaryText,
        secondaryText = secondaryText,
        leadingIcon = leadingIcon,
        onClick = {
            if (playing || remoteTV?.paired == true) {
                if (!externalActive) {
                    connectionViewModel.webSocketClient.send(
                        com.playbridge.shared.protocol.createContextQueryJson()
                    )
                }
                onOpenRemote()
            } else {
                // Idle → choose / switch the cast destination.
                showDevicePicker()
            }
        },
        isPlaying = playing,
        isPaused = paused,
        progress = if (playing) playbackProgress else null,
        ready = !playing && (externalActive || nativeSelected && wsConnected),
        thumbnail = phoneItem?.let { item ->
            {
                com.playbridge.sender.cast.MediaThumbnail(
                    item.uri.toUri(), item.kind, android.util.Size(256, 144), Modifier.fillMaxSize(),
                )
            }
        },
        upNextCount = upNextCount,
        onUpNext = onOpenUpNext,
        onPlayPause = {
            if (externalActive) {
                if (paused) connectionViewModel.externalPlay() else connectionViewModel.externalPause()
            } else {
                scope.launch { connectionViewModel.executeRemote(com.playbridge.sender.cast.remote.RemoteAction.TogglePlayPause) }
            }
        },
        onUnlinkClick = if (linked) ({ linkedCoordinator.unlink("unlinked") }) else null,
        accentColor = accentColor,
        listTint = listTint.takeIf { phoneQueue.active && !externalActive && nativeSelected && wsConnected },
        modifier = modifier,
    )
}
