package com.playbridge.sender.browser

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
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
// import mozilla.components.browser.engine.gecko.GeckoEngineSession // Internal, do not use
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
import com.playbridge.sender.model.QRCodeData
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.ConnectionScreen
import com.playbridge.sender.ui.theme.PlayBridgeTheme
import mozilla.components.lib.state.ext.flow
import kotlinx.coroutines.flow.first
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.history.HistoryEntity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.zIndex

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import com.playbridge.sender.data.history.TabEntity
import com.playbridge.sender.data.history.HistoryDatabase
import androidx.activity.viewModels
import com.playbridge.sender.connection.ConnectionViewModel

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
        // Ensure sessions are synced when coming back from background (e.g., lock screen)
        // to prevent black/blank screens. We launch on Main thread to avoid blocking the initial
        // layout pass, letting the UI draw before heavy session creation begins.
        val state = Components.store.state
        lifecycleScope.launch(Dispatchers.Main) {
            tabManager.syncSessions(state.tabs, state.selectedTabId)
        }
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
    }

    private fun saveTabs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val state = Components.store.state
            val tabs = state.tabs
            val selectedId = state.selectedTabId
            
            val entities = tabs.map { tab ->
                TabEntity(
                    id = tab.id,
                    url = tab.content.url,
                    title = tab.content.title,
                    parentId = tab.parentId,
                    isSelected = (tab.id == selectedId)
                )
            }
            database.tabDao().updateTabs(entities)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)


        
        if (!Components.isEngineInitialized()) {
            Components.initialize(applicationContext)
        }
        VideoDetector.init(applicationContext)
        
        // Set tabManager reference for resolving Kotlin tab IDs from extension messages
        Components.tabManager = tabManager
        
        // Install the bundled video detector extension
        Components.installBundledExtension()

        database = DatabaseProvider.getDatabase(applicationContext)

        val libraryViewModel: LibraryViewModel by viewModels()

        // Restore tabs
        // Track whether tab restoration is complete to avoid blank screen
        val tabsRestoredOrReady = mutableStateOf(Components.store.state.tabs.isNotEmpty())

        lifecycleScope.launch(Dispatchers.IO) {
            if (Components.store.state.tabs.isEmpty()) {
                val savedTabs = database.tabDao().getAll()
                if (savedTabs.isNotEmpty()) {
                    val sessionTabs = savedTabs.map { entity ->
                        TabSessionState(
                            id = entity.id,
                            content = ContentState(url = entity.url, title = entity.title ?: ""),
                            parentId = entity.parentId
                        )
                    }
                    val selectedId = savedTabs.find { it.isSelected }?.id
                    withContext(Dispatchers.Main) {
                        tabManager.restoreTabs(sessionTabs, selectedId, Components.store)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                tabsRestoredOrReady.value = true
            }
        }

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Browser) }
            val clipboardManager = LocalClipboardManager.current
            val connectionState by connectionViewModel.connectionState.collectAsState()
            val scope = rememberCoroutineScope()
            
            // Database and History
            // Uses the activity-scoped database instance
            val historyDao = remember { database.historyDao() }
            val bookmarkDao = remember { database.bookmarkDao() }
            val addonDao = remember { database.addonDao() }
            val addonRepository = remember { com.playbridge.sender.data.library.AddonRepository(addonDao, cacheDir) }
            val installedAddons by addonDao.getAll().collectAsState(initial = emptyList())
            
            // Suggestions State
            var isEditing by remember { mutableStateOf(false) }
            var editUrl by remember { mutableStateOf("") }
            val suggestions by historyDao.search(editUrl).collectAsState(initial = emptyList())
            val allHistory by historyDao.getAll().collectAsState(initial = emptyList())
            
            // Connection ViewModel State
            val tvDevice by connectionViewModel.tvDevice.collectAsState(initial = null)
            val discoveredDevices by connectionViewModel.discoveredDevices.collectAsState()
            val history by connectionViewModel.deviceHistory.collectAsState(initial = emptyList())

            // Connection logic is now handled in ConnectionViewModel
            
            // Session and navigation state from BrowserStore
            val store = Components.store
            var browserState by remember { mutableStateOf(store.state) }
            
            // Observe store state changes
            LaunchedEffect(store) {
                store.flow().collect { state ->
                    browserState = state
                }
            }
            // TODO: correct observation logic
            
            // Ensure we have at least one tab
            LaunchedEffect(browserState.tabs.size) {
                tabManager.ensureAtLeastOneTab(store)
            }
            
            // Sync sessions with tabs
            LaunchedEffect(browserState.tabs, browserState.selectedTabId) {
                // LaunchedEffect already runs on the main dispatcher, we just yield or let it run
                // deferring it slightly is okay, but it should run on Main.
                tabManager.syncSessions(browserState.tabs, browserState.selectedTabId)
            }
            
            val sessions = tabManager.sessions

            val selectedTabId = browserState.selectedTabId
            val selectedTab = browserState.tabs.find { it.id == selectedTabId }
            val session = if (selectedTab != null) sessions[selectedTab.id] else null
            val freshLoadingTabIds by tabManager.freshLoadingTabIds
            
            // State for download dialog
            var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
            
            // If no session is available (e.g. during init), we normally show loading.
            // However, if we are in the Tabs screen, we can render the tabs screen standalone
            // without needing a selected session.
            if (session == null) {
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
                        tabManager.ensureAtLeastOneTab(store)
                    }
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@setContent
            }
            
            // Existing state variables
            var currentUrl by remember { mutableStateOf("about:blank") }
            var isLoading by remember { mutableStateOf(false) }
            var browserCanGoBack by remember { mutableStateOf(false) }
            var browserCanGoForward by remember { mutableStateOf(false) }
            var menuExpanded by remember { mutableStateOf(false) }
            
            // User preferences
            val prefs = remember { getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }

            val preferredAudioLang by remember { mutableStateOf(prefs.getString("preferred_audio_lang", "") ?: "") }
            val preferredSubLang by remember { mutableStateOf(prefs.getString("preferred_subtitle_lang", "") ?: "") }
            val defaultVideoQuality by remember { mutableStateOf(prefs.getString("default_video_quality", "Auto") ?: "Auto") }

            var detectVideosEnabled by remember { mutableStateOf(prefs.getBoolean("detect_videos", true)) }
            var isDesktopMode by remember { mutableStateOf(false) }
            var isSecureConnection by remember { mutableStateOf(false) }
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
            
            // View state - browser or scanner
            var showScanner by remember { mutableStateOf(false) }
            
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
            LaunchedEffect(selectedTabId, detectedVideos.size) {
                val tabId = selectedTabId ?: return@LaunchedEffect
                for (video in detectedVideos) {
                    if (video.isSubtitle) continue
                    if (!video.qualitiesChecked) {
                        launch { VideoDetector.fetchHlsQualities(video, tabId) }
                    }
                    if (!VideoDetector.hasThumbnail(video.url)) {
                        launch { VideoDetector.fetchThumbnail(video) }
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
            
            // Snackbar State
            val snackbarHostState = remember { SnackbarHostState() }
            var pendingPopupUrl by remember { mutableStateOf<String?>(null) }

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
            
            // Track previous URL to avoid clearing on hash changes
            var previousUrl by remember { mutableStateOf("") }
            
            // Context menu state for "Open in new tab"
            var contextMenuUrl by remember { mutableStateOf<String?>(null) }
            
            // Mutable state wrappers for SessionObserverSetup
            val currentUrlState = remember { mutableStateOf(currentUrl) }
            val isLoadingState = remember { mutableStateOf(isLoading) }
            val canGoBackState = remember { mutableStateOf(browserCanGoBack) }
            val canGoForwardState = remember { mutableStateOf(browserCanGoForward) }
            val contextMenuUrlState = remember { mutableStateOf(contextMenuUrl) }
            val previousUrlState = remember { mutableStateOf(previousUrl) }
            val pendingDownloadState = remember { mutableStateOf(pendingDownload) }
            val isSecureConnectionState = remember { mutableStateOf(isSecureConnection) }
            
            val pendingPopupUrlState = remember { mutableStateOf(pendingPopupUrl) }

            // Sync wrapper states back to local vars
            currentUrl = currentUrlState.value
            isLoading = isLoadingState.value
            browserCanGoBack = canGoBackState.value
            browserCanGoForward = canGoForwardState.value
            contextMenuUrl = contextMenuUrlState.value
            previousUrl = previousUrlState.value
            pendingDownload = pendingDownloadState.value
            isSecureConnection = isSecureConnectionState.value
            pendingPopupUrl = pendingPopupUrlState.value
            
            // Sync wrapper states from BrowserStore when the selected tab changes
            // This ensures the URL bar shows the correct URL immediately on tab switch
            LaunchedEffect(selectedTab?.id) {
                if (selectedTab != null) {
                    currentUrlState.value = selectedTab.content.url
                    previousUrlState.value = selectedTab.content.url
                    isSecureConnectionState.value = false
                }
            }
            // isDesktopMode is controlled by the UI, so we sync downwards to the observer setup
            // which will react to changes

            
            // Link context menu
            LinkContextMenu(
                url = contextMenuUrl,
                isConnected = connectionState is WebSocketClient.ConnectionState.Connected,
                onPlayOnTv = { linkUrl ->
                    val cmd = com.playbridge.protocol.createPlayCommandJson(
                        url = linkUrl, 
                        playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
                        preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                        preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                        defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" }
                    )
                    connectionViewModel.sendCommandAndRecord(cmd, "play", linkUrl, "Video Link")
                    Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onOpenInNewTab = { linkUrl ->
                    tabManager.createTab(linkUrl, store)
                    Toast.makeText(this@BrowserActivity, "Opened in new tab", Toast.LENGTH_SHORT).show()
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onOpenInBackground = { linkUrl ->
                    tabManager.createTab(linkUrl, store, select = false)
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
                browserCanGoBack = canGoBackState,
                browserCanGoForward = canGoForwardState,
                contextMenuUrl = contextMenuUrlState,
                previousUrl = previousUrlState,
                historyDao = historyDao,
                pendingDownload = pendingDownloadState,
                isDesktopMode = isDesktopMode,
                detectVideosEnabled = detectVideosEnabled,
                isSecureConnection = isSecureConnectionState,
                pendingPopupUrl = pendingPopupUrlState,
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
                            HorizontalDivider()

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
                                label = { Text("Command History", style = MaterialTheme.typography.titleMedium) },
                                selected = currentScreen == Screen.CommandHistory,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    currentScreen = Screen.CommandHistory
                                },
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                modifier = Modifier.height(48.dp)
                            )

                            Spacer(Modifier.height(8.dp))
                        }
                    }
                ) {
                    Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                            isDesktopMode = isDesktopMode,
                                            onDesktopModeChange = { isDesktopMode = it },
                                            onBookmarkClick = { handleBookmarkClick() },
                                            onEditingChange = { editing -> 
                                                isEditing = editing
                                                if (editing) {
                                                    editUrl = currentUrl
                                                }
                                            },
                                            onUrlChange = { url -> editUrl = url },
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
                                                    connectionViewModel.webSocketClient.send(com.playbridge.protocol.createContextQueryJson())
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
                                                
                                                // TV Controls (when connected)
                                                if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                    // Remote Control
                                                    AnimatedMenuItem(
                                                        index = 1,
                                                        onClick = {
                                                            menuExpanded = false
                                                            connectionViewModel.webSocketClient.send(com.playbridge.protocol.createContextQueryJson())
                                                            currentScreen = Screen.Remote
                                                        }
                                                    ) { onClick ->
                                                        DropdownMenuItem(
                                                            text = { Text("Remote Control", style = MaterialTheme.typography.bodyLarge) },
                                                            leadingIcon = { Icon(Icons.Default.Gamepad, null, tint = MaterialTheme.colorScheme.primary) },
                                                            onClick = onClick,
                                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                        )
                                                    }
                                                    
                                                    // Open on TV
                                                    AnimatedMenuItem(
                                                        index = 2,
                                                        onClick = {
                                                            menuExpanded = false
                                                            val cmd = com.playbridge.protocol.createBrowserCommandJson(
                                                                currentUrl, 
                                                                browserMode = prefs.getString("tv_browser_mode", "tv")?.takeIf { it != "tv" }
                                                            )
                                                            connectionViewModel.sendCommandAndRecord(cmd, "browser", currentUrl, "Browser Page")
                                                            Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                                                        }
                                                    ) { onClick ->
                                                        DropdownMenuItem(
                                                            text = { Text("Open on TV", style = MaterialTheme.typography.bodyLarge) },
                                                            leadingIcon = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary) },
                                                            onClick = onClick,
                                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                        )
                                                    }
                                                    
                                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                }

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
                                                        // Observer setup will react to this change
                                                        // menuExpanded = false // Keep open to see toggle switch?
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
                            Screen.Connection -> {
                                // No TopAppBar here as ConnectionScreen has its own now
                            }
                            Screen.History -> {
                                // No TopAppBar here as HistoryScreen has its own
                            }
                            Screen.CommandHistory -> {
                                // No TopAppBar here as CommandHistoryScreen has its own
                            }
                            Screen.Bookmarks -> {}
                            Screen.Home -> {}
                            Screen.Remote -> {
                                // No TopAppBar here as RemoteControlScreen has its own
                            }

                            Screen.Downloads -> {
                                // No TopAppBar here as DownloadsScreen has its own
                            }
                            Screen.Settings -> {
                                // No TopAppBar here as SettingsScreen has its own
                            }
                            Screen.Library -> {
                                // No TopAppBar here as LibraryScreen has its own
                            }
                            is Screen.MovieDetail -> {
                                // No TopAppBar here as MovieDetailScreen has its own
                            }
                            is Screen.TvShowDetail -> {
                                // No TopAppBar here as TvShowDetailScreen has its own
                            }
                            Screen.AddonSettings -> {
                                // No TopAppBar here as AddonSettingsScreen has its own
                            }
                            Screen.DebridLibrary -> {
                                // No TopAppBar here as DebridLibraryScreen has its own
                            }
                        }
                    }
                ) { innerPadding ->
                    val isOwnTopBar = currentScreen !in listOf(Screen.Browser, Screen.Tabs, Screen.Extensions)
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

                    Box(modifier = Modifier.padding(resolvedPadding)) {
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
                                    } else if ((targetState == Screen.Downloads || targetState == Screen.Extensions || targetState == Screen.Settings || targetState == Screen.Bookmarks || targetState == Screen.Remote || targetState == Screen.AddonSettings || targetState is Screen.MovieDetail || targetState is Screen.TvShowDetail) && (initialState == Screen.Browser || initialState == Screen.Library || initialState == Screen.DebridLibrary || initialState == Screen.Connection)) {
                                         androidx.compose.animation.slideInHorizontally { width -> width } + fadeIn() togetherWith
                                                androidx.compose.animation.slideOutHorizontally { width -> -width } + fadeOut()
                                    } else if ((targetState == Screen.Browser || targetState == Screen.Library || targetState == Screen.DebridLibrary || targetState == Screen.Connection) && (initialState == Screen.Downloads || initialState == Screen.Extensions || initialState == Screen.Settings || initialState == Screen.Bookmarks || initialState == Screen.Remote || initialState == Screen.AddonSettings || initialState is Screen.MovieDetail || initialState is Screen.TvShowDetail)) {
                                         androidx.compose.animation.slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                                androidx.compose.animation.slideOutHorizontally { width -> width } + fadeOut()
                                    } else {
                                        // Default fade for other transitions (e.g. settings)
                                        fadeIn() togetherWith fadeOut()
                                    }
                                },
                                label = "ScreenTransition"
                            ) { targetScreen ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    when (targetScreen) {
                                        Screen.Browser -> {
                                            // Fullscreen back handler — takes priority
                                            if (isFullscreen) {
                                                BackHandler {
                                                    isFullscreen = false
                                                    val gs = tabManager.getGeckoSession(session)
                                                    gs?.exitFullScreen()
                                                }
                                            }
                                            // Browser: first back goes to browser history, second back exits
                                            BackHandler(enabled = !isFullscreen) {
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
                                            
                                            // Clear fresh-load flag once the page finishes loading
                                            LaunchedEffect(isLoading) {
                                                if (!isLoading && selectedTabId != null) {
                                                    tabManager.freshLoadingTabIds.value =
                                                        tabManager.freshLoadingTabIds.value - selectedTabId
                                                }
                                            }

                                            // BrowserView call site
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                BrowserView(
                                                    session = session,
                                                    onLongPressLink = { url: String -> contextMenuUrl = url }
                                                )

                                                // Show loading overlay when session was freshly created (e.g. after lock screen
                                                // forces a process restart). Hides the blank GeckoEngineView during initial load.
                                                if (selectedTabId != null && selectedTabId in freshLoadingTabIds) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(MaterialTheme.colorScheme.background),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        CircularProgressIndicator()
                                                    }
                                                }
                                                
                                                // Home Screen Overlay
                                                if (currentUrl == "about:blank") {
                                                    HomeScreen(
                                                        onNavigate = { url -> 
                                                            session.loadUrl(url) 
                                                        },
                                                        historyDao = historyDao,
                                                        bookmarkDao = bookmarkDao
                                                    )
                                                }
                                                
                                                // Suggestions Overlay (Full Screen)
                                                if (isEditing) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(MaterialTheme.colorScheme.background)
                                                            .zIndex(1f) // Ensure it's on top of BrowserView
                                                    ) {
                                                        LazyColumn(
                                                            modifier = Modifier.fillMaxSize()
                                                        ) {
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
                                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                // Draggable Video FAB
                                                if (videoCount > 0 && detectVideosEnabled && !isEditing) {
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
                                                                    // Convert pixel drag to dp
                                                                    fabOffsetX += dragAmount.x / density
                                                                    fabOffsetY += dragAmount.y / density
                                                                }
                                                            }
                                                    ) {
                                                        BadgedBox(
                                                            badge = {
                                                                Badge(
                                                                    containerColor = MaterialTheme.colorScheme.error,
                                                                    contentColor = MaterialTheme.colorScheme.onError
                                                                ) {
                                                                    Text(videoCount.toString())
                                                                }
                                                            }
                                                        ) {
                                                            Icon(Icons.Default.PlayArrow, "Detected $videoCount videos")
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
                                        Screen.CommandHistory -> {
                                            val db = com.playbridge.sender.data.history.DatabaseProvider.getDatabase(androidx.compose.ui.platform.LocalContext.current)
                                            val commandHistoryFlow = remember { db.commandHistoryDao().getAll() }
                                            val commandHistory by commandHistoryFlow.collectAsState(initial = emptyList())
                                            CommandHistoryScreen(
                                                historyItems = commandHistory,
                                                onSendToTv = { item ->
                                                    if (connectionViewModel.webSocketClient.isConnected()) {
                                                        item.payloadJson?.let {
                                                            connectionViewModel.webSocketClient.send(it)
                                                        }
                                                        Toast.makeText(this@BrowserActivity, "Command sent", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onOpenLocally = { url ->
                                                    session.loadUrl(url)
                                                    currentScreen = Screen.Browser
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
                                            BackHandler {
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
                                            ConnectionScreen(
                                                viewModel = connectionViewModel,
                                                onMenuClick = { scope.launch { drawerState.open() } }
                                            )
                                        }
                                        Screen.Downloads -> {
                                            BackHandler { currentScreen = Screen.Browser }
                                            DownloadsScreen(
                                                onBack = { currentScreen = Screen.Browser },
                                                onPlayOnTv = { url, type ->
                                                    if (connectionState is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) {
                                                        val cmd = com.playbridge.protocol.createPlayCommandJson(
                                                            url = url,
                                                            title = "From Downloads",
                                                            contentType = type,
                                                            preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                                            preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                                            defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" }
                                                        )
                                                        connectionViewModel.sendCommandAndRecord(cmd, "play", url, "From Downloads")
                                                        Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            )
                                        }
                                        Screen.Settings -> {
                                            BackHandler { currentScreen = Screen.Browser }
                                            SettingsScreen(
                                                onBack = { currentScreen = Screen.Browser },
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
                                                when {
                                                    mac != null -> {
                                                        // Previously used device — auto-connect
                                                        connectionViewModel.bluetoothClient.connect(mac)
                                                    }
                                                    devices.size == 1 -> {
                                                        // Only one paired device — auto-connect and save
                                                        val onlyMac = devices[0].address
                                                        savedMac = onlyMac
                                                        connectionViewModel.saveBluetoothMacForTv(tvDevice?.uuid, onlyMac)
                                                        connectionViewModel.bluetoothClient.connect(onlyMac)
                                                    }
                                                    // Multiple devices and no prior selection — show dialog (user taps BT icon)
                                                }
                                            }

                                            // Handle Bluetooth permissions request for Android 12+
                                            val btPermissionLauncher = rememberLauncherForActivityResult(
                                                ActivityResultContracts.RequestMultiplePermissions()
                                            ) { permissions ->
                                                val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
                                                val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] ?: false

                                                if (connectGranted && scanGranted) {
                                                    initBluetooth()
                                                } else {
                                                    Toast.makeText(this@BrowserActivity, "Bluetooth permissions denied. Falling back to Wi-Fi.", Toast.LENGTH_SHORT).show()
                                                }
                                            }

                                            LaunchedEffect(Unit) {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                                    val needsConnect = ContextCompat.checkSelfPermission(this@BrowserActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                                                    val needsScan = ContextCompat.checkSelfPermission(this@BrowserActivity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED

                                                    if (needsConnect || needsScan) {
                                                        btPermissionLauncher.launch(
                                                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                                                        )
                                                    } else {
                                                        initBluetooth()
                                                    }
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
                                                        val cmd = com.playbridge.protocol.createRemoteCommandJson(key)
                                                        connectionViewModel.webSocketClient.send(cmd)
                                                    }
                                                },
                                                onMouseMove = { dx, dy ->
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendMouseCommand("move", dx, dy)
                                                    } else {
                                                        val cmd = com.playbridge.protocol.createMouseCommandJson("move", dx, dy)
                                                        connectionViewModel.webSocketClient.send(cmd)
                                                    }
                                                },
                                                onMouseClick = {
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendMouseCommand("click", 0f, 0f)
                                                    } else {
                                                        val cmd = com.playbridge.protocol.createMouseCommandJson("click")
                                                        connectionViewModel.webSocketClient.send(cmd)
                                                    }
                                                },
                                                onMouseScroll = { dx, dy ->
                                                    if (btConnectionState is com.playbridge.sender.connection.BluetoothClient.ConnectionState.Connected) {
                                                        connectionViewModel.bluetoothClient.sendMouseCommand("scroll", dx, dy)
                                                    } else {
                                                        val cmd = com.playbridge.protocol.createMouseCommandJson("scroll", dx, dy)
                                                        connectionViewModel.webSocketClient.send(cmd)
                                                    }
                                                },
                                                onBrowserControl = { action ->
                                                    // Browser controls still go over websocket as they affect the TV browser, not OS mouse
                                                    val cmd = com.playbridge.protocol.createBrowserControlCommandJson(action)
                                                    connectionViewModel.webSocketClient.send(cmd)
                                                },
                                                onPlayerControl = { command ->
                                                    // Player controls still go over websocket
                                                    val cmd = com.playbridge.protocol.createControlCommandJson(command)
                                                    connectionViewModel.webSocketClient.send(cmd)
                                                    if (command == "stop") {
                                                        tvActiveContext = "idle"
                                                    }
                                                }
                                            )
                                        }
                                        Screen.Home -> {
                                            BackHandler { 
                                                if (browserCanGoBack) {
                                                    session.goBack() 
                                                } else {
                                                    finish()
                                                }
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
                                            // Derive the currently playing episode from playlist index + startEpisode
                                            val nowPlayingEp = tvPlaylistState?.let {
                                                nowPlayingEpisodeStart + it.currentIndex
                                            }
                                            LibraryScreen(
                                                viewModel = libraryViewModel,
                                                onMenuClick = { scope.launch { drawerState.open() } },
                                                onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                    {
                                                        connectionViewModel.webSocketClient.send(com.playbridge.protocol.createContextQueryJson())
                                                        currentScreen = Screen.Remote
                                                    }
                                                } else null,
                                                nowPlayingTvId = nowPlayingTvId,
                                                nowPlayingSeason = nowPlayingSeason,
                                                nowPlayingEpisode = nowPlayingEp,
                                                onNowPlayingClick = {
                                                    nowPlayingTvId?.let { id ->
                                                        currentScreen = Screen.TvShowDetail(id)
                                                    }
                                                },
                                                onMovieClick = { movieId ->
                                                    currentScreen = Screen.MovieDetail(movieId)
                                                },
                                                onTvShowClick = { tvId ->
                                                    currentScreen = Screen.TvShowDetail(tvId)
                                                }
                                            )
                                        }
                                        is Screen.MovieDetail -> {
                                            val movieId = (targetScreen as Screen.MovieDetail).movieId
                                            BackHandler { currentScreen = Screen.Library }
                                            MovieDetailScreen(
                                                movieId = movieId,
                                                addonRepository = addonRepository,
                                                onPlayStream = { url, title, subtitles ->
                                                    val mainVideo = DetectedVideo(
                                                        url = url,
                                                        title = title,
                                                        tabId = -1,
                                                        timestamp = System.currentTimeMillis(),
                                                        isPlayable = true,
                                                        detectedBy = "library"
                                                    )

                                                    val subVideos = subtitles?.map { subUrl ->
                                                        DetectedVideo(
                                                            url = subUrl,
                                                            tabId = -1,
                                                            timestamp = System.currentTimeMillis(),
                                                            contentType = "text/vtt",
                                                            detectedBy = "library_subtitle"
                                                        )
                                                    } ?: emptyList()

                                                    scope.launch {
                                                        forcedVideos = listOf(mainVideo) + subVideos
                                                        showVideoSheet = true
                                                    }
                                                },
                                                onBack = { currentScreen = Screen.Library }
                                            )
                                        }
                                        is Screen.TvShowDetail -> {
                                            val tvId = (targetScreen as Screen.TvShowDetail).tvId
                                            BackHandler { currentScreen = Screen.Library }
                                            TvShowDetailScreen(
                                                tvId = tvId,
                                                addonRepository = addonRepository,
                                                onPlayStream = { url, title, subtitles ->
                                                    val mainVideo = DetectedVideo(
                                                        url = url,
                                                        title = title,
                                                        tabId = -1,
                                                        timestamp = System.currentTimeMillis(),
                                                        isPlayable = true,
                                                        detectedBy = "library"
                                                    )

                                                    val subVideos = subtitles?.map { subUrl ->
                                                        DetectedVideo(
                                                            url = subUrl,
                                                            tabId = -1,
                                                            timestamp = System.currentTimeMillis(),
                                                            contentType = "text/vtt",
                                                            detectedBy = "library_subtitle"
                                                        )
                                                    } ?: emptyList()

                                                    scope.launch {
                                                        forcedVideos = listOf(mainVideo) + subVideos
                                                        showVideoSheet = true
                                                    }
                                                },
                                                onPlayPlaylist = { items ->
                                                    val playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
                                                    val itemsWithMode = items.map {
                                                        it.copy(
                                                            playerMode = playerMode,
                                                            preferredAudioLanguage = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                                            preferredSubtitleLanguage = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                                            defaultVideoQuality = defaultVideoQuality.takeIf { q -> q != "Auto" }
                                                        )
                                                    }
                                                    val cmd = com.playbridge.protocol.createPlaylistCommandJson(items = itemsWithMode)
                                                    connectionViewModel.webSocketClient.send(cmd)
                                                },
                                                onQueueAdd = { item ->
                                                    val playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
                                                    val itemWithPrefs = item.copy(
                                                        playerMode = playerMode,
                                                        preferredAudioLanguage = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                                        preferredSubtitleLanguage = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                                        defaultVideoQuality = defaultVideoQuality.takeIf { q -> q != "Auto" }
                                                    )
                                                    connectionViewModel.webSocketClient.send(
                                                        com.playbridge.protocol.createQueueAddCommandJson(itemWithPrefs)
                                                    )
                                                },
                                                onPlaylistJump = { index ->
                                                    connectionViewModel.webSocketClient.send(
                                                        com.playbridge.protocol.createPlaylistJumpCommandJson(index)
                                                    )
                                                },
                                                onNowPlayingStarted = { id, season, startEp ->
                                                    nowPlayingTvId = id
                                                    nowPlayingSeason = season
                                                    nowPlayingEpisodeStart = startEp
                                                },
                                                // Highlight the playing season and episode when navigated via play icon
                                                highlightSeason = if (tvId == nowPlayingTvId) nowPlayingSeason else null,
                                                highlightEpisode = if (tvId == nowPlayingTvId) {
                                                    tvPlaylistState?.let { nowPlayingEpisodeStart + it.currentIndex }
                                                } else null,
                                                playlistState = tvPlaylistState,
                                                onBack = { currentScreen = Screen.Library }
                                            )
                                        }
                                        Screen.AddonSettings -> {
                                            BackHandler { currentScreen = Screen.Settings }
                                            AddonSettingsScreen(
                                                addonRepository = addonRepository,
                                                installedAddons = installedAddons,
                                                onBack = { currentScreen = Screen.Settings }
                                            )
                                        }
                                        Screen.DebridLibrary -> {
                                            BackHandler { finish() }
                                            DebridLibraryScreen(
                                                onMenuClick = { scope.launch { drawerState.open() } },
                                                onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                    {
                                                        connectionViewModel.webSocketClient.send(com.playbridge.protocol.createContextQueryJson())
                                                        currentScreen = Screen.Remote
                                                    }
                                                } else null,
                                                onCopyUrl = { linkUrl ->
                                                    clipboardManager.setText(AnnotatedString(linkUrl))
                                                    Toast.makeText(this@BrowserActivity, "Link copied", Toast.LENGTH_SHORT).show()
                                                },
                                                onPlayOnTv = { playUrl, title, subtitles ->
                                                    if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                        val cmd = com.playbridge.protocol.createPlayCommandJson(
                                                            url = playUrl,
                                                            title = title,
                                                            subtitles = subtitles,
                                                            playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
                                                            preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                                            preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                                            defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" }
                                                        )
                                                        connectionViewModel.sendCommandAndRecord(cmd, "play", playUrl, title ?: "Video")
                                                        Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onPlayPlaylistOnTv = { items ->
                                                    val playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
                                                    val itemsWithMode = items.map {
                                                        it.copy(
                                                            playerMode = playerMode,
                                                            preferredAudioLanguage = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                                            preferredSubtitleLanguage = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                                            defaultVideoQuality = defaultVideoQuality.takeIf { q -> q != "Auto" }
                                                        )
                                                    }

                                                    val detectedVideo = DetectedVideo(
                                                        url = "playlist://debrid",
                                                        tabId = -1,
                                                        timestamp = System.currentTimeMillis(),
                                                        isPlayable = true,
                                                        detectedBy = "debrid_playlist",
                                                        playlistPayload = itemsWithMode
                                                    )

                                                    scope.launch {
                                                        forcePlaylistSheet = detectedVideo
                                                        showVideoSheet = true
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                    }
                }
                
                // Video detection bottom sheet
                if (showVideoSheet) {
                    DetectedVideosSheet(
                        videos = detectedVideos,
                        onDismiss = {
                            showVideoSheet = false
                            forcePlaylistSheet = null
                            forcedVideos = null
                        },
                        playerMode = sheetPlayerMode,
                        onPlayerModeChange = { mode ->
                            sheetPlayerMode = mode
                            prefs.edit().putString("tv_player_mode", mode).apply()
                        },
                        onVideoClick = { video, subtitles ->
                            Log.d(TAG, "=== PLAY ON TV CLICKED ===")
                            Log.d(TAG, "Video URL: ${video.url}")
                            Log.d(TAG, "Subtitles: $subtitles")
                            Log.d(TAG, "Connection state: $connectionState")
                            
                            when (val state = connectionState) {
                                is WebSocketClient.ConnectionState.Connected -> {
                                    Log.d(TAG, "Connected to: ${state.serverName}")
                                    
                                    if (video.playlistPayload != null) {
                                        val cmd = com.playbridge.protocol.createPlaylistCommandJson(items = video.playlistPayload)
                                        val sent = connectionViewModel.webSocketClient.send(cmd)
                                        if (sent) {
                                            tvActiveContext = "player"
                                            Toast.makeText(
                                                this@BrowserActivity,
                                                "Playlist sent to ${state.serverName}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        // Use mediaHeaders() to strip browser-context headers (Sec-Fetch-*, etc.)
                                        // that cause CDN token validation to reject requests from non-browser clients.
                                        // Cookie, Referer, Origin, and User-Agent are preserved.
                                        val headers = VideoDetector.mediaHeaders(video)

                                        // Fall back to originUrl as Referer if not captured in request headers
                                        if (!video.originUrl.isNullOrEmpty() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                                            headers["Referer"] = video.originUrl
                                        }

                                        val commandJson = com.playbridge.protocol.createPlayCommandJson(
                                            url = video.url,
                                            title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                            headers = headers,
                                            contentType = video.contentType,
                                            subtitles = subtitles,
                                            detectedBy = video.detectedBy,
                                            playerMode = sheetPlayerMode.takeIf { it != "tv" },
                                            preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                            preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                            defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" }
                                        )
                                        Log.d(TAG, "Sending play command: $commandJson")
                                        connectionViewModel.sendCommandAndRecord(commandJson, "play", video.url, selectedTab?.content?.title ?: "Video from browser")
                                        val sent = connectionViewModel.webSocketClient.send(commandJson)
                                        Log.d(TAG, "Command sent: $sent")

                                        if (sent) {
                                            tvActiveContext = "player"
                                            Toast.makeText(
                                                this@BrowserActivity,
                                                "Playing on ${state.serverName}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                is WebSocketClient.ConnectionState.Connecting,
                                is WebSocketClient.ConnectionState.Retrying -> {
                                    Toast.makeText(
                                        this@BrowserActivity,
                                        "Connecting to TV...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                else -> {
                                    Toast.makeText(
                                        this@BrowserActivity,
                                        "Not connected to TV",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            showVideoSheet = false
                            forcePlaylistSheet = null
                        },
                        onClear = {
                            VideoDetector.clearTab(selectedTabId ?: "")
                        },
                        onDownload = { video ->
                            DownloadUtils.enqueueDownload(
                                this@BrowserActivity,
                                video.url,
                                null,
                                video.contentType,
                                video.headers?.get("User-Agent"),
                                video.headers?.get("Cookie"),
                                video.headers?.get("Referer") ?: video.originUrl
                            )
                        }
                    )
                }
                
                // Show Popup Blocked Snackbar
                LaunchedEffect(pendingPopupUrl) {
                    pendingPopupUrl?.let { url ->
                        val host = try {
                            java.net.URI(url).host ?: url.take(30)
                        } catch (e: Exception) {
                            url.take(30)
                        }

                        val result = snackbarHostState.showSnackbar(
                            message = "Popup blocked from $host",
                            actionLabel = "Allow",
                            duration = SnackbarDuration.Long
                        )

                        if (result == SnackbarResult.ActionPerformed) {
                            tabManager.createTab(url, store, parentId = selectedTab?.id)
                        }
                        pendingPopupUrl = null
                        pendingPopupUrlState.value = null
                    }
                }

                // Download Confirmation Dialog
                DownloadConfirmDialog(
                    pendingDownload = pendingDownload,
                    onConfirm = { download: PendingDownload ->
                        DownloadUtils.enqueueDownload(
                            this@BrowserActivity,
                            download.url,
                            download.fileName,
                            download.contentType,
                            download.userAgent,
                            download.cookie,
                            download.referer
                        )
                        Toast.makeText(this@BrowserActivity, "Download started", Toast.LENGTH_SHORT).show()
                        pendingDownload = null
                        pendingDownloadState.value = null
                    },
                    onPlayOnTv = { download: PendingDownload ->
                        val headers = mutableMapOf<String, String>()
                        if (download.userAgent != null) headers["User-Agent"] = download.userAgent
                        if (download.referer != null) headers["Referer"] = download.referer
                        if (download.cookie != null) headers["Cookie"] = download.cookie

                        when (val state = connectionState) {
                            is WebSocketClient.ConnectionState.Connected -> {
                                val commandJson = com.playbridge.protocol.createPlayCommandJson(
                                    url = download.url,
                                    title = selectedTab?.content?.title ?: download.fileName ?: "Video from browser",
                                    headers = headers,
                                    contentType = download.contentType,
                                    subtitles = null,
                                    detectedBy = "download",
                                    playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
                                    preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                    preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                    defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" }
                                )
                                connectionViewModel.sendCommandAndRecord(commandJson, "play", download.url, selectedTab?.content?.title ?: download.fileName ?: "Video from browser")
                                val sent = connectionViewModel.webSocketClient.send(commandJson)
                                if (sent) {
                                    tvActiveContext = "player"
                                    Toast.makeText(this@BrowserActivity, "Playing on ${state.serverName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            is WebSocketClient.ConnectionState.Connecting,
                            is WebSocketClient.ConnectionState.Retrying -> {
                                Toast.makeText(this@BrowserActivity, "Connecting to TV...", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show()
                            }
                        }
                        pendingDownload = null
                        pendingDownloadState.value = null
                    },
                    onDismiss = {
                        pendingDownload = null
                        pendingDownloadState.value = null
                    }
                )

                // Magnet and Torrent Parsing Sheet
                if (interceptedMagnet != null || interceptedTorrentBytes != null) {
                    val provider = debridRepository.getActiveProvider()
                    if (provider != null) {
                        MagnetParsingSheet(
                            magnetUri = interceptedMagnet,
                            torrentBytes = interceptedTorrentBytes,
                            provider = provider,
                            onDismiss = { 
                                interceptedMagnet = null
                                interceptedTorrentBytes = null
                            },
                            onPlayLinks = { links ->
                                val videos = links.map { link ->
                                    com.playbridge.protocol.PlayPayload(
                                        url = link.downloadUrl,
                                        title = link.filename,
                                        playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
                                        preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
                                        preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
                                        defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" }
                                    )
                                }

                                val detectedVideo = DetectedVideo(
                                    url = if (links.size == 1) links.first().downloadUrl else "playlist://magnet",
                                    tabId = -1,
                                    timestamp = System.currentTimeMillis(),
                                    isPlayable = true,
                                    detectedBy = "magnet_playlist",
                                    playlistPayload = if (links.size > 1) videos else null
                                )

                                scope.launch {
                                    forcePlaylistSheet = detectedVideo
                                    showVideoSheet = true
                                }

                                interceptedMagnet = null
                                interceptedTorrentBytes = null
                            }
                        )
                    } else {
                        Toast.makeText(this@BrowserActivity, "No Debrid provider configured. Configure it in Settings.", Toast.LENGTH_LONG).show()
                        interceptedMagnet = null
                        interceptedTorrentBytes = null
                    }
                }
            }
        }
    }
    }


    override fun onDestroy() {
        // webSocketClient destruction is now handled by ConnectionViewModel's onCleared
        super.onDestroy()
    }
    

    @Composable
    fun BrowserView(session: EngineSession, onLongPressLink: (String) -> Unit) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                GeckoEngineView(context).apply {
                    render(session)
                }
            },
            update = { view ->
                view.render(session)
            }
        )
        
        // Context menu handling verification
        // Logic removed temporarily as GeckoEngineSession.geckoSession is internal
        // TODO: Implement context menu using proper EngineSession API or custom GeckoView integration
        /*
        val geckoSession = (session as? GeckoEngineSession)?.geckoSession
        if (geckoSession != null) {
            DisposableEffect(session) {
                val delegate = object : org.mozilla.geckoview.GeckoSession.ContentDelegate {
                     // ...
                }
                geckoSession.contentDelegate = delegate
                onDispose { geckoSession.contentDelegate = null }
            }
        }
        */
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
    object CommandHistory : Screen()
    object Bookmarks : Screen()
    object Home : Screen()
    object Remote : Screen()
    object Library : Screen()
    object DebridLibrary : Screen()
    object AddonSettings : Screen()
    data class MovieDetail(val movieId: Int) : Screen()
    data class TvShowDetail(val tvId: Int) : Screen()
}
