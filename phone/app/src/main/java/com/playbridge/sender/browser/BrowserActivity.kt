package com.playbridge.sender.browser

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.GeckoEngineView
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.fetch.Response
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.ContentState
import java.util.UUID
import mozilla.components.lib.state.Store

import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.connection.NsdHelper
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.ConnectionScreen
import com.playbridge.sender.ui.theme.PlayBridgeTheme
import mozilla.components.lib.state.ext.flow
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.history.HistoryEntity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import com.playbridge.sender.data.history.TabEntity
import com.playbridge.sender.data.history.HistoryDatabase
import androidx.activity.viewModels
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.shared.protocol.createPlayCommandJson

@Composable
fun AnimatedMenuItem(
    index: Int,
    onClick: (() -> Unit)? = null,
    content: @Composable (onClick: () -> Unit) -> Unit
) {
    val alpha = remember { Animatable(0f) }
    val slide = remember { Animatable(50f) } // start 50px down
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val delay = index * 30L // 30ms stagger
        kotlinx.coroutines.delay(delay)
        launch {
            alpha.animateTo(
                1f,
                animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)
            )
        }
        launch {
            slide.animateTo(
                0f,
                animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)
            )
        }
    }

    val wrappedOnClick: () -> Unit = {
        if (onClick != null) {
            scope.launch {
                launch {
                    scale.animateTo(0.95f, tween(100))
                    scale.animateTo(1f, tween(100))
                }
                kotlinx.coroutines.delay(150) // Wait for animation and ripple
                onClick()
            }
        }
    }

    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
            this.translationY = slide.value
            this.scaleX = scale.value
            this.scaleY = scale.value
        }
    ) {
        content(wrappedOnClick)
    }
}

class BrowserActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BrowserActivity"
    }

    private val connectionViewModel: ConnectionViewModel by viewModels()
    private val tabManager = TabManager()
    private lateinit var database: HistoryDatabase

    override fun onResume() {
        super.onResume()
        Log.d("PB_STARTUP", "onResume: existingSessions=${tabManager.sessions.size}")
        lifecycleScope.launch(Dispatchers.Main) {
            val state = Components.store.state   // read CURRENT state when coroutine runs
            Log.d("PB_STARTUP", "onResume coroutine: storeTabs=${state.tabs.size}, selectedTabId=${state.selectedTabId}, sessionForSelectedTab=${tabManager.sessions[state.selectedTabId] != null}")
            tabManager.syncSessions(state.tabs, state.selectedTabId)
            Log.d("PB_STARTUP", "onResume: syncSessions done — sessions=${tabManager.sessions.size}, sessionForSelectedTab=${tabManager.sessions[state.selectedTabId] != null}")
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("PB_STARTUP", "onStop: storeTabs=${Components.store.state.tabs.size}, sessions=${tabManager.sessions.size}")
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
    }

    private fun saveTabs() {
        val state = Components.store.state
        val tabs = state.tabs
        val selectedId = state.selectedTabId
        val allStates = tabManager.captureAllStates()
        
        Log.d(TAG, "saveTabs: starting save for ${tabs.size} tabs, captured ${allStates.size} session states")

        // Use a more robust scope for saving to ensure it completes even if activity is pausing
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val entities = tabs.map { tab ->
                    TabEntity(
                        id = tab.id,
                        url = tab.content.url,
                        title = tab.content.title,
                        parentId = tab.parentId,
                        isSelected = (tab.id == selectedId),
                        sessionState = allStates[tab.id]
                    )
                }
                database.tabDao().updateTabs(entities)
                Log.d(TAG, "saveTabs: successfully updated DB with ${entities.size} tabs")
            } catch (e: Exception) {
                Log.e(TAG, "saveTabs: failed to update DB", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)



        if (!Components.isEngineInitialized()) {
            Components.initialize(applicationContext)
        }
        VideoDetector.init(applicationContext)

        // Request notification permission for media controls on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // Set tabManager reference for resolving Kotlin tab IDs from extension messages
        Components.tabManager = tabManager

        // Install the bundled video detector extension
        Components.installBundledExtension()

        database = DatabaseProvider.getDatabase(applicationContext)

        val libraryViewModel: LibraryViewModel by viewModels()

        // Restore tabs
        // Track whether tab restoration is complete to avoid blank screen
        val storeTabsAtStart = Components.store.state.tabs.size
        val tabsRestoredOrReady = mutableStateOf(Components.store.state.tabs.isNotEmpty())
        Log.d("PB_STARTUP", "onCreate: store has $storeTabsAtStart tabs at start, tabsRestoredOrReady=${tabsRestoredOrReady.value}")

        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("PB_STARTUP", "IO coroutine started — checking if store needs restoration")
            if (Components.store.state.tabs.isEmpty()) {
                Log.d("PB_STARTUP", "Store is empty — querying DB")
                val savedTabs = database.tabDao().getAll()
                Log.d("PB_STARTUP", "DB returned ${savedTabs.size} tabs")
                if (savedTabs.isNotEmpty()) {
                    val sessionTabs = savedTabs.map { entity ->
                        TabSessionState(
                            id = entity.id,
                            content = ContentState(url = entity.url, title = entity.title ?: ""),
                            parentId = entity.parentId
                        )
                    }
                    val selectedId = savedTabs.find { it.isSelected }?.id
                    Log.d("PB_STARTUP", "Restoring ${sessionTabs.size} tabs to store, selectedId=$selectedId")
                    withContext(Dispatchers.Main) {
                        Log.d("PB_STARTUP", "Populating savedStates and calling restoreTabs on Main thread")
                        savedTabs.forEach { entity ->
                            entity.sessionState?.let { bytes ->
                                tabManager.savedStates[entity.id] = bytes
                            }
                        }
                        tabManager.restoreTabs(sessionTabs, selectedId, Components.store)
                        Log.d("PB_STARTUP", "restoreTabs done — store now has ${Components.store.state.tabs.size} tabs, selectedTabId=${Components.store.state.selectedTabId}")
                    }
                } else {
                    Log.d("PB_STARTUP", "DB has no tabs — will create blank tab via ensureAtLeastOneTab")
                }
            } else {
                Log.d("PB_STARTUP", "Store already has ${Components.store.state.tabs.size} tabs — skipping DB restore")
            }
            withContext(Dispatchers.Main) {
                Log.d("PB_STARTUP", "Setting tabsRestoredOrReady=true")
                tabsRestoredOrReady.value = true
            }
        }

        setContent {
            var currentScreen by remember {
                val sp = getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                mutableStateOf<Screen>(
                    when (sp.getString("last_main_screen", "browser")) {
                        "library" -> Screen.Library
                        "debrid" -> Screen.DebridLibrary
                        else -> Screen.Browser
                    }
                )
            }
            // Tracks the last "main" tab so Settings/overlays know where to return
            var lastMainScreen by remember { mutableStateOf(currentScreen) }
            val clipboardManager = LocalClipboardManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            val context = LocalContext.current
            val connectionState by connectionViewModel.connectionState.collectAsState()
            val scope = rememberCoroutineScope()

            // Database and History
            // Uses the activity-scoped database instance
            val historyDao = remember { database.historyDao() }
            val bookmarkDao = remember { database.bookmarkDao() }
            val addonDao = remember { database.addonDao() }
            val addonRepository = remember { com.playbridge.sender.data.library.AddonRepository(addonDao, cacheDir) }
            val subtitleService = remember { com.playbridge.sender.data.library.StremioSubtitleService(addonRepository) }
            val installedAddons by addonDao.getAll().collectAsState(initial = emptyList())

            // Suggestions State
            var isEditing by remember { mutableStateOf(false) }
            var editUrl by remember { mutableStateOf("") }
            val suggestions by historyDao.search(editUrl).collectAsState(initial = emptyList())
            val allHistory by historyDao.getAll().collectAsState(initial = emptyList())
            // URL bar tap panel state (Chrome-like panel shown before user types)
            var urlBarTapped by remember { mutableStateOf(false) }
            var urlPanelClipboard by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(isEditing) {
                if (isEditing) {
                    urlBarTapped = true
                    val clip = clipboardManager.getText()?.text
                    urlPanelClipboard = if (!clip.isNullOrBlank()) clip else null
                } else {
                    urlBarTapped = false
                    urlPanelClipboard = null
                }
            }

            // Connection ViewModel State
            val tvDevice by connectionViewModel.tvDevice.collectAsState(initial = null)
            val discoveredDevices by connectionViewModel.discoveredDevices.collectAsState()
            val history by connectionViewModel.deviceHistory.collectAsState(initial = emptyList())

            // Session and navigation state from BrowserStore
            val store = Components.store
            var browserState by remember {
                Log.d("PB_STARTUP", "Compose: initialising browserState — store has ${store.state.tabs.size} tabs, selectedTabId=${store.state.selectedTabId}")
                mutableStateOf(store.state)
            }

            // Observe store state changes
            LaunchedEffect(store) {
                Log.d("PB_STARTUP", "Compose: store.flow() collector started")
                store.flow().collect { state ->
                    if (state.tabs.size != browserState.tabs.size || state.selectedTabId != browserState.selectedTabId) {
                        Log.d("PB_STARTUP", "Compose: store.flow() emitted — tabs=${state.tabs.size}, selectedTabId=${state.selectedTabId}")
                    }
                    browserState = state
                }
            }

            LaunchedEffect(tabsRestoredOrReady.value) {
                Log.d("PB_STARTUP", "Compose: tabsRestoredOrReady=${tabsRestoredOrReady.value} — force-syncing browserState from store (${store.state.tabs.size} tabs, selectedTabId=${store.state.selectedTabId})")
                browserState = store.state
            }

            val tabIds = browserState.tabs.map { it.id }
            LaunchedEffect(tabIds, browserState.selectedTabId) {
                Log.d("PB_STARTUP", "Compose: syncSessions triggered — tabCount=${browserState.tabs.size}, selectedTabId=${browserState.selectedTabId}")
                tabManager.syncSessions(browserState.tabs, browserState.selectedTabId)
                Log.d("PB_STARTUP", "Compose: syncSessions returned — sessions.keys=${tabManager.sessions.keys.size}")
            }

            val sessions = tabManager.sessions

            val selectedTabId = browserState.selectedTabId
            val selectedTab = browserState.tabs.find { it.id == selectedTabId }
            val session = if (selectedTab != null) sessions[selectedTab.id] else null

            Log.d("PB_STARTUP", "Compose: recompose — browserStateTabs=${browserState.tabs.size}, selectedTabId=$selectedTabId, sessionNull=${session == null}, tabsRestored=${tabsRestoredOrReady.value}, sessionsMapSize=${sessions.size}")

            // State for download dialog
            var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }

            if (session == null) {
                Log.d("PB_STARTUP", "Compose: session==null path — browserStateTabs=${browserState.tabs.size}, selectedTabId=$selectedTabId, tabsRestored=${tabsRestoredOrReady.value}, sessionsInMap=${sessions.keys.joinToString()}")
                if (currentScreen == Screen.Tabs) {
                    PlayBridgeTheme {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            TabsScreen(
                                onTabSelected = { tabId ->
                                    tabManager.selectTab(tabId, store)
                                    currentScreen = Screen.Browser
                                },
                                onTabClosed = { tabId ->
                                    tabManager.closeTab(tabId, store)
                                },
                                onNewTab = {
                                    tabManager.createTab("about:blank", store)
                                    currentScreen = Screen.Browser
                                }
                            )
                        }
                    }
                    return@setContent
                }

                if (tabsRestoredOrReady.value) {
                    // Restoration done but session is still null — force create a tab
                    LaunchedEffect(Unit) {
                        Log.d("PB_STARTUP", "Compose: tabsRestored=true but session still null — calling ensureAtLeastOneTab (storeTabCount=${store.state.tabs.size})")
                        tabManager.ensureAtLeastOneTab(store)
                    }
                }
                // No spinner — just return and let the background show through while sessions init
                return@setContent
            }

            // UI state variables — keyed to selectedTabId so they reset when switching tabs
            var currentUrl by remember(selectedTabId) { mutableStateOf(selectedTab?.content?.url ?: "about:blank") }
            var isLoading by remember(selectedTabId) { mutableStateOf(false) }
            
            // Back/Forward states are now read from tabManager.navigationStates
            val navState = tabManager.navigationStates[selectedTabId] ?: TabNavigationState()
            val browserCanGoBack = navState.canGoBack
            val browserCanGoForward = navState.canGoForward

            var previousUrl by remember(selectedTabId) { mutableStateOf(selectedTab?.content?.url ?: "") }
            var menuExpanded by remember { mutableStateOf(false) }

            // User preferences
            val prefs = remember { getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }

            // Sync tab manager settings from prefs
            DisposableEffect(prefs) {
                tabManager.maxAliveSessions = prefs.getInt("max_alive_tabs", 5)
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == "max_alive_tabs") {
                        tabManager.maxAliveSessions = p.getInt(key, 5)
                        Log.d("TabManager", "Updated maxAliveSessions to ${tabManager.maxAliveSessions} from prefs")
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }
            
            val browserSettings = remember { getSharedPreferences("browser_settings", android.content.Context.MODE_PRIVATE) }

            // Mediaflow proxy config (read once; user must reopen cast sheet to pick up changes)
            val mediaflowProxyUrl      by remember { mutableStateOf(browserSettings.getString(MediaflowProxy.PREFS_KEY_URL, "") ?: "") }
            val mediaflowProxyPassword by remember { mutableStateOf(browserSettings.getString(MediaflowProxy.PREFS_KEY_PASSWORD, "") ?: "") }
            val mediaflowAutoSelect    by remember { mutableStateOf(browserSettings.getBoolean(MediaflowProxy.PREFS_KEY_AUTO_SELECT, true)) }

            // Persist the active main screen so it survives app restarts and Settings navigation
            LaunchedEffect(currentScreen) {
                if (currentScreen == Screen.Browser || currentScreen == Screen.Library || currentScreen == Screen.DebridLibrary) {
                    lastMainScreen = currentScreen
                    prefs.edit().putString("last_main_screen", when (currentScreen) {
                        Screen.Library -> "library"
                        Screen.DebridLibrary -> "debrid"
                        else -> "browser"
                    }).apply()
                }
            }

            val preferredAudioLang by remember { mutableStateOf(prefs.getString("preferred_audio_lang", "") ?: "") }
            val preferredSubLang by remember { mutableStateOf(prefs.getString("preferred_subtitle_lang", "") ?: "") }
            val defaultVideoQuality by remember { mutableStateOf(prefs.getString("default_video_quality", "Auto") ?: "Auto") }
            val maxBitrateCapMbps by remember { mutableStateOf(prefs.getString("auto_stream_max_mbps", "")?.toDoubleOrNull()) }

            // Set up Bridge callback to handle Hub UI cast requests
            LaunchedEffect(connectionViewModel, preferredAudioLang, preferredSubLang, defaultVideoQuality, maxBitrateCapMbps) {
                com.playbridge.sender.browser.Components.onBridgeCastRequest = { url, title ->
                    Log.d("BrowserActivity", "Cast requested via Extension Bridge: $url ($title)")
                    val currentMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
                    
                    val cmd = createPlayCommandJson(
                        url = url,
                        title = title,
                        playerMode = currentMode,
                        preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                        preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                        defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" },
                        maxBitrateCapMbps = maxBitrateCapMbps
                    )
                    
                    connectionViewModel.sendCommandAndRecord(
                        commandJson = cmd,
                        type = "play",
                        url = url,
                        title = title
                    )
                }
            }

            var detectVideosEnabled by remember { mutableStateOf(prefs.getBoolean("detect_videos", true)) }
            var isDesktopMode by remember { mutableStateOf(false) }
            var isSecureConnection by remember { mutableStateOf(false) }
            var siteSecurityInfo by remember { mutableStateOf<SiteSecurityInfo?>(null) }
            var showSiteInfoSheet by remember { mutableStateOf(false) }
            var isFullscreen by remember { mutableStateOf(false) }

            // Fullscreen: hide/show system bars
            val view = LocalView.current
            LaunchedEffect(isFullscreen) {
                val window = this@BrowserActivity.window ?: return@LaunchedEffect
                val controller = WindowInsetsControllerCompat(window, view)
                if (isFullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            // Update simple UI state from selected tab
            LaunchedEffect(selectedTab?.id) {
                if (selectedTab != null) {
                    currentUrl = selectedTab.content.url
                }
            }

            // View state - browser
            // var showScanner by remember { mutableStateOf(false) } (deleted)

            // FAB Drag state
            var fabOffsetX by remember { mutableFloatStateOf(0f) }
            var fabOffsetY by remember { mutableFloatStateOf(0f) }

            // Back press handling
            var backPressedTime by remember { mutableLongStateOf(0L) }

            // Magnet parsing state
            var interceptedMagnet by remember { mutableStateOf<String?>(null) }
            var interceptedTorrentBytes by remember { mutableStateOf<ByteArray?>(null) }
            val debridRepository = remember { com.playbridge.sender.data.debrid.DebridRepository(applicationContext) }

            // Video detection state — per-tab
            var showVideoSheet by remember { mutableStateOf(false) }
            var sheetPlayerMode by remember { mutableStateOf(prefs.getString("tv_player_mode", "tv") ?: "tv") }
            var forcePlaylistSheet by remember { mutableStateOf<DetectedVideo?>(null) }
            var forcedVideos by remember { mutableStateOf<List<DetectedVideo>?>(null) }
            var castSheetInitialMode by remember { mutableStateOf("play") }
            var castSheetBrowseOverride by remember { mutableStateOf<String?>(null) }
            var pendingContentPayload by remember { mutableStateOf<com.playbridge.shared.protocol.PlayPayload?>(null) }
            val detectedVideos by remember(selectedTabId, forcePlaylistSheet, forcedVideos) {
                derivedStateOf {
                    // Read processingVersion so this re-derives whenever any video's
                    // isPlayable / qualities / hlsPlaylist fields are updated by background fetches.
                    @Suppress("UNUSED_EXPRESSION")
                    VideoDetector.processingVersion
                    if (forcedVideos != null) forcedVideos!!
                    else if (forcePlaylistSheet != null) listOf(forcePlaylistSheet!!)
                    else VideoDetector.getVideosForTab(selectedTabId ?: "").toList()
                }
            }
            val videoCount by remember(selectedTabId) {
                derivedStateOf { detectedVideos.count { !it.isSubtitle } }
            }

            // Eagerly parse HLS/DASH qualities and fetch thumbnails for the current tab's videos
            // so results are ready before the user opens the sheet.
            LaunchedEffect(selectedTabId) {
                val tabId = selectedTabId ?: return@LaunchedEffect
                val processedUrls = mutableSetOf<String>()
                // Use snapshotFlow to observe additions to the tab's video list without
                // restarting this LaunchedEffect (which would cancel pending background tasks).
                snapshotFlow { VideoDetector.getVideosForTab(tabId) }.collect { videos ->
                    for (video in videos) {
                        if (video.isSubtitle) continue
                        if (processedUrls.contains(video.url)) continue

                        if (!video.qualitiesChecked || !VideoDetector.hasThumbnail(video.url)) {
                            processedUrls.add(video.url)
                            if (!video.qualitiesChecked) {
                                launch { VideoDetector.fetchHlsQualities(video, tabId) }
                            }
                            if (!VideoDetector.hasThumbnail(video.url)) {
                                launch { VideoDetector.fetchThumbnail(video) }
                            }
                        }
                    }
                }
            }

            // TV active context - updated via WebSocket messages from TV
            var tvActiveContext by remember { mutableStateOf("idle") } // "player", "browser", or "idle"

            // TV playlist state - updated via playlist_status messages from TV
            var tvPlaylistState by remember { mutableStateOf<PlaylistUiState?>(null) }
            // Now Playing context - set when a playlist play starts from LibraryDetailScreen
            var nowPlayingTvId by remember { mutableStateOf<Int?>(null) }
            var nowPlayingSeason by remember { mutableStateOf<Int?>(null) }
            var nowPlayingEpisodeStart by remember { mutableStateOf<Int>(1) } // episode number of playlist index 0

            // Find in Page state
            var showFindBar by remember { mutableStateOf(false) }

            var pendingPopup by remember { mutableStateOf<PendingPopup?>(null) }

            // Clear finding when bar closes
            LaunchedEffect(showFindBar) {
                if (!showFindBar) {
                    tabManager.clearFind(session)
                }
            }

            // Listen for context messages from TV
            LaunchedEffect(Unit) {
                launch {
                    connectionViewModel.webSocketClient.messages.collect { message ->
                        try {
                            val json = org.json.JSONObject(message)
                            when (json.optString("type")) {
                                "context" -> {
                                    tvActiveContext = json.optString("active", "idle")
                                    // Clear playlist state when TV goes idle
                                    if (tvActiveContext == "idle") {
                                        tvPlaylistState = null
                                        nowPlayingTvId = null
                                        nowPlayingSeason = null
                                    }
                                }
                                "playlist_status" -> {
                                    tvPlaylistState = PlaylistUiState(
                                        currentIndex = json.optInt("currentIndex", 0),
                                        totalCount = json.optInt("totalCount", 0)
                                    )
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }

                // Token listening is now handled in ConnectionViewModel
            }

            // Context menu state for "Open in new tab"
            var contextMenuUrl by remember(selectedTabId) { mutableStateOf<String?>(null) }

            // Mutable state wrappers for SessionObserverSetup — keyed to selectedTabId
            val currentUrlState = remember(selectedTabId) { mutableStateOf(currentUrl) }
            val isLoadingState = remember(selectedTabId) { mutableStateOf(false) }
            val contextMenuUrlState = remember(selectedTabId) { mutableStateOf(contextMenuUrl) }
            val previousUrlState = remember(selectedTabId) { mutableStateOf(previousUrl) }
            val pendingDownloadState = remember(selectedTabId) { mutableStateOf(pendingDownload) }
            val isSecureConnectionState = remember(selectedTabId) { mutableStateOf(isSecureConnection) }
            val siteSecurityInfoState = remember(selectedTabId) { mutableStateOf(siteSecurityInfo) }

            val pendingPopupState = remember(selectedTabId) { mutableStateOf(pendingPopup) }

            // Sync wrapper states back to local vars
            currentUrl = currentUrlState.value
            isLoading = isLoadingState.value
            contextMenuUrl = contextMenuUrlState.value
            previousUrl = previousUrlState.value
            pendingDownload = pendingDownloadState.value
            isSecureConnection = isSecureConnectionState.value
            siteSecurityInfo = siteSecurityInfoState.value
            pendingPopup = pendingPopupState.value

            // Sync wrapper states from BrowserStore when the selected tab changes
            // This ensures the URL bar shows the correct URL immediately on tab switch
            LaunchedEffect(selectedTab?.id) {
                if (selectedTab != null) {
                    currentUrlState.value = selectedTab.content.url
                    previousUrlState.value = selectedTab.content.url
                    isSecureConnectionState.value = false
                }
            }

            // Auto-reconnect to TV when opening the cast sheet
            LaunchedEffect(showVideoSheet) {
                if (showVideoSheet && connectionState is WebSocketClient.ConnectionState.Disconnected) {
                    tvDevice?.let { device ->
                        Log.d("BrowserActivity", "Cast sheet opened while disconnected. Retrying connection to ${device.name}")
                        connectionViewModel.connect(device)
                    }
                }
            }

            // Link context menu
            LinkContextMenu(
                url = contextMenuUrl,
                isConnected = connectionState is WebSocketClient.ConnectionState.Connected,
                onPlayOnTv = { linkUrl ->
                    val video = DetectedVideo(
                        url = linkUrl,
                        tabId = -1,
                        detectedBy = "link_menu"
                    )
                    forcedVideos = listOf(video)
                    showVideoSheet = true
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onOpenInNewTab = { linkUrl ->
                    tabManager.createTab(linkUrl, store, parentId = store.state.selectedTabId)
                    Toast.makeText(this@BrowserActivity, "Opened in new tab", Toast.LENGTH_SHORT).show()
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onOpenInBackground = { linkUrl ->
                    tabManager.createTab(linkUrl, store, parentId = store.state.selectedTabId, select = false)
                    Toast.makeText(this@BrowserActivity, "Opened in background", Toast.LENGTH_SHORT).show()
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onCopyLink = { linkUrl ->
                    clipboardManager.setText(AnnotatedString(linkUrl))
                    Toast.makeText(this@BrowserActivity, "Link copied", Toast.LENGTH_SHORT).show()
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onDismiss = {
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                }
            )

            // Register navigation observer & GeckoSession delegates
            SessionObserverSetup(
                session = session,
                selectedTab = selectedTab,
                store = store,
                tabManager = tabManager,
                scope = scope,
                currentUrl = currentUrlState,
                isLoading = isLoadingState,
                contextMenuUrl = contextMenuUrlState,
                previousUrl = previousUrlState,
                historyDao = historyDao,
                pendingDownload = pendingDownloadState,
                isDesktopMode = isDesktopMode,
                detectVideosEnabled = detectVideosEnabled,
                isSecureConnection = isSecureConnectionState,
                siteSecurityInfo = siteSecurityInfoState,
                pendingPopup = pendingPopupState,
                onXpiDetected = { url ->
                    runOnUiThread {
                        Toast.makeText(this@BrowserActivity, "Installing extension...", Toast.LENGTH_SHORT).show()
                    }
                    lifecycleScope.launch(Dispatchers.Main) {
                        try {
                            Components.addonManager.installAddon(
                                url = url,
                                onSuccess = { addon ->
                                    Log.d(TAG, "Addon installed: ${addon.id}")
                                    runOnUiThread {
                                        Toast.makeText(this@BrowserActivity, "Extension installed successfully!", Toast.LENGTH_LONG).show()
                                    }
                                },
                                onError = { throwable ->
                                    Log.e(TAG, "Addon install failed", throwable)
                                    runOnUiThread {
                                        Toast.makeText(this@BrowserActivity, "Install failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error installing addon", e)
                        }
                    }
                },
                onMagnetDetected = { uri ->
                    interceptedMagnet = uri
                },
                onTorrentDownloaded = { bytes ->
                    interceptedTorrentBytes = bytes
                },
                onVideoHashDetected = { url, kotlinTabId ->
                    try {
                        val hashData = url.substringAfter("#playbridge-video=")
                        val decoded = java.net.URLDecoder.decode(hashData, "UTF-8")
                        Log.d(TAG, "PlayBridge video signal for tab $kotlinTabId: $decoded")

                        val json = kotlinx.serialization.json.Json.parseToJsonElement(decoded)
                        if (json is kotlinx.serialization.json.JsonObject) {
                            VideoDetector.onMessageReceived(kotlinx.serialization.json.JsonObject(mapOf(
                                "type" to kotlinx.serialization.json.JsonPrimitive("video_detected"),
                                "url" to (json["url"] ?: kotlinx.serialization.json.JsonPrimitive("")),
                                "contentType" to (json["contentType"] ?: kotlinx.serialization.json.JsonNull),
                                "detectedBy" to (json["detectedBy"] ?: kotlinx.serialization.json.JsonPrimitive("unknown")),
                                "originUrl" to (json["originUrl"] ?: kotlinx.serialization.json.JsonNull),
                                "headers" to (json["headers"] ?: kotlinx.serialization.json.JsonNull),
                                "timestamp" to (json["timestamp"] ?: kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
                            )), kotlinTabId)
                            Log.d(TAG, "Video added to VideoDetector for tab $kotlinTabId from hash signal")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing playbridge-video hash", e)
                    }
                },
                onFullScreenChange = { fullScreen ->
                    isFullscreen = fullScreen
                }
            )

            val handleBookmarkClick = {
                scope.launch(Dispatchers.IO) {
                    val title = selectedTab?.content?.title
                    val url = currentUrl
                    if (url != "about:blank") {
                        bookmarkDao.insert(
                            com.playbridge.sender.data.history.BookmarkEntity(
                                url = url,
                                title = title,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        scope.launch(Dispatchers.Main) {
                            Toast.makeText(this@BrowserActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

            PlayBridgeTheme {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = drawerState.isOpen, // Only swipe to close, not to open
                    drawerContent = {
                        ModalDrawerSheet(modifier = Modifier.width(260.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "PlayBridge",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    scope.launch { drawerState.close() }
                                    currentScreen = Screen.Settings
                                }) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "Settings",
                                        tint = if (currentScreen == Screen.Settings || currentScreen == Screen.AddonSettings)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Language, contentDescription = null) },
                                label = { Text("Browser", style = MaterialTheme.typography.titleMedium) },
                                selected = currentScreen == Screen.Browser || currentScreen == Screen.Tabs || currentScreen == Screen.History || currentScreen == Screen.Downloads || currentScreen == Screen.Bookmarks || currentScreen == Screen.Remote || currentScreen == Screen.Extensions || currentScreen == Screen.Home,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    currentScreen = Screen.Browser
                                },
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                modifier = Modifier.height(48.dp)
                            )

                            NavigationDrawerItem(
                                icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) },
                                label = { Text("Library", style = MaterialTheme.typography.titleMedium) },
                                selected = currentScreen == Screen.Library,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    currentScreen = Screen.Library
                                },
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                modifier = Modifier.height(48.dp)
                            )

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                                label = { Text("Debrid Library", style = MaterialTheme.typography.titleMedium) },
                                selected = currentScreen == Screen.DebridLibrary,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    currentScreen = Screen.DebridLibrary
                                },
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                modifier = Modifier.height(48.dp)
                            )

                            NavigationDrawerItem(
                                icon = {
                                    if (connectionState is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) {
                                        Icon(Icons.Default.Tv, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Green)
                                    } else {
                                        Icon(Icons.Default.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                label = {
                                    if (connectionState is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) {
                                        Text("TV Connection", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.Green)
                                    } else {
                                        Text("TV Connection", style = MaterialTheme.typography.titleMedium)
                                    }
                                },
                                selected = currentScreen == Screen.Connection,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    currentScreen = Screen.Connection
                                },
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                modifier = Modifier.height(48.dp)
                            )

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                label = { Text("Cast History", style = MaterialTheme.typography.titleMedium) },
                                selected = currentScreen == Screen.CastHistory,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    currentScreen = Screen.CastHistory
                                },
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                modifier = Modifier.height(48.dp)
                            )

                            Spacer(Modifier.height(8.dp))
                        }
                    }
                ) {
                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                        if (isFullscreen) {
                            // Hide toolbar in fullscreen
                        } else when (currentScreen) {
                            Screen.Browser -> {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        BrowserToolbar(
                                            currentUrl = currentUrl,
                                            isLoading = isLoading,
                                            canGoBack = browserCanGoBack,
                                            canGoForward = browserCanGoForward,
                                            tabCount = browserState.tabs.size,
                                            isEditing = isEditing,
                                            isSecure = isSecureConnection,
                                            onSecurityIconClick = { showSiteInfoSheet = true },
                                            isDesktopMode = isDesktopMode,
                                            onDesktopModeChange = { isDesktopMode = it },
                                            onBookmarkClick = { handleBookmarkClick() },
                                            onEditingChange = { editing ->
                                                isEditing = editing
                                                if (editing) {
                                                    editUrl = currentUrl
                                                }
                                            },
                                            onUrlChange = { url ->
                                                editUrl = url
                                                urlBarTapped = false
                                            },
                                            onNavigate = { url ->
                                                session.loadUrl(url)
                                                isEditing = false
                                            },
                                            onBack = { session.goBack() },
                                            onForward = { session.goForward() },
                                            onRefresh = { session.reload() },
                                            onStop = { session.stopLoading() },
                                            onMenuClick = { menuExpanded = true },
                                            onDrawerClick = { scope.launch { drawerState.open() } },
                                            onTabsClick = { currentScreen = Screen.Tabs },
                                            onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                {
                                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                    currentScreen = Screen.Remote
                                                }
                                            } else null,
                                            menuContent = {

                                            // Dropdown menu
                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false },
                                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                tonalElevation = 8.dp,
                                                shape = MaterialTheme.shapes.large
                                            ) {
                                                // Navigation buttons row
                                                AnimatedMenuItem(index = 0) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 8.dp),
                                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        IconButton(
                                                            onClick = {
                                                                session.goBack()
                                                                menuExpanded = false
                                                            },
                                                            enabled = browserCanGoBack
                                                        ) {
                                                            Icon(
                                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                                "Back",
                                                                tint = if (browserCanGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                session.goForward()
                                                                menuExpanded = false
                                                            },
                                                            enabled = browserCanGoForward
                                                        ) {
                                                            Icon(
                                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                                "Forward",
                                                                tint = if (browserCanGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                if (isLoading) session.stopLoading() else session.reload()
                                                                menuExpanded = false
                                                            }
                                                        ) {
                                                            Icon(
                                                                if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                                                if (isLoading) "Stop" else "Refresh",
                                                                tint = MaterialTheme.colorScheme.onSurface
                                                            )
                                                        }
                                                    }
                                                }

                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                                AnimatedMenuItem(
                                                    index = 3,
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.History
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text("History", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurface) },
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }

                                                AnimatedMenuItem(
                                                    index = 4,
                                                    onClick = {
                                                        menuExpanded = false
                                                        showFindBar = true
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text("Find in Page", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }

                                                AnimatedMenuItem(
                                                    index = 4,
                                                    onClick = {
                                                        detectVideosEnabled = !detectVideosEnabled
                                                        prefs.edit().putBoolean("detect_videos", detectVideosEnabled).apply()
                                                        menuExpanded = false
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text("Detect Videos", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = {
                                                            Icon(
                                                                if (detectVideosEnabled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                                null,
                                                                tint = if (detectVideosEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        },
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }

                                                AnimatedMenuItem(
                                                    index = 4,
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.Downloads
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text("Downloads", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) },
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }

                                                AnimatedMenuItem(
                                                    index = 5,
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.Extensions
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text("Extensions", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }

                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                                // Bookmarks
                                                AnimatedMenuItem(
                                                    index = 8,
                                                    onClick = {
                                                        menuExpanded = false
                                                        handleBookmarkClick()
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text("Add Bookmark", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.StarBorder, null, tint = MaterialTheme.colorScheme.onSurface) },
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }

                                                AnimatedMenuItem(
                                                    index = 9,
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.Bookmarks
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text("Bookmarks", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.Bookmarks, null, tint = MaterialTheme.colorScheme.onSurface) }, // Changed icon to Bookmarks
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }

                                                // Desktop Site Toggle
                                                AnimatedMenuItem(
                                                    index = 10,
                                                    onClick = {
                                                        isDesktopMode = !isDesktopMode
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text("Desktop Site", style = MaterialTheme.typography.bodyLarge)
                                                                Switch(
                                                                    checked = isDesktopMode,
                                                                    onCheckedChange = {
                                                                        isDesktopMode = it
                                                                    },
                                                                    modifier = Modifier.scale(0.8f)
                                                                )
                                                            }
                                                        },
                                                        leadingIcon = { Icon(Icons.Default.DesktopMac, null, tint = MaterialTheme.colorScheme.onSurface) },
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }
                                            }
                                            }
                                        )

                                    // Find on Page Bar
                                     if (showFindBar) {
                                        FindOnPageBar(
                                            onFind = { text -> tabManager.findInPage(session, text) },
                                            onNext = { tabManager.findInPage(session, "", 0) },
                                            onPrev = { tabManager.findInPage(session, "", 1) },
                                            onClose = { showFindBar = false }
                                        )
                                    }
                                }
                            }
                        }
                            Screen.Tabs -> {
                                @OptIn(ExperimentalMaterial3Api::class)
                                TopAppBar(
                                    title = { Text("Tabs") },
                                    navigationIcon = {
                                        IconButton(onClick = { currentScreen = Screen.Browser }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                        }
                                    }
                                )
                            }
                            Screen.Extensions -> {
                                @OptIn(ExperimentalMaterial3Api::class)
                                TopAppBar(
                                    title = { Text("Extensions") },
                                    navigationIcon = {
                                        IconButton(onClick = { currentScreen = Screen.Browser }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                        }
                                    }
                                )
                            }
                            Screen.Connection -> {}
                            Screen.History -> {}
                            Screen.CastHistory -> {}
                            Screen.Bookmarks -> {}
                            Screen.Home -> {}
                            Screen.Remote -> {}
                            Screen.Downloads -> {}
                            Screen.Settings -> {}
                            Screen.Library -> {}
                            is Screen.LibraryDetail -> {}
                            Screen.AddonSettings -> {}
                            Screen.DebridLibrary -> {}
                        }
                    }
                ) { innerPadding ->
                    // content
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
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .padding(resolvedPadding)
                        ) {
                                    when (targetScreen) {
                                        Screen.Browser -> {
                                            if (isFullscreen) {
                                                BackHandler {
                                                    isFullscreen = false
                                                    val gs = tabManager.getGeckoSession(session)
                                                    gs?.exitFullScreen()
                                                }
                                            }
                                            BackHandler(enabled = !isFullscreen && !isEditing) {
                                                if (browserCanGoBack) {
                                                    session.goBack()
                                                } else {
                                                    val currentTime = System.currentTimeMillis()
                                                    if (currentTime - backPressedTime > 2000) {
                                                        backPressedTime = currentTime
                                                        Toast.makeText(
                                                            this@BrowserActivity,
                                                            "Press back again to exit",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    } else {
                                                        finish()
                                                    }
                                                }
                                            }

                                            BackHandler(enabled = isEditing) {
                                                isEditing = false
                                                urlBarTapped = false
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                            }

                                            Box(modifier = Modifier.fillMaxSize()) {
                                                BrowserView(
                                                    session = session,
                                                    onLongPressLink = { url: String -> contextMenuUrl = url }
                                                )

                                                if (currentUrl == "about:blank") {
                                                    HomeScreen(
                                                        onNavigate = { url ->
                                                            session.loadUrl(url)
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
                                                            val domain = try { Uri.parse(currentUrl).host ?: currentUrl } catch (e: Exception) { currentUrl }
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
                                                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
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
                                                                                        isEditing = false
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
                                                                                    urlPanelClipboard!!,
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
                                                                                    val clip = urlPanelClipboard!!
                                                                                    val url = if (clip.startsWith("http://") || clip.startsWith("https://") || clip.startsWith("about:")) clip else "https://$clip"
                                                                                    session.loadUrl(url)
                                                                                    isEditing = false
                                                                                    keyboardController?.hide()
                                                                                    focusManager.clearFocus()
                                                                                }) {
                                                                                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open link")
                                                                                }
                                                                            },
                                                                            modifier = Modifier.clickable {
                                                                                val clip2 = urlPanelClipboard!!
                                                                                val url = if (clip2.startsWith("http://") || clip2.startsWith("https://") || clip2.startsWith("about:")) clip2 else "https://$clip2"
                                                                                session.loadUrl(url)
                                                                                isEditing = false
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
                                                                            session.loadUrl(historyItem.url)
                                                                            isEditing = false
                                                                        }
                                                                    )
                                                                    Spacer(modifier = Modifier.height(12.dp))
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                val tabUrl = selectedTab?.content?.url.orEmpty()
                                                if (!isEditing && tabUrl.isNotEmpty() && tabUrl != "about:blank") {
                                                    FloatingActionButton(
                                                        onClick = { showVideoSheet = true },
                                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        shape = CircleShape,
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(24.dp)
                                                            .offset(x = fabOffsetX.dp, y = fabOffsetY.dp)
                                                            .pointerInput(Unit) {
                                                                detectDragGestures { change, dragAmount ->
                                                                    change.consume()
                                                                    fabOffsetX += dragAmount.x / density
                                                                    fabOffsetY += dragAmount.y / density
                                                                }
                                                            }
                                                    ) {
                                                        BadgedBox(
                                                            badge = {
                                                                if (videoCount > 0) {
                                                                    Badge(
                                                                        containerColor = MaterialTheme.colorScheme.error,
                                                                        contentColor = MaterialTheme.colorScheme.onError
                                                                    ) {
                                                                        Text(videoCount.toString())
                                                                    }
                                                                }
                                                            }
                                                        ) {
                                                            Icon(Icons.Default.PlayArrow, "Open TV sheet")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Screen.History -> {
                                            HistoryScreen(
                                                historyItems = allHistory,
                                                onItemClick = { url ->
                                                    session.loadUrl(url)
                                                    currentScreen = Screen.Browser
                                                },
                                                onClearHistory = {
                                                    scope.launch(Dispatchers.IO) {
                                                        historyDao.clear()
                                                    }
                                                },
                                                onBack = { currentScreen = Screen.Browser }
                                            )
                                        }
                                        Screen.CastHistory -> {
                                            val db = com.playbridge.sender.data.history.DatabaseProvider.getDatabase(androidx.compose.ui.platform.LocalContext.current)
                                            val commandHistoryFlow = remember { db.commandHistoryDao().getAll() }
                                            val commandHistory by commandHistoryFlow.collectAsState(initial = emptyList())
                                            CastHistoryScreen(
                                                historyItems = commandHistory,
                                                onItemClick = { item ->
                                                    forcedVideos = listOf(
                                                        DetectedVideo(
                                                            url = item.url,
                                                            title = item.title,
                                                            detectedBy = "history",
                                                            timestamp = item.timestamp
                                                        )
                                                    )
                                                    showVideoSheet = true
                                                },
                                                onDelete = { item ->
                                                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { db.commandHistoryDao().delete(item) }
                                                },
                                                onClearHistory = {
                                                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { db.commandHistoryDao().clear() }
                                                },
                                                onBack = { currentScreen = Screen.Browser }
                                            )
                                        }
                                        Screen.Tabs -> {
                                            BackHandler { currentScreen = Screen.Browser }
                                            TabsScreen(
                                                onTabSelected = { tabId ->
                                                    tabManager.selectTab(tabId, store)
                                                    currentScreen = Screen.Browser
                                                },
                                                onTabClosed = { tabId ->
                                                    tabManager.closeTab(tabId, store)
                                                },
                                                onNewTab = {
                                                    tabManager.createTab("about:blank", store)
                                                    currentScreen = Screen.Browser
                                                }
                                            )
                                        }
                                        Screen.Extensions -> {
                                            BackHandler { currentScreen = Screen.Browser }
                                            ExtensionsScreen(
                                                session = session,
                                                onBack = { currentScreen = Screen.Browser },
                                                onAddExtension = {
                                                    tabManager.createTab("https://addons.mozilla.org/android/", store)
                                                    currentScreen = Screen.Browser
                                                }
                                            )
                                        }
                                        Screen.Connection -> {
                                            BackHandler { currentScreen = Screen.Browser }
                                            ConnectionScreen(
                                                viewModel = connectionViewModel,
                                                onMenuClick = { scope.launch { drawerState.open() } },
                                                onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                    {
                                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                        currentScreen = Screen.Remote
                                                    }
                                                } else null
                                            )
                                        }
                                        Screen.Downloads -> {
                                            BackHandler { currentScreen = Screen.Browser }
                                            DownloadsScreen(
                                                onBack = { currentScreen = Screen.Browser }
                                            )
                                        }
                                        Screen.Settings -> {
                                            BackHandler { currentScreen = lastMainScreen }
                                            SettingsScreen(
                                                onBack = { currentScreen = lastMainScreen },
                                                onAddonSettings = { currentScreen = Screen.AddonSettings },
                                                tvIp = if (connectionState is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) tvDevice?.ip else null,
                                                tvPort = if (connectionState is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) tvDevice?.port else null
                                            )
                                        }
                                        Screen.Bookmarks -> {
                                            BackHandler { currentScreen = Screen.Browser }
                                            BookmarksScreen(
                                                bookmarkDao = bookmarkDao,
                                                onNavigate = { url ->
                                                    session.loadUrl(url)
                                                    currentScreen = Screen.Browser
                                                },
                                                onBack = { currentScreen = Screen.Browser }
                                            )
                                        }
                                        Screen.Remote -> {
                                            val btConnectionState by connectionViewModel.bluetoothClient.connectionState.collectAsState()
                                            var pairedDevices by remember { mutableStateOf<List<android.bluetooth.BluetoothDevice>>(emptyList()) }
                                            var savedMac by remember { mutableStateOf<String?>(null) }

                                            fun initBluetooth() {
                                                val devices = connectionViewModel.bluetoothClient.getBondedDevices()
                                                pairedDevices = devices
                                                val mac = connectionViewModel.getSavedBluetoothMacForTv(tvDevice?.uuid)
                                                savedMac = mac
                                                if (mac != null) {
                                                    connectionViewModel.bluetoothClient.connect(mac)
                                                } else if (devices.size == 1) {
                                                    val onlyMac = devices[0].address
                                                    savedMac = onlyMac
                                                    connectionViewModel.saveBluetoothMacForTv(tvDevice?.uuid, onlyMac)
                                                    connectionViewModel.bluetoothClient.connect(onlyMac)
                                                }
                                            }

                                            val btPermissionLauncher = rememberLauncherForActivityResult(
                                                ActivityResultContracts.RequestMultiplePermissions()
                                            ) { permissions ->
                                                if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true && permissions[Manifest.permission.BLUETOOTH_SCAN] == true) {
                                                    initBluetooth()
                                                }
                                            }

                                            LaunchedEffect(Unit) {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                                    btPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
                                                } else {
                                                    initBluetooth()
                                                }
                                            }

                                            BackHandler {
                                                connectionViewModel.bluetoothClient.disconnect()
                                                currentScreen = Screen.Browser
                                            }
                                            RemoteControlScreen(
                                                isMediaPlaying = tvActiveContext == "player",
                                                btConnectionState = btConnectionState,
                                                pairedDevices = pairedDevices,
                                                savedBluetoothMac = savedMac,
                                                onBluetoothDeviceSelected = { macAddress ->
                                                    savedMac = macAddress
                                                    connectionViewModel.saveBluetoothMacForTv(tvDevice?.uuid, macAddress)
                                                    connectionViewModel.bluetoothClient.connect(macAddress)
                                                },
                                                onBack = {
                                                    connectionViewModel.bluetoothClient.disconnect()
                                                    currentScreen = Screen.Browser
                                                },
                                                onRemoteKey = { key ->
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendRemoteCommand(key)
                                                    } else {
                                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createRemoteCommandJson(key))
                                                    }
                                                },
                                                onMouseMove = { dx, dy ->
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendMouseCommand("move", dx, dy)
                                                    } else {
                                                        connectionViewModel.webSocketClient.sendMouseCommand("move", dx, dy)
                                                    }
                                                },
                                                onMouseClick = {
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendMouseCommand("click", 0f, 0f)
                                                    } else {
                                                        connectionViewModel.webSocketClient.sendMouseCommand("click", 0f, 0f)
                                                    }
                                                },
                                                onMouseScroll = { dx, dy ->
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendMouseCommand("scroll", dx, dy)
                                                    } else {
                                                        connectionViewModel.webSocketClient.sendMouseCommand("scroll", dx, dy)
                                                    }
                                                },
                                                onMouseDown = {
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendMouseCommand("down", 0f, 0f)
                                                    } else {
                                                        connectionViewModel.webSocketClient.sendMouseCommand("down", 0f, 0f)
                                                    }
                                                },
                                                onMouseUp = {
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendMouseCommand("up", 0f, 0f)
                                                    } else {
                                                        connectionViewModel.webSocketClient.sendMouseCommand("up", 0f, 0f)
                                                    }
                                                },
                                                onBrowserControl = { action ->
                                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createBrowserControlCommandJson(action))
                                                },
                                                onPlayerControl = { command ->
                                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createControlCommandJson(command))
                                                    if (command == "stop") { tvActiveContext = "idle" }
                                                }
                                            )
                                        }
                                        Screen.Home -> {
                                            BackHandler {
                                                if (browserCanGoBack) { session.goBack() } else { finish() }
                                            }
                                            HomeScreen(
                                                onNavigate = { url ->
                                                    session.loadUrl(url)
                                                    currentScreen = Screen.Browser
                                                },
                                                historyDao = historyDao,
                                                bookmarkDao = bookmarkDao
                                            )
                                        }
                                        Screen.Library -> {
                                            BackHandler { finish() }
                                            val nowPlayingEp = tvPlaylistState?.let { nowPlayingEpisodeStart + it.currentIndex }
                                            LibraryScreen(
                                                viewModel = libraryViewModel,
                                                onMenuClick = { scope.launch { drawerState.open() } },
                                                nowPlayingTvId = nowPlayingTvId,
                                                nowPlayingSeason = nowPlayingSeason,
                                                nowPlayingEpisode = nowPlayingEp,
                                                onNowPlayingClick = {
                                                    nowPlayingTvId?.let { id -> currentScreen = Screen.LibraryDetail(id.toString(), "tv") }
                                                },
                                                onMovieClick = { movieId -> currentScreen = Screen.LibraryDetail(movieId.toString(), "movie") },
                                                onTvShowClick = { tvId -> currentScreen = Screen.LibraryDetail(tvId.toString(), "tv") },
                                                onAddonItemClick = { id, type -> currentScreen = Screen.LibraryDetail(id, type) }
                                            )
                                        }
                                        is Screen.LibraryDetail -> {
                                            val screen = targetScreen as Screen.LibraryDetail
                                            BackHandler { currentScreen = Screen.Library }
                                            val screenNumericId = screen.id.toIntOrNull()
                                            LibraryDetailScreen(
                                                id = screen.id,
                                                type = screen.type,
                                                addonRepository = addonRepository,
                                                viewModel = libraryViewModel,
                                                tvName = tvDevice?.name,
                                                isTvConnected = connectionState is WebSocketClient.ConnectionState.Connected,
                                                availableTvDevices = remember(discoveredDevices, history) {
                                                    (history + discoveredDevices).distinctBy { it.uuid.ifEmpty { "${it.ip}:${it.port}" } }
                                                },
                                                selectedTvDevice = tvDevice,
                                                onTvDeviceSelect = { device -> connectionViewModel.connect(device) },
                                                onPlayTrailer = { trailerUrl ->
                                                    castSheetInitialMode = "browse"
                                                    castSheetBrowseOverride = trailerUrl
                                                    showVideoSheet = true
                                                },
                                                onPlayPayloadToTv = { payload ->
                                                    // Always send direct to TV
                                                    val cmd = com.playbridge.shared.protocol.createPlayCommandJson(
                                                        url = payload.url,
                                                        title = payload.title,
                                                        contentType = payload.contentType,
                                                        detectedBy = payload.detectedBy,
                                                        playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
                                                        preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                                        preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                                        defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" },
                                                        maxBitrateCapMbps = maxBitrateCapMbps,
                                                        visualMetadata = payload.visualMetadata
                                                    )
                                                    connectionViewModel.webSocketClient.send(cmd)
                                                    if (payload.contentType == "series") {
                                                        nowPlayingTvId = payload.visualMetadata?.tmdbId?.toIntOrNull()
                                                        nowPlayingSeason = payload.visualMetadata?.season
                                                        nowPlayingEpisodeStart = payload.visualMetadata?.episode ?: 1
                                                    }
                                                    Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
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
                                                        forcedVideos = listOf(mainVideo)
                                                        showVideoSheet = true
                                                    }
                                                },
                                                // Proxy path: the phone has already resolved a stream
                                                // and rewritten the URL through mediaflow-proxy. Send it
                                                // straight to the TV as a `play` command (bypassing
                                                // content-payload resolution on the TV).
                                                onSendStreamToTv = { url, title, headers, contentType ->
                                                    val cmd = com.playbridge.shared.protocol.createPlayCommandJson(
                                                        url = url,
                                                        title = title,
                                                        headers = headers,
                                                        contentType = contentType,
                                                        detectedBy = "library",
                                                        playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
                                                        preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                                        preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                                        defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" },
                                                        maxBitrateCapMbps = maxBitrateCapMbps
                                                    )
                                                    connectionViewModel.webSocketClient.send(cmd)
                                                },
                                                onPlayPlaylistToTv = { playlist ->
                                                    val playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
                                                    val itemsWithPrefs = playlist.items.map {
                                                        it.copy(
                                                            playerMode = playerMode,
                                                            preferredAudioLanguage = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                                            preferredSubtitleLanguage = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                                            defaultVideoQuality = defaultVideoQuality.takeIf { q -> q != "Auto" },
                                                            maxBitrateCapMbps = maxBitrateCapMbps
                                                        )
                                                    }
                                                    val finalPlaylist = playlist.copy(items = itemsWithPrefs)
                                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createPlaylistCommandJson(finalPlaylist))
                                                    
                                                    nowPlayingTvId = screenNumericId
                                                    nowPlayingSeason = playlist.items.getOrNull(playlist.startIndex)?.visualMetadata?.season ?: 1
                                                    nowPlayingEpisodeStart = playlist.items.getOrNull(playlist.startIndex)?.visualMetadata?.episode ?: 1
                                                },
                                                onQueueAdd = { item ->
                                                    val playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
                                                    val itemWithPrefs = item.copy(
                                                        playerMode = playerMode,
                                                        preferredAudioLanguage = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                                        preferredSubtitleLanguage = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                                        defaultVideoQuality = defaultVideoQuality.takeIf { q -> q != "Auto" },
                                                        maxBitrateCapMbps = maxBitrateCapMbps
                                                    )
                                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createQueueAddCommandJson(itemWithPrefs))
                                                },
                                                onNowPlayingStarted = { tmdbId, season, startEp ->
                                                    nowPlayingTvId = tmdbId
                                                    nowPlayingSeason = season
                                                    nowPlayingEpisodeStart = startEp
                                                },
                                                onBack = { currentScreen = Screen.Library },
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
                                                    startActivity(Intent.createChooser(intent, "Share $title"))
                                                }
                                            )
                                        }
                                        Screen.AddonSettings -> {
                                            BackHandler { currentScreen = Screen.Settings }
                                            AddonSettingsScreen(
                                                addonRepository = addonRepository,
                                                installedAddons = installedAddons,
                                                onBack = { currentScreen = Screen.Settings },
                                                onOpenUrl = { url ->
                                                    tabManager.createTab(url, store)
                                                    currentScreen = Screen.Browser
                                                }
                                            )
                                        }
                                        Screen.DebridLibrary -> {
                                            BackHandler { finish() }
                                            DebridLibraryScreen(
                                                onMenuClick = { scope.launch { drawerState.open() } },
                                                onCopyUrl = { linkUrl ->
                                                    clipboardManager.setText(AnnotatedString(linkUrl))
                                                    Toast.makeText(this@BrowserActivity, "Link copied", Toast.LENGTH_SHORT).show()
                                                },
                                                onShowCastSheet = { video ->
                                                    forcedVideos = listOf(video)
                                                    showVideoSheet = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                if (showVideoSheet) {
                    CastSheet(
                        videos = detectedVideos,
                        contentPayload = pendingContentPayload,
                        onContentClick = { payload ->
                             val cmd = com.playbridge.shared.protocol.createPlayCommandJson(
                                 url = payload.url,
                                 title = payload.title,
                                 contentType = payload.contentType,
                                 detectedBy = payload.detectedBy,
                                 playerMode = sheetPlayerMode.takeIf { it != "tv" },
                                 preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                 preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                 defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" },
                                 maxBitrateCapMbps = maxBitrateCapMbps,
                                 visualMetadata = payload.visualMetadata
                             )
                            connectionViewModel.webSocketClient.send(cmd)
                            if (payload.contentType == "series") {
                                nowPlayingTvId = payload.visualMetadata?.tmdbId?.toIntOrNull()
                                nowPlayingSeason = payload.visualMetadata?.season
                                nowPlayingEpisodeStart = payload.visualMetadata?.episode ?: 1
                            }
                            Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                            showVideoSheet = false
                            pendingContentPayload = null
                        },
                        playerMode = sheetPlayerMode,
                        onPlayerModeChange = { mode ->
                            sheetPlayerMode = mode
                            prefs.edit().putString("tv_player_mode", mode).apply()
                        },
                        availableTvDevices = remember(discoveredDevices, history) {
                            (history + discoveredDevices).distinctBy { it.uuid.ifEmpty { "${it.ip}:${it.port}" } }
                        },
                        selectedTvDevice = tvDevice,
                        onTvChange = { device -> connectionViewModel.connect(device) },
                        tvConnectionState = when (connectionState) {
                            is WebSocketClient.ConnectionState.Connected -> true
                            is WebSocketClient.ConnectionState.Error -> false
                            else -> null
                        },
                        onVideoClick = { video, subtitles ->
                            when (val state = connectionState) {
                                is WebSocketClient.ConnectionState.Connected -> {
                                    if (video.playlistPayload != null) {
                                        val cmd = com.playbridge.shared.protocol.createPlaylistCommandJson(
                                            payload = com.playbridge.shared.protocol.PlaylistPayload(items = video.playlistPayload!!)
                                        )
                                        if (connectionViewModel.webSocketClient.send(cmd)) {
                                            tvActiveContext = "player"
                                            Toast.makeText(this@BrowserActivity, "Playlist sent to ${state.serverName}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val headers = VideoDetector.mediaHeaders(video)
                                        if (!video.originUrl.isNullOrEmpty() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                                            headers["Referer"] = video.originUrl
                                        }
                                        val effectiveQuality = defaultVideoQuality.takeIf { it != "Auto" }
                                        val commandJson = com.playbridge.shared.protocol.createPlayCommandJson(
                                            url = video.url,
                                            title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                            headers = headers,
                                            contentType = video.contentType,
                                            subtitles = subtitles,
                                            detectedBy = video.detectedBy,
                                            playerMode = sheetPlayerMode.takeIf { it != "tv" },
                                            preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                            preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                            defaultVideoQuality = effectiveQuality,
                                            maxBitrateCapMbps = maxBitrateCapMbps
                                        )
                                        connectionViewModel.sendCommandAndRecord(commandJson, "play", video.url, selectedTab?.content?.title ?: "Video from browser")
                                        if (connectionViewModel.webSocketClient.send(commandJson)) {
                                            tvActiveContext = "player"
                                            session?.let { tabManager.pauseMedia(it) }
                                            Toast.makeText(this@BrowserActivity, "Playing on ${state.serverName}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                else -> { Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show() }
                            }
                            showVideoSheet = false
                            forcePlaylistSheet = null
                        },
                        onClear = { VideoDetector.clearTab(selectedTabId ?: "") },
                        onDownload = { video ->
                            DownloadUtils.enqueueDownload(this@BrowserActivity, video.url, null, video.contentType, video.headers?.get("User-Agent"), video.headers?.get("Cookie"), video.headers?.get("Referer") ?: video.originUrl, pageTitle = selectedTab?.content?.title)
                        },
                        browseUrl = castSheetBrowseOverride ?: currentUrl,
                        initialMode = castSheetInitialMode,
                        mediaflowProxyUrl = mediaflowProxyUrl,
                        mediaflowProxyPassword = mediaflowProxyPassword,
                        mediaflowAutoSelect = mediaflowAutoSelect,
                        onBrowseClick = { selectedMode, desktopMode ->
                            val effectiveUrl = castSheetBrowseOverride ?: currentUrl
                            val cmd = com.playbridge.shared.protocol.createBrowserCommandJson(effectiveUrl, browserMode = selectedMode.takeIf { it != "tv" }, desktopMode = desktopMode.takeIf { it })
                            connectionViewModel.sendCommandAndRecord(cmd, "browser", effectiveUrl, "Browser Page")
                            Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                            showVideoSheet = false
                            forcePlaylistSheet = null
                            castSheetInitialMode = "play"
                            castSheetBrowseOverride = null
                        },
                        onOpenNewTab = { url ->
                            tabManager.createTab(url, Components.store)
                            showVideoSheet = false
                            castSheetInitialMode = "play"
                            castSheetBrowseOverride = null
                            currentScreen = Screen.Browser
                        },
                        subtitleService = subtitleService,
                        onDismiss = {
                            showVideoSheet = false
                            forcePlaylistSheet = null
                            forcedVideos = null
                            castSheetInitialMode = "play"
                            castSheetBrowseOverride = null
                            pendingContentPayload = null
                        }
                    )
                }

                if (showSiteInfoSheet) {
                    val sheetInfo = siteSecurityInfo ?: SiteSecurityInfo(isSecure = isSecureConnection, host = try { java.net.URI(currentUrl).host ?: currentUrl } catch (e: Exception) { currentUrl })
                    SiteInfoSheet(info = sheetInfo, onDismiss = { showSiteInfoSheet = false })
                }

                pendingPopup?.let { popup ->
                    val popupPrefs = remember { getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
                    fun openPopupTab() {
                        scope.launch(Dispatchers.Main) {
                            val tabId = tabManager.createTab(url = popup.popupUrl, store = store, parentId = selectedTab?.id, select = true)
                            tabManager.sessions[tabId] = popup.engineSession
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        PopupBlockedBar(host = popup.openerHost, onAllowOnce = { openPopupTab(); pendingPopup = null; pendingPopupState.value = null }, onAlwaysAllow = { val whitelist = popupPrefs.getStringSet("popup_whitelist", emptySet())!!.toMutableSet(); whitelist.add(popup.openerHost); popupPrefs.edit().putStringSet("popup_whitelist", whitelist).apply(); openPopupTab(); pendingPopup = null; pendingPopupState.value = null }, onDismiss = { popup.rawGeckoSession.close(); pendingPopup = null; pendingPopupState.value = null })
                    }
                }

                DownloadConfirmDialog(
                    pendingDownload = pendingDownload,
                    onConfirm = { download: PendingDownload ->
                        DownloadUtils.enqueueDownload(this@BrowserActivity, download.url, download.fileName, download.contentType, download.userAgent, download.cookie, download.referer, pageTitle = selectedTab?.content?.title)
                        Toast.makeText(this@BrowserActivity, "Download started", Toast.LENGTH_SHORT).show()
                        pendingDownload = null
                        pendingDownloadState.value = null
                    },
                    onPlayOnTv = { download: PendingDownload ->
                        val headers = mutableMapOf<String, String>()
                        if (download.userAgent != null) headers["User-Agent"] = download.userAgent
                        if (download.referer != null) headers["Referer"] = download.referer
                        if (download.cookie != null) headers["Cookie"] = download.cookie

                        val video = DetectedVideo(
                            url = download.url,
                            tabId = -1,
                            contentType = download.contentType,
                            detectedBy = "download",
                            headers = headers
                        )

                        forcedVideos = listOf(video)
                        showVideoSheet = true
                        pendingDownload = null
                        pendingDownloadState.value = null
                    },
                    onDismiss = { pendingDownload = null; pendingDownloadState.value = null }
                )

                if (interceptedMagnet != null || interceptedTorrentBytes != null) {
                    val provider = debridRepository.getActiveProvider()
                    if (provider != null) {
                        MagnetParsingSheet(magnetUri = interceptedMagnet, torrentBytes = interceptedTorrentBytes, provider = provider, onDismiss = { interceptedMagnet = null; interceptedTorrentBytes = null }, onPlayLinks = { links ->
                            val videos = links.map { link -> com.playbridge.shared.protocol.PlayPayload(url = link.downloadUrl, title = link.filename, playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }, preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() }, preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() }, defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" }, maxBitrateCapMbps = maxBitrateCapMbps) }
                            val detectedVideo = DetectedVideo(url = if (links.size == 1) links.first().downloadUrl else "playlist://magnet", tabId = -1, timestamp = System.currentTimeMillis(), isPlayable = true, detectedBy = "magnet_playlist", playlistPayload = if (links.size > 1) videos else null)
                            scope.launch { forcePlaylistSheet = detectedVideo; showVideoSheet = true }
                            interceptedMagnet = null
                            interceptedTorrentBytes = null
                        })
                    } else {
                        Toast.makeText(this@BrowserActivity, "No Debrid provider configured. Configure it in Settings.", Toast.LENGTH_LONG).show()
                        interceptedMagnet = null
                        interceptedTorrentBytes = null
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d("PB_STARTUP", "onDestroy: isFinishing=$isFinishing, sessions=${tabManager.sessions.size}")
        tabManager.sessions.values.forEach { it.close() }
        super.onDestroy()
    }

    @Composable
    fun BrowserView(session: EngineSession, onLongPressLink: (String) -> Unit) {
        key(session) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> GeckoEngineView(context).apply { render(session) } },
                update = { view -> view.render(session) }
            )
        }
    }
}

sealed class Screen {
    object Browser : Screen()
    object Tabs : Screen()
    object Extensions : Screen()
    object Connection : Screen()
    object Downloads : Screen()
    object Settings : Screen()
    object History : Screen()
    object CastHistory : Screen()
    object Bookmarks : Screen()
    object Home : Screen()
    object Remote : Screen()
    object Library : Screen()
    object DebridLibrary : Screen()
    object AddonSettings : Screen()
    data class LibraryDetail(val id: String, val type: String) : Screen()
}
