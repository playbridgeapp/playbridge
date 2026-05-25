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
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex

import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import com.playbridge.sender.data.history.TabEntity
import com.playbridge.sender.data.history.HistoryDatabase
import androidx.activity.viewModels
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.shared.protocol.createSingleVideoCommandJson
import com.playbridge.shared.protocol.createPlaylistCommandJson
import playbridge.PlaylistPayload
import playbridge.PlayPayload

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
        val now = System.currentTimeMillis()

        Log.d(TAG, "saveTabs: starting save for ${tabs.size} tabs, captured ${allStates.size} session states")

        // Use Components.applicationScope to ensure the database save starts and finishes
        // even when the Activity is paused, stopped, or destroyed.
        Components.applicationScope.launch {
            try {
                val entities = tabs.mapIndexed { index, tab ->
                    TabEntity(
                        id = tab.id,
                        url = tab.content.url,
                        title = tab.content.title,
                        parentId = tabManager.parentIds[tab.id] ?: tab.parentId,
                        isSelected = (tab.id == selectedId),
                        lastAccessTime = now,
                        sessionState = allStates[tab.id],
                        position = index
                    )
                }
                database.tabDao().updateTabs(entities)
                Log.d(TAG, "saveTabs: successfully updated DB with ${entities.size} tabs")
            } catch (e: Exception) {
                Log.e(TAG, "saveTabs: failed to update DB", e)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
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
            var isSettingsFromLibrary by remember { mutableStateOf(false) }
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
            val tmdbRepository = remember { com.playbridge.sender.data.library.TmdbRepository(this@BrowserActivity) }
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

            // Debounced persistence: any onStateUpdated or store tab/selection change marks the tabs dirty;
            // we save at most once every 1.5s, plus a final flush on dispose.
            val saveDirtyFlow = remember { kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 16) }
            DisposableEffect(Unit) {
                tabManager.onAnyStateUpdated = { _ -> saveDirtyFlow.tryEmit(Unit) }
                onDispose { tabManager.onAnyStateUpdated = null }
            }
            LaunchedEffect(Unit) {
                var lastSaveJob: kotlinx.coroutines.Job? = null
                saveDirtyFlow.collect {
                    lastSaveJob?.cancel()
                    lastSaveJob = scope.launch {
                        kotlinx.coroutines.delay(1500)
                        saveTabs()
                    }
                }
            }

            // Observe store state changes
            LaunchedEffect(store) {
                Log.d("PB_STARTUP", "Compose: store.flow() collector started")
                var lastSelectedId = browserState.selectedTabId
                var lastTabsIds = browserState.tabs.map { it.id }
                store.flow().collect { state ->
                    val newTabsIds = state.tabs.map { it.id }
                    val newSelected = state.selectedTabId

                    val tabsChanged = newTabsIds != lastTabsIds
                    val selectedChanged = newSelected != lastSelectedId

                    if (tabsChanged || selectedChanged) {
                        Log.d("PB_STARTUP", "Compose: store.flow() tab/selection change — tabsChanged=$tabsChanged, selectedChanged=$selectedChanged. Scheduling save.")
                        saveDirtyFlow.tryEmit(Unit)
                    }

                    if (state.tabs.size != browserState.tabs.size || state.selectedTabId != browserState.selectedTabId) {
                        Log.d("PB_STARTUP", "Compose: store.flow() emitted — tabs=${state.tabs.size}, selectedTabId=${state.selectedTabId}")
                    }
                    // Record selection so the close-tab fallback stack stays in sync
                    // even when tabs are selected via direct store dispatches that
                    // bypass tabManager.selectTab.
                    if (newSelected != null && newSelected != lastSelectedId) {
                        tabManager.recordSelection(newSelected)
                    }

                    lastSelectedId = newSelected
                    lastTabsIds = newTabsIds
                    browserState = state
                }
            }

            LaunchedEffect(tabsRestoredOrReady.value) {
                Log.d("PB_STARTUP", "Compose: tabsRestoredOrReady=${tabsRestoredOrReady.value} — force-syncing browserState from store (${store.state.tabs.size} tabs, selectedTabId=${store.state.selectedTabId})")
                browserState = store.state

                if (tabsRestoredOrReady.value && store.state.tabs.isEmpty()) {
                    Log.d("PB_STARTUP", "Compose: tabsRestored=true and store empty — calling ensureAtLeastOneTab")
                    tabManager.ensureAtLeastOneTab(store)
                }
            }

            val tabIds = browserState.tabs.map { it.id }
            LaunchedEffect(tabIds, browserState.selectedTabId) {
                Log.d("PB_STARTUP", "Compose: syncSessions triggered — tabCount=${browserState.tabs.size}, selectedTabId=${browserState.selectedTabId}")
                tabManager.syncSessions(browserState.tabs, browserState.selectedTabId)
                Log.d("PB_STARTUP", "Compose: syncSessions returned — sessions.keys=${tabManager.sessions.keys.size}")
                // Purge per-tab state for any tab that was externally removed
                // from the store (e.g. via Mozilla Components paths that bypass
                // closeTab). This is the safety net for memory leaks.
                tabManager.reconcileWithStoreTabs(tabIds.toSet())
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
                        Scaffold(
                            topBar = {
                                @OptIn(ExperimentalMaterial3Api::class)
                                TopAppBar(
                                    title = { Text("Tabs") },
                                    actions = {
                                        IconButton(onClick = {
                                            tabManager.createTab("about:blank", store)
                                            currentScreen = Screen.Browser
                                        }) {
                                            Icon(Icons.Default.Add, "New Tab")
                                        }

                                        var menuExpanded by remember { mutableStateOf(false) }
                                        val playingTabIds = tabManager.playingTabIds

                                        Box {
                                            IconButton(onClick = { menuExpanded = true }) {
                                                Icon(Icons.Default.MoreVert, "More options")
                                            }
                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Go to playing tab") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        playingTabIds.keys.firstOrNull()?.let {
                                                            tabManager.selectTab(it, store)
                                                            currentScreen = Screen.Browser
                                                        }
                                                    },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                                                    enabled = playingTabIds.isNotEmpty()
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Reopen Closed Tab") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        tabManager.reopenClosedTab(store)?.let {
                                                            currentScreen = Screen.Browser
                                                        }
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Restore, null) },
                                                    enabled = tabManager.canReopenClosedTab()
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        ) { innerPadding ->
                            Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
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
                                    },
                                    onTabDuplicate = { tabId ->
                                        tabManager.duplicateTab(tabId, store)
                                    },
                                    onTabBookmark = { tabId ->
                                        val targetTab = store.state.tabs.find { it.id == tabId }
                                        targetTab?.let { tab ->
                                             val url = tab.content.url
                                             if (url.isNotEmpty() && url != "about:blank") {
                                                 scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                     bookmarkDao.insert(
                                                         com.playbridge.sender.data.history.BookmarkEntity(
                                                             url = url,
                                                             title = tab.content.title.ifEmpty { null },
                                                             timestamp = System.currentTimeMillis()
                                                         )
                                                     )
                                                     scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                                         Toast.makeText(this@BrowserActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
                                                     }
                                                 }
                                             }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    return@setContent
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
            var showMenuSheet by remember { mutableStateOf(false) }
            val sheetState = rememberModalBottomSheetState()

            // User preferences
            val prefs = remember { getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
            var autoSwitchToRemote by remember { mutableStateOf(prefs.getBoolean("auto_switch_to_remote", false)) }

            // Sync tab manager settings from prefs
            DisposableEffect(prefs) {
                tabManager.maxAliveSessions = prefs.getInt("max_alive_tabs", 5)
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == "max_alive_tabs") {
                        tabManager.maxAliveSessions = p.getInt(key, 5)
                        Log.d("TabManager", "Updated maxAliveSessions to ${tabManager.maxAliveSessions} from prefs")
                    } else if (key == "auto_switch_to_remote") {
                        autoSwitchToRemote = p.getBoolean(key, false)
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
                if (currentScreen is Screen.Browser || currentScreen is Screen.Library || currentScreen is Screen.DebridLibrary || currentScreen is Screen.LibraryDetail) {
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
                com.playbridge.sender.browser.Components.onBridgeCastRequest = { items, startIndex, playlistMetadata ->
                    Log.d("BrowserActivity", "Cast requested via Extension Bridge: ${items.size} items, startIndex: $startIndex")
                    
                    lifecycleScope.launch {
                        // Reconnect attempt before sending — mirrors LibraryDetailScreen behavior
                        val savedDevice = connectionViewModel.tvDevice.first()
                        if (savedDevice != null && connectionViewModel.connectionState.value !is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) {
                            Log.i("BrowserActivity", "TV disconnected, attempting reconnection before bridge cast")
                            runOnUiThread {
                                Toast.makeText(this@BrowserActivity, "Connecting to TV...", Toast.LENGTH_SHORT).show()
                            }
                            connectionViewModel.connect(savedDevice!!)
                            
                            // Wait for connection with timeout (e.g. 8 seconds)
                            // This allows the Hub UI to trigger a play even if the app was backgrounded and lost connection
                            withTimeoutOrNull(8000) {
                                connectionViewModel.connectionState.first { it is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected }
                            }

                            if (connectionViewModel.connectionState.value !is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) {
                                Log.w("BrowserActivity", "Wait for connection timed out or failed. Aborting cast.")
                                runOnUiThread {
                                    Toast.makeText(this@BrowserActivity, "Could not connect to TV", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                        }

                        val currentMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
                        
                        val playPayloads = items.map { item ->
                            item.copy(
                                player_mode = item.player_mode ?: currentMode,
                                preferred_audio_language = item.preferred_audio_language ?: preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = item.preferred_subtitle_language ?: preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = item.default_video_quality ?: defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = item.max_bitrate_cap_mbps ?: maxBitrateCapMbps,
                            )
                        }

                        if (playPayloads.isNotEmpty()) {
                            val cmd = createPlaylistCommandJson(PlaylistPayload(
                                items = playPayloads,
                                start_index = startIndex,
                                visual_metadata = playlistMetadata,
                            ))
                            
                            connectionViewModel.sendCommandAndRecord(
                                commandJson = cmd,
                                type = "playlist",
                                url = playPayloads[startIndex.coerceIn(0, playPayloads.size - 1)].url,
                                title = playPayloads[startIndex.coerceIn(0, playPayloads.size - 1)].title ?: "Playlist"
                            )
                        }
                    }
                }
            }

            var detectVideosEnabled by remember { mutableStateOf(prefs.getBoolean("detect_videos", true)) }
            var isDesktopMode by remember { mutableStateOf(false) }
            var isSecureConnection by remember { mutableStateOf(false) }
            var siteSecurityInfo by remember { mutableStateOf<SiteSecurityInfo?>(null) }
            var showSiteInfoSheet by remember { mutableStateOf(false) }
            var isFullscreen by remember { mutableStateOf(false) }
            var isFullscreenVideoPortrait by remember { mutableStateOf(false) }

            // Fullscreen: hide/show system bars and handle auto-rotation
            val view = LocalView.current
            LaunchedEffect(isFullscreen, isFullscreenVideoPortrait) {
                val window = this@BrowserActivity.window ?: return@LaunchedEffect
                val controller = WindowInsetsControllerCompat(window, view)
                if (isFullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    requestedOrientation = if (isFullscreenVideoPortrait) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
            var pendingContentPayload by remember { mutableStateOf<playbridge.PlayPayload?>(null) }

            // TV active context - updated via WebSocket messages from TV
            var tvActiveContext by remember { mutableStateOf("idle") } // "player", "browser", or "idle"
            // TV playlist state - updated via playlist_status messages from TV
            var tvPlaylistState by remember { mutableStateOf<PlaylistUiState?>(null) }
            // TV playback status (state/position/duration/title) - updated via status messages from TV
            var tvPlayback by remember { mutableStateOf<TvPlaybackStatus?>(null) }
            // Available audio/subtitle tracks on the TV - updated via tracks messages
            var tvAudioTracks by remember { mutableStateOf<List<MediaTrack>>(emptyList()) }
            var tvSubtitleTracks by remember { mutableStateOf<List<MediaTrack>>(emptyList()) }
            // TV player settings (speed/scaling/audio-boost/subtitle-offset/filter/engine)
            var tvPlayerSettings by remember { mutableStateOf(TvPlayerSettings()) }
            // Now Playing context - set when a playlist play starts from LibraryDetailScreen
            var nowPlayingTvId by remember { mutableStateOf<Int?>(null) }
            var nowPlayingSeason by remember { mutableStateOf<Int?>(null) }
            var nowPlayingEpisodeStart by remember { mutableStateOf<Int>(1) } // episode number of playlist index 0
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

            val haptic = LocalHapticFeedback.current

            fun performQuickCast() {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                scope.launch {
                    if (connectionState !is WebSocketClient.ConnectionState.Connected) {
                        tvDevice?.let { device ->
                            Toast.makeText(this@BrowserActivity, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
                            connectionViewModel.connect(device)
                            withTimeoutOrNull(5000) {
                                while (connectionState !is WebSocketClient.ConnectionState.Connected) {
                                    delay(250)
                                }
                            }
                        }
                    }

                    val connection = connectionState as? WebSocketClient.ConnectionState.Connected
                    if (connection == null) {
                        Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // Give a small buffer for the connection to fully stabilize
                    delay(300)

                    // 1. Check Library Content (highest priority)
                    val content = pendingContentPayload
                    if (content != null && videoCount == 0) {
                        val cmd = createSingleVideoCommandJson(
                            PlayPayload(
                                url = content.url,
                                title = content.title,
                                content_type = content.content_type,
                                detected_by = content.detected_by,
                                player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                                visual_metadata = content.visual_metadata,
                            )
                        )
                        if (connectionViewModel.webSocketClient.send(cmd)) {
                            tvActiveContext = "player"
                            if (autoSwitchToRemote) {
                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                currentScreen = Screen.Remote
                            }
                            Toast.makeText(this@BrowserActivity, "Playing ${content.title}", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    // 2. Check detected videos
                    val playable = detectedVideos.filter { !it.isSubtitle }
                        .sortedWith(
                            compareByDescending<DetectedVideo> { video ->
                                val hasMaster = video.url.contains("master", ignoreCase = true)
                                val base = when {
                                    video.hlsPlaylist?.videoQualities?.isNotEmpty() == true -> 5
                                    video.isPlayable == true && (video.url.contains(".m3u8", ignoreCase = true) || video.url.contains(".mpd", ignoreCase = true)) -> 4
                                    video.isPlayable == false -> 1
                                    else -> 2
                                }
                                if (hasMaster) base + 1 else base
                            }.thenByDescending { it.timestamp }
                        )

                    if (playable.isNotEmpty()) {
                        val video = playable.first()
                        if (video.playlistPayload != null) {
                            val cmd = com.playbridge.shared.protocol.createPlaylistCommandJson(
                                payload = playbridge.PlaylistPayload(items = video.playlistPayload!!)
                            )
                            if (connectionViewModel.webSocketClient.send(cmd)) {
                                tvActiveContext = "player"
                                if (autoSwitchToRemote) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                    currentScreen = Screen.Remote
                                }
                                Toast.makeText(this@BrowserActivity, "Playlist sent to ${connection.serverName}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val headers = VideoDetector.mediaHeaders(video)
                            if (!video.originUrl.isNullOrEmpty() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                                headers["Referer"] = video.originUrl
                            }
                            val effectiveQuality = defaultVideoQuality.takeIf { it != "Auto" }
                            val cmd = createSingleVideoCommandJson(
                                PlayPayload(
                                    url = video.url,
                                    title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                    headers = headers ?: emptyMap(),
                                    content_type = video.contentType,
                                    detected_by = video.detectedBy,
                                    player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                    preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                    preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                    default_video_quality = effectiveQuality,
                                    max_bitrate_cap_mbps = maxBitrateCapMbps,
                                )
                            )
                            connectionViewModel.sendCommandAndRecord(cmd, "play", video.url, selectedTab?.content?.title ?: "Video from browser")
                            if (connectionViewModel.webSocketClient.send(cmd)) {
                                tvActiveContext = "player"
                                session?.let { tabManager.pauseMedia(it) }
                                if (autoSwitchToRemote) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                    currentScreen = Screen.Remote
                                }
                                Toast.makeText(this@BrowserActivity, "Playing on ${connection.serverName}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        return@launch
                    }

                    // 3. Fallback to Browse
                    val effectiveUrl = currentUrl
                    val cmd = com.playbridge.shared.protocol.createBrowserCommandJson(
                        effectiveUrl,
                        browserMode = sheetPlayerMode.takeIf { it != "tv" },
                        desktopMode = isDesktopMode.takeIf { it }
                    )
                    connectionViewModel.sendCommandAndRecord(cmd, "browser", effectiveUrl, "Browser Page")
                    if (autoSwitchToRemote) {
                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                        currentScreen = Screen.Remote
                    }
                    Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                }
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


            // Find in Page state
            var showFindBar by remember { mutableStateOf(false) }

            var pendingPopup by remember { mutableStateOf<PendingPopup?>(null) }

            // Clear finding when bar closes
            LaunchedEffect(showFindBar) {
                if (!showFindBar) {
                    tabManager.clearFind(session)
                }
            }

            // On (re)connect, ask the TV to re-broadcast its now-playing snapshot
            // (context + playlist + tracks + status) so the remote screen repopulates
            // after an app restart — these are otherwise only sent on change events.
            LaunchedEffect(Unit) {
                connectionViewModel.connectionState.collect { state ->
                    if (state is WebSocketClient.ConnectionState.Connected) {
                        connectionViewModel.webSocketClient.send(
                            com.playbridge.shared.protocol.createContextQueryJson()
                        )
                    }
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
                                        tvPlayback = null
                                        tvAudioTracks = emptyList()
                                        tvSubtitleTracks = emptyList()
                                        tvPlayerSettings = TvPlayerSettings()
                                        nowPlayingTvId = null
                                        nowPlayingSeason = null
                                    }
                                }
                                "playlist_status" -> {
                                    val itemsJson = json.optJSONArray("items")
                                    val episodes = buildList {
                                        if (itemsJson != null) {
                                            for (i in 0 until itemsJson.length()) {
                                                val o = itemsJson.optJSONObject(i) ?: continue
                                                add(
                                                    PlaylistEpisode(
                                                        index = o.optInt("index", i),
                                                        title = o.optString("title", "Item ${i + 1}")
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    tvPlaylistState = PlaylistUiState(
                                        currentIndex = json.optInt("currentIndex", 0),
                                        totalCount = json.optInt("totalCount", 0),
                                        items = episodes
                                    )
                                }
                                "status" -> {
                                    tvPlayback = TvPlaybackStatus(
                                        state = json.optString("state", "paused"),
                                        positionMs = json.optLong("position", 0L),
                                        durationMs = json.optLong("duration", 0L),
                                        title = json.optString("title", "").ifEmpty { null }
                                    )
                                }
                                "tracks" -> {
                                    fun parseTracks(arr: org.json.JSONArray?): List<MediaTrack> =
                                        buildList {
                                            if (arr != null) {
                                                for (i in 0 until arr.length()) {
                                                    val o = arr.optJSONObject(i) ?: continue
                                                    add(
                                                        MediaTrack(
                                                            id = o.optString("id"),
                                                            name = o.optString("name", "Track ${i + 1}"),
                                                            selected = o.optBoolean("selected", false)
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    tvAudioTracks = parseTracks(json.optJSONArray("audio"))
                                    tvSubtitleTracks = parseTracks(json.optJSONArray("subtitle"))
                                }
                                "player_settings" -> {
                                    tvPlayerSettings = TvPlayerSettings(
                                        speed = json.optDouble("speed", 1.0).toFloat(),
                                        scaling = json.optString("scaling", "Fit"),
                                        audioBoost = json.optBoolean("audioBoost", false),
                                        subtitleOffsetMs = json.optLong("subtitleOffsetMs", 0L),
                                        filter = json.optString("filter", "NONE"),
                                        engine = json.optString("engine", "")
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
                onFullScreenChange = { fullScreen, isPortrait ->
                    isFullscreenVideoPortrait = isPortrait
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

            PlayBridgeTheme {
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
                                            isEditing = isEditing,
                                            isSecure = isSecureConnection,
                                            onLogoClick = { currentScreen = Screen.Dashboard },
                                            onSecurityIconClick = { showSiteInfoSheet = true },
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
                                            onRefresh = { session.reload() },
                                            onStop = { session.stopLoading() },
                                            onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                {
                                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                    currentScreen = Screen.Remote
                                                }
                                            } else null
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
                                    },
                                    actions = {
                                        IconButton(onClick = {
                                            tabManager.createTab("about:blank", store)
                                            currentScreen = Screen.Browser
                                        }) {
                                            Icon(Icons.Default.Add, "New Tab")
                                        }
                                        
                                        var menuExpanded by remember { mutableStateOf(false) }
                                        val playingTabIds = tabManager.playingTabIds
                                        
                                        Box {
                                            IconButton(onClick = { menuExpanded = true }) {
                                                Icon(Icons.Default.MoreVert, "More options")
                                            }
                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Go to playing tab") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        playingTabIds.keys.firstOrNull()?.let {
                                                            tabManager.selectTab(it, store)
                                                            currentScreen = Screen.Browser
                                                        }
                                                    },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                                                    enabled = playingTabIds.isNotEmpty()
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Reopen Closed Tab") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        tabManager.reopenClosedTab(store)?.let {
                                                            currentScreen = Screen.Browser
                                                        }
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Restore, null) },
                                                    enabled = tabManager.canReopenClosedTab()
                                                )
                                            }
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
                            Screen.Dashboard -> {}
                        }
                    },
                        bottomBar = {
                            if (currentScreen == Screen.Browser && !isFullscreen) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .windowInsetsPadding(WindowInsets.navigationBars)
                                            .padding(horizontal = 16.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 1. Back Button
                                        IconButton(
                                            onClick = { session.goBack() },
                                            enabled = browserCanGoBack
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back",
                                                tint = if (browserCanGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            )
                                        }

                                        // 2. Forward Button
                                        IconButton(
                                            onClick = { session.goForward() },
                                            enabled = browserCanGoForward
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "Forward",
                                                tint = if (browserCanGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            )
                                        }

                                         val tabUrl = currentUrl
                                         val isPlayEnabled = tabUrl.isNotEmpty() && tabUrl != "about:blank"
                                         Box(
                                             contentAlignment = Alignment.Center,
                                             modifier = Modifier
                                                 .size(48.dp)
                                                 .clip(CircleShape)
                                                 .combinedClickable(
                                                     enabled = isPlayEnabled,
                                                     onClick = { showVideoSheet = true },
                                                     onLongClick = { performQuickCast() }
                                                 )
                                         ) {
                                             BadgedBox(
                                                 badge = {
                                                     if (isPlayEnabled && videoCount > 0) {
                                                         Badge(
                                                             containerColor = MaterialTheme.colorScheme.error,
                                                             contentColor = MaterialTheme.colorScheme.onError
                                                         ) {
                                                             Text(videoCount.toString())
                                                         }
                                                     }
                                                 }
                                             ) {
                                                 Icon(
                                                     imageVector = Icons.Default.PlayArrow,
                                                     contentDescription = "Play/Cast video",
                                                     tint = if (isPlayEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                                 )
                                             }
                                         }

                                        // 4. Tab button with tab count outline box
                                        IconButton(
                                            onClick = { currentScreen = Screen.Tabs }
                                        ) {
                                            val tabCount = browserState.tabs.size
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .border(
                                                        width = 2.dp,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        shape = RoundedCornerShape(5.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = tabCount.toString(),
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = if (tabCount >= 100) 8.sp else if (tabCount >= 10) 10.sp else 12.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }

                                        // 5. Hamburger menu button
                                        IconButton(
                                            onClick = { showMenuSheet = true }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Menu,
                                                contentDescription = "Menu"
                                            )
                                        }
                                    }
                                }
                            } else if ((currentScreen == Screen.Library || currentScreen == Screen.AddonSettings || (currentScreen == Screen.Settings && isSettingsFromLibrary)) && !isFullscreen) {
                                val navTab by libraryViewModel.selectedTab.collectAsState()
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 8.dp
                                ) {
                                    // 1. Home
                                    NavigationBarItem(
                                        selected = currentScreen == Screen.Library && navTab == 0,
                                        onClick = {
                                            if (currentScreen != Screen.Library) {
                                                currentScreen = Screen.Library
                                            }
                                            libraryViewModel.setSelectedTab(0)
                                        },
                                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                        label = { Text("Home") }
                                    )

                                    // 2. Discover
                                    NavigationBarItem(
                                        selected = currentScreen == Screen.Library && navTab == 1,
                                        onClick = {
                                            if (currentScreen != Screen.Library) {
                                                currentScreen = Screen.Library
                                            }
                                            libraryViewModel.setSelectedTab(1)
                                        },
                                        icon = { Icon(Icons.Default.Explore, contentDescription = "Discover") },
                                        label = { Text("Discover") }
                                    )

                                    // 3. Library
                                    NavigationBarItem(
                                        selected = currentScreen == Screen.Library && navTab == 2,
                                        onClick = {
                                            if (currentScreen != Screen.Library) {
                                                currentScreen = Screen.Library
                                            }
                                            libraryViewModel.setSelectedTab(2)
                                        },
                                        icon = { Icon(Icons.Default.Bookmark, contentDescription = "Library") },
                                        label = { Text("Library") }
                                    )

                                    // 4. Addons
                                    NavigationBarItem(
                                        selected = currentScreen == Screen.AddonSettings,
                                        onClick = {
                                            currentScreen = Screen.AddonSettings
                                        },
                                        icon = { Icon(Icons.Default.Extension, contentDescription = "Addons") },
                                        label = { Text("Addons") }
                                    )

                                    // 5. Settings
                                    NavigationBarItem(
                                        selected = currentScreen == Screen.Settings,
                                        onClick = {
                                            isSettingsFromLibrary = true
                                            currentScreen = Screen.Settings
                                        },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                        label = { Text("Settings") }
                                    )
                                }
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
                                                if (currentScreen == Screen.Browser) {
                                                    BrowserView(
                                                        session = session,
                                                        onLongPressLink = { url: String -> contextMenuUrl = url }
                                                    )
                                                }

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
                                            }
                                        }
                                        Screen.History -> {
                                            BackHandler { currentScreen = lastMainScreen }
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
                                                onBack = { currentScreen = lastMainScreen }
                                            )
                                        }
                                        Screen.CastHistory -> {
                                            BackHandler { currentScreen = lastMainScreen }
                                            val db = com.playbridge.sender.data.history.DatabaseProvider.getDatabase(androidx.compose.ui.platform.LocalContext.current)
                                            val commandHistoryFlow = remember { db.commandHistoryDao().getAll() }
                                            val commandHistory by commandHistoryFlow.collectAsState(initial = emptyList())
                                            CastHistoryScreen(
                                                historyItems = commandHistory,
                                                onMenuClick = { currentScreen = Screen.Dashboard },
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
                                                onBack = { currentScreen = lastMainScreen }
                                            )
                                        }
                                        Screen.Tabs -> {
                                            BackHandler { currentScreen = lastMainScreen }
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
                                                },
                                                onTabDuplicate = { tabId ->
                                                    tabManager.duplicateTab(tabId, store)
                                                },
                                                onTabBookmark = { tabId ->
                                                    val targetTab = store.state.tabs.find { it.id == tabId }
                                                    targetTab?.let { tab ->
                                                        val url = tab.content.url
                                                        if (url.isNotEmpty() && url != "about:blank") {
                                                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                                bookmarkDao.insert(
                                                                    com.playbridge.sender.data.history.BookmarkEntity(
                                                                        url = url,
                                                                        title = tab.content.title.ifEmpty { null },
                                                                        timestamp = System.currentTimeMillis()
                                                                    )
                                                                )
                                                                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                                                    Toast.makeText(this@BrowserActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        Screen.Extensions -> {
                                            BackHandler { currentScreen = lastMainScreen }
                                            ExtensionsScreen(
                                                session = session,
                                                onBack = { currentScreen = lastMainScreen },
                                                onAddExtension = {
                                                    tabManager.createTab("https://addons.mozilla.org/android/", store)
                                                    currentScreen = Screen.Browser
                                                }
                                            )
                                        }
                                        Screen.Connection -> {
                                            BackHandler { currentScreen = lastMainScreen }
                                            ConnectionScreen(
                                                viewModel = connectionViewModel,
                                                onMenuClick = { currentScreen = Screen.Dashboard },
                                                onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                    {
                                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                        currentScreen = Screen.Remote
                                                    }
                                                } else null
                                            )
                                        }
                                        Screen.Downloads -> {
                                            BackHandler { currentScreen = lastMainScreen }
                                            DownloadsScreen(
                                                onBack = { currentScreen = lastMainScreen }
                                            )
                                        }
                                        Screen.Settings -> {
                                            BackHandler {
                                                if (isSettingsFromLibrary) {
                                                    currentScreen = Screen.Library
                                                    libraryViewModel.setSelectedTab(0)
                                                } else {
                                                    currentScreen = lastMainScreen
                                                }
                                            }
                                            SettingsScreen(
                                                onBack = {
                                                    if (isSettingsFromLibrary) {
                                                        currentScreen = Screen.Library
                                                        libraryViewModel.setSelectedTab(0)
                                                    } else {
                                                        currentScreen = lastMainScreen
                                                    }
                                                },
                                                tvIp = if (connectionState is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) tvDevice?.ip else null,
                                                tvPort = if (connectionState is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) tvDevice?.port else null,
                                                showBack = !isSettingsFromLibrary,
                                                isFromLibrary = isSettingsFromLibrary
                                            )
                                        }
                                        Screen.Bookmarks -> {
                                            BackHandler { currentScreen = lastMainScreen }
                                            BookmarksScreen(
                                                bookmarkDao = bookmarkDao,
                                                onNavigate = { url ->
                                                    session.loadUrl(url)
                                                    currentScreen = Screen.Browser
                                                },
                                                onBack = { currentScreen = lastMainScreen }
                                            )
                                        }
                                        Screen.Remote -> {
                                            BackHandler {
                                                currentScreen = lastMainScreen
                                            }
                                            RemoteControlScreen(
                                                isMediaPlaying = tvActiveContext == "player",
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
                                                    {
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
                                                },
                                                onBack = {
                                                    currentScreen = lastMainScreen
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
                                            val selectedTabVal by libraryViewModel.selectedTab.collectAsState()
                                            BackHandler {
                                                if (selectedTabVal != 0) {
                                                    libraryViewModel.setSelectedTab(0)
                                                } else {
                                                    finish()
                                                }
                                            }
                                            LibraryScreen(
                                                viewModel = libraryViewModel,
                                                onMenuClick = { currentScreen = Screen.Dashboard },
                                                onRemoteClick = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                    {
                                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                        currentScreen = Screen.Remote
                                                    }
                                                } else null,
                                                onMovieClick = { movieId -> currentScreen = Screen.LibraryDetail(movieId.toString(), "movie") },
                                                onTvShowClick = { tvId -> currentScreen = Screen.LibraryDetail(tvId.toString(), "tv") },
                                                onAddonItemClick = { id, type, source -> currentScreen = Screen.LibraryDetail(id, type, source) }
                                            )
                                        }
                                        is Screen.LibraryDetail -> {
                                            val screen = targetScreen as Screen.LibraryDetail
                                            BackHandler { currentScreen = Screen.Library }
                                            val screenNumericId = screen.id.toIntOrNull()
                                            LibraryDetailScreen(
                                                id = screen.id,
                                                type = screen.type,
                                                forcedSource = screen.source,
                                                addonRepository = addonRepository,
                                                viewModel = libraryViewModel,
                                                tvName = tvDevice?.name,
                                                isTvConnected = connectionState is WebSocketClient.ConnectionState.Connected,
                                                onOpenRemote = if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                    {
                                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                        currentScreen = Screen.Remote
                                                    }
                                                } else null,
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
                                                    scope.launch {
                                                        // Ensure connected before sending
                                                        if (tvDevice != null && connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                                            runOnUiThread { Toast.makeText(this@BrowserActivity, "Connecting to TV...", Toast.LENGTH_SHORT).show() }
                                                            connectionViewModel.connect(tvDevice!!)
                                                            withTimeoutOrNull(8000) {
                                                                connectionViewModel.connectionState.first { it is WebSocketClient.ConnectionState.Connected }
                                                            }
                                                        }

                                                        if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                                            runOnUiThread { Toast.makeText(this@BrowserActivity, "Could not connect to TV", Toast.LENGTH_SHORT).show() }
                                                            return@launch
                                                        }

                                                        val cmd = createSingleVideoCommandJson(
                                                            payload.copy(
                                                                player_mode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
                                                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                                                            )
                                                        )
                                                        if (connectionViewModel.webSocketClient.send(cmd)) {
                                                            if (payload.content_type == "series") {
                                                                nowPlayingTvId = payload.visual_metadata?.tmdb_id?.toIntOrNull()
                                                                nowPlayingSeason = payload.visual_metadata?.season
                                                                nowPlayingEpisodeStart = payload.visual_metadata?.episode ?: 1
                                                            }
                                                            tvActiveContext = "player"
                                                            if (autoSwitchToRemote) {
                                                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                                currentScreen = Screen.Remote
                                                            }
                                                            Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
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
                                                        forcedVideos = listOf(mainVideo)
                                                        showVideoSheet = true
                                                    }
                                                },
                                                // Proxy path: the phone has already resolved a stream
                                                // and rewritten the URL through mediaflow-proxy. Send it
                                                // straight to the TV as a `play` command (bypassing
                                                // content-payload resolution on the TV).
                                                onSendStreamToTv = { url, title, headers, contentType ->
                                                    scope.launch {
                                                        // Ensure connected before sending
                                                        if (tvDevice != null && connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                                            runOnUiThread { Toast.makeText(this@BrowserActivity, "Connecting to TV...", Toast.LENGTH_SHORT).show() }
                                                            connectionViewModel.connect(tvDevice!!)
                                                            withTimeoutOrNull(8000) {
                                                                connectionViewModel.connectionState.first { it is WebSocketClient.ConnectionState.Connected }
                                                            }
                                                        }

                                                        if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                                            runOnUiThread { Toast.makeText(this@BrowserActivity, "Could not connect to TV", Toast.LENGTH_SHORT).show() }
                                                            return@launch
                                                        }

                                                        val cmd = createSingleVideoCommandJson(
                                                            PlayPayload(
                                                                url = url,
                                                                title = title,
                                                                headers = headers ?: emptyMap(),
                                                                content_type = contentType,
                                                                detected_by = "library",
                                                                player_mode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
                                                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                                                            )
                                                        )
                                                        if (connectionViewModel.webSocketClient.send(cmd)) {
                                                            tvActiveContext = "player"
                                                            if (autoSwitchToRemote) {
                                                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                                currentScreen = Screen.Remote
                                                            }
                                                            Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onPlayPlaylistToTv = { playlist ->
                                                    scope.launch {
                                                        // Ensure connected before sending
                                                        if (tvDevice != null && connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                                            runOnUiThread { Toast.makeText(this@BrowserActivity, "Connecting to TV...", Toast.LENGTH_SHORT).show() }
                                                            connectionViewModel.connect(tvDevice!!)
                                                            withTimeoutOrNull(8000) {
                                                                connectionViewModel.connectionState.first { it is WebSocketClient.ConnectionState.Connected }
                                                            }
                                                        }

                                                        if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                                                            runOnUiThread { Toast.makeText(this@BrowserActivity, "Could not connect to TV", Toast.LENGTH_SHORT).show() }
                                                            return@launch
                                                        }

                                                        val playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
                                                        val itemsWithPrefs = playlist.items.map {
                                                            it.copy(
                                                                player_mode = playerMode,
                                                                preferred_audio_language = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                                                preferred_subtitle_language = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                                                default_video_quality = defaultVideoQuality.takeIf { q -> q != "Auto" },
                                                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                                                            )
                                                        }
                                                        val finalPlaylist = playlist.copy(items = itemsWithPrefs)
                                                        if (connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createPlaylistCommandJson(finalPlaylist))) {
                                                            nowPlayingTvId = screenNumericId
                                                            nowPlayingSeason = playlist.items.getOrNull(playlist.start_index)?.visual_metadata?.season ?: 1
                                                            nowPlayingEpisodeStart = playlist.items.getOrNull(playlist.start_index)?.visual_metadata?.episode ?: 1

                                                            tvActiveContext = "player"
                                                            if (autoSwitchToRemote) {
                                                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                                currentScreen = Screen.Remote
                                                            }
                                                            Toast.makeText(this@BrowserActivity, "Playlist sent to TV", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                },
                                                onQueueAdd = { item ->
                                                    val playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }
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
                                            BackHandler {
                                                currentScreen = Screen.Library
                                                libraryViewModel.setSelectedTab(0)
                                            }
                                            AddonSettingsScreen(
                                                addonRepository = addonRepository,
                                                installedAddons = installedAddons,
                                                onBack = {
                                                    currentScreen = Screen.Library
                                                    libraryViewModel.setSelectedTab(0)
                                                },
                                                showBack = false,
                                                onOpenUrl = { url ->
                                                    tabManager.createTab(url, store)
                                                    currentScreen = Screen.Browser
                                                },
                                                onRefreshCatalogs = { libraryViewModel.refreshCatalogsNow() },
                                                onClearCatalogCache = { libraryViewModel.clearCatalogCache() },
                                                onCatalogsChanged = { libraryViewModel.refreshCatalogsNow() }
                                            )
                                        }
                                        Screen.Dashboard -> {
                                            BackHandler { currentScreen = lastMainScreen }
                                            val isConnected = connectionState is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected
                                            DashboardScreen(
                                                currentScreen = lastMainScreen,
                                                isConnected = isConnected,
                                                isSecure = (connectionState as? WebSocketClient.ConnectionState.Connected)?.secure == true,
                                                connectedDeviceName = tvDevice?.name,
                                                onNavigate = { screen ->
                                                    currentScreen = screen
                                                }
                                            )
                                        }
                                        Screen.DebridLibrary -> {
                                            BackHandler { finish() }
                                            DebridLibraryScreen(
                                                onMenuClick = { currentScreen = Screen.Dashboard },
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
                        isTvPlaying = tvActiveContext == "player",
                        onContentClick = { payload ->
                             val cmd = createSingleVideoCommandJson(
                                 payload.copy(
                                     player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                     preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                     preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                     default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                     max_bitrate_cap_mbps = maxBitrateCapMbps,
                                 )
                             )
                            if (connectionViewModel.webSocketClient.send(cmd)) {
                                if (payload.content_type == "series") {
                                    nowPlayingTvId = payload.visual_metadata?.tmdb_id?.toIntOrNull()
                                    nowPlayingSeason = payload.visual_metadata?.season
                                    nowPlayingEpisodeStart = payload.visual_metadata?.episode ?: 1
                                }
                                tvActiveContext = "player"
                                if (autoSwitchToRemote) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                    currentScreen = Screen.Remote
                                }
                                Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                            }
                            showVideoSheet = false
                            pendingContentPayload = null
                        },
                        onQueueContent = { payload ->
                            val item = payload.copy(
                                player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                            )
                            if (connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createQueueAddCommandJson(item))) {
                                Toast.makeText(this@BrowserActivity, "Added to queue", Toast.LENGTH_SHORT).show()
                            }
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
                                            payload = playbridge.PlaylistPayload(items = video.playlistPayload!!)
                                        )
                                        if (connectionViewModel.webSocketClient.send(cmd)) {
                                            tvActiveContext = "player"
                                            if (autoSwitchToRemote) {
                                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                currentScreen = Screen.Remote
                                            }
                                            Toast.makeText(this@BrowserActivity, "Playlist sent to ${state.serverName}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val headers = VideoDetector.mediaHeaders(video)
                                        if (!video.originUrl.isNullOrEmpty() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                                            headers["Referer"] = video.originUrl
                                        }
                                        val effectiveQuality = defaultVideoQuality.takeIf { it != "Auto" }
                                        val commandJson = createSingleVideoCommandJson(
                                            PlayPayload(
                                                url = video.url,
                                                title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                                headers = headers ?: emptyMap(),
                                                content_type = video.contentType,
                                                subtitles = subtitles ?: emptyList(),
                                                detected_by = video.detectedBy,
                                                player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                                default_video_quality = effectiveQuality,
                                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                                            )
                                        )
                                        connectionViewModel.sendCommandAndRecord(commandJson, "play", video.url, selectedTab?.content?.title ?: "Video from browser")
                                        if (connectionViewModel.webSocketClient.send(commandJson)) {
                                            tvActiveContext = "player"
                                            session?.let { tabManager.pauseMedia(it) }
                                            if (autoSwitchToRemote) {
                                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                currentScreen = Screen.Remote
                                            }
                                            Toast.makeText(this@BrowserActivity, "Playing on ${state.serverName}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                else -> { Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show() }
                            }
                            showVideoSheet = false
                            forcePlaylistSheet = null
                        },
                        onQueueVideo = { video, subtitles ->
                            when (connectionState) {
                                is WebSocketClient.ConnectionState.Connected -> {
                                    // queue_add takes one item at a time; a multi-item HLS bundle
                                    // is appended item-by-item.
                                    val items: List<PlayPayload> = video.playlistPayload ?: run {
                                        val headers = VideoDetector.mediaHeaders(video)
                                        if (!video.originUrl.isNullOrEmpty() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                                            headers["Referer"] = video.originUrl
                                        }
                                        listOf(
                                            PlayPayload(
                                                url = video.url,
                                                title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                                headers = headers ?: emptyMap(),
                                                content_type = video.contentType,
                                                subtitles = subtitles ?: emptyList(),
                                                detected_by = video.detectedBy,
                                                player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                                            )
                                        )
                                    }
                                    var sent = false
                                    items.forEach { item ->
                                        if (connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createQueueAddCommandJson(item))) sent = true
                                    }
                                    if (sent) Toast.makeText(this@BrowserActivity, "Added to queue", Toast.LENGTH_SHORT).show()
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

                            // Automatically open remote control after browsing
                            if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                if (autoSwitchToRemote) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                    currentScreen = Screen.Remote
                                }
                            }
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

                if (showMenuSheet) {
                    PlayBridgeTheme {
                        ModalBottomSheet(
                            onDismissRequest = { showMenuSheet = false },
                            sheetState = sheetState,
                            dragHandle = { BottomSheetDefaults.DragHandle() },
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)
                        ) {
                            // Row 1: Bookmarks, History, Downloads, Add Bookmark, Find in Page
                            Row(modifier = Modifier.fillMaxWidth()) {
                                MenuGridItem(
                                    icon = Icons.Default.Bookmarks,
                                    label = "Bookmarks",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showMenuSheet = false
                                            currentScreen = Screen.Bookmarks
                                        }
                                    }
                                )
                                MenuGridItem(
                                    icon = Icons.Default.History,
                                    label = "History",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showMenuSheet = false
                                            currentScreen = Screen.History
                                        }
                                    }
                                )
                                MenuGridItem(
                                    icon = Icons.Default.Download,
                                    label = "Downloads",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showMenuSheet = false
                                            currentScreen = Screen.Downloads
                                        }
                                    }
                                )
                                MenuGridItem(
                                    icon = Icons.Default.Star,
                                    label = "Add Bookmark",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showMenuSheet = false
                                            handleBookmarkClick()
                                        }
                                    }
                                )
                                MenuGridItem(
                                    icon = Icons.Default.Search,
                                    label = "Find in Page",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showMenuSheet = false
                                            showFindBar = true
                                        }
                                    }
                                )
                            }
                            // Row 2: Extensions, Settings, Desktop Site, Video Detect
                            Row(modifier = Modifier.fillMaxWidth()) {
                                MenuGridItem(
                                    icon = Icons.Default.Extension,
                                    label = "Extensions",
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showMenuSheet = false
                                            currentScreen = Screen.Extensions
                                        }
                                    }
                                )
                                MenuGridItem(
                                    icon = Icons.Default.Settings,
                                    label = "Settings",
                                    selected = currentScreen == Screen.Settings,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            showMenuSheet = false
                                            isSettingsFromLibrary = false
                                            currentScreen = Screen.Settings
                                        }
                                    }
                                )
                                MenuGridItem(
                                    icon = Icons.Default.Devices,
                                    label = "Desktop Site",
                                    selected = isDesktopMode,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        isDesktopMode = !isDesktopMode
                                    }
                                )
                                MenuGridItem(
                                    icon = Icons.Default.PlayCircle,
                                    label = "Video Detect",
                                    selected = detectVideosEnabled,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        detectVideosEnabled = !detectVideosEnabled
                                    }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
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
                            val videos = links.map { link -> playbridge.PlayPayload(url = link.downloadUrl, title = link.filename, player_mode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" }, preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() }, preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() }, default_video_quality = defaultVideoQuality.takeIf { it != "Auto" }, max_bitrate_cap_mbps = maxBitrateCapMbps) }
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

    @Composable
    private fun MenuSheetItem(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        selected: Boolean = false,
        tint: androidx.compose.ui.graphics.Color? = null,
        labelColor: androidx.compose.ui.graphics.Color? = null,
        onClick: () -> Unit
    ) {
        Surface(
            onClick = onClick,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint ?: if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = labelColor ?: if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable
    private fun MenuGridItem(
        icon: ImageVector,
        label: String,
        selected: Boolean = false,
        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        labelColor: Color = MaterialTheme.colorScheme.onSurface,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer 
                        else Color.Transparent
                    )
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) MaterialTheme.colorScheme.primary else tint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else if (labelColor == Color.Green) labelColor else labelColor.copy(alpha = 0.7f),
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
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
    data class LibraryDetail(val id: String, val type: String, val source: String? = null) : Screen()
    object Dashboard : Screen()
}
