package com.playbridge.sender.browser
import com.playbridge.sender.library.*
import com.playbridge.sender.cast.*

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.playbridge.sender.history.BookmarksScreen
import com.playbridge.sender.history.CastHistoryScreen
import com.playbridge.sender.history.HistoryScreen
import com.playbridge.sender.settings.SettingsScreen
import com.playbridge.sender.downloads.DownloadConfirmDialog
import com.playbridge.sender.downloads.DownloadUtils
import com.playbridge.sender.downloads.DownloadsScreen
import com.playbridge.sender.downloads.PendingDownload
import com.playbridge.sender.downloads.PendingPopup
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.ConnectionScreen
import com.playbridge.sender.ui.DashboardScreen
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
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.library.LibraryViewModel
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

    private val connectionViewModel: ConnectionViewModel by viewModel()
    private val connectionCoordinator: ConnectionCoordinator by inject()
    private val addonRepository: com.playbridge.sender.data.library.AddonRepository by inject()
    private val browserViewModel: com.playbridge.sender.browser.BrowserViewModel by viewModel()
    private val tabManager = TabManager()

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
        val selectedId = Components.store.state.selectedTabId
        val tabs = Components.store.state.tabs
        val allStates = tabManager.captureAllStates()
        browserViewModel.saveTabs(tabs, selectedId, allStates, tabManager.parentIds)
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

        val libraryViewModel: LibraryViewModel by viewModel()
        val settingsRepository: SettingsRepository by inject()

        // Restore tabs
        // Track whether tab restoration is complete to avoid blank screen
        val storeTabsAtStart = Components.store.state.tabs.size
        val tabsRestoredOrReady = mutableStateOf(Components.store.state.tabs.isNotEmpty())
        Log.d("PB_STARTUP", "onCreate: store has $storeTabsAtStart tabs at start, tabsRestoredOrReady=${tabsRestoredOrReady.value}")

        // One-time legacy SharedPreferences migration block
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val legacyPrefs = getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
            if (legacyPrefs.getBoolean("needs_datastore_migration", true)) {
                settingsRepository.setAutoSwitchToRemote(legacyPrefs.getBoolean("auto_switch_to_remote", true))
                settingsRepository.setMaxAliveTabs(legacyPrefs.getInt("max_alive_tabs", 5))
                
                val audioLang = legacyPrefs.getString("preferred_audio_language", null) 
                    ?: legacyPrefs.getString("preferred_audio_lang", "") ?: ""
                settingsRepository.setPreferredAudioLang(audioLang)
                
                val subLang = legacyPrefs.getString("preferred_subtitle_language", null)
                    ?: legacyPrefs.getString("preferred_subtitle_lang", "") ?: ""
                settingsRepository.setPreferredSubtitleLang(subLang)
                
                settingsRepository.setDefaultVideoQuality(legacyPrefs.getString("default_video_quality", "Auto") ?: "Auto")
                
                val maxBitrateStr = legacyPrefs.getString("auto_stream_max_mbps", null)
                val maxBitrateDouble = maxBitrateStr?.toDoubleOrNull() ?: legacyPrefs.getInt("max_bitrate_cap_mbps", 0).toDouble()
                settingsRepository.setMaxBitrateCapMbps(maxBitrateDouble)
                
                settingsRepository.setTvPlayerMode(legacyPrefs.getString("tv_player_mode", "tv") ?: "tv")
                settingsRepository.setDetectVideos(legacyPrefs.getBoolean("detect_videos", true))
                settingsRepository.setBlockPopups(legacyPrefs.getBoolean("block_popups", true))
                
                legacyPrefs.edit().putBoolean("needs_datastore_migration", false).apply()
                Log.d("BrowserActivity", "Migrated legacy SharedPreferences to Jetpack DataStore successfully.")
            }
        }

        browserViewModel.restoreTabs(tabManager, Components.store) {
            tabsRestoredOrReady.value = true
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
            val activeDlnaTarget by connectionViewModel.activeDlnaTarget.collectAsState()
            val scope = rememberCoroutineScope()

            // Suggestions State
            var isEditing by remember { mutableStateOf(false) }
            var editUrl by remember { mutableStateOf("") }
            val suggestions by browserViewModel.suggestions.collectAsState()
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

            var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }

            // Hoisted Tabs screen states
            var isTabsSearchVisible by rememberSaveable { mutableStateOf(false) }
            var isTabsMultiSelectMode by rememberSaveable { mutableStateOf(false) }
            var showTabsCloseAllConfirm by remember { mutableStateOf(false) }

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
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text(if (isTabsSearchVisible) "Hide Search" else "Search Tabs") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isTabsSearchVisible = !isTabsSearchVisible
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Search, null) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Select Tabs") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isTabsMultiSelectMode = true
                                                    },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text("Close All Tabs", color = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showTabsCloseAllConfirm = true
                                                    },
                                                    leadingIcon = { 
                                                        Icon(
                                                            Icons.Default.Delete, 
                                                            null,
                                                            tint = MaterialTheme.colorScheme.error
                                                        ) 
                                                    }
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
                                                  browserViewModel.addBookmark(url, tab.content.title)
                                                  Toast.makeText(this@BrowserActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
                                              }
                                         }
                                     },
                                     isSearchVisibleExternal = isTabsSearchVisible,
                                     onSearchVisibleChangeExternal = { isTabsSearchVisible = it },
                                     isMultiSelectModeExternal = isTabsMultiSelectMode,
                                     onMultiSelectModeChangeExternal = { isTabsMultiSelectMode = it },
                                     showCloseAllConfirmExternal = showTabsCloseAllConfirm,
                                     onCloseAllConfirmChangeExternal = { showTabsCloseAllConfirm = it }
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

            val composeScope = rememberCoroutineScope()
            // User preferences via SettingsRepository
            val autoSwitchToRemote by settingsRepository.autoSwitchToRemote.collectAsState(initial = true)
            val maxAliveTabs by settingsRepository.maxAliveTabs.collectAsState(initial = 5)

            LaunchedEffect(maxAliveTabs) {
                tabManager.maxAliveSessions = maxAliveTabs
                Log.d("TabManager", "Updated maxAliveSessions to $maxAliveTabs from DataStore")
            }
            
            val prefs = remember { getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
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

            val preferredAudioLang by settingsRepository.preferredAudioLang.collectAsState(initial = "")
            val preferredSubLang by settingsRepository.preferredSubtitleLang.collectAsState(initial = "")
            val defaultVideoQuality by settingsRepository.defaultVideoQuality.collectAsState(initial = "Auto")
            val maxBitrateCapMbpsFlow by settingsRepository.maxBitrateCapMbps.collectAsState(initial = 0.0)
            val maxBitrateCapMbps = maxBitrateCapMbpsFlow.takeIf { it > 0.0 }
            val tvPlayerMode by settingsRepository.tvPlayerMode.collectAsState(initial = "tv")

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

                        val currentMode = tvPlayerMode.takeIf { it != "tv" }
                        
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

            val detectVideosEnabled by settingsRepository.detectVideos.collectAsState(initial = true)
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

            // FAB Drag state
            var fabOffsetX by remember { mutableFloatStateOf(0f) }
            var fabOffsetY by remember { mutableFloatStateOf(0f) }

            // Back press handling
            var backPressedTime by remember { mutableLongStateOf(0L) }

            // Magnet parsing state
            var interceptedMagnet by remember { mutableStateOf<String?>(null) }
            var interceptedTorrentBytes by remember { mutableStateOf<ByteArray?>(null) }

            // Stremio addon install state (set when a stremio:// link is clicked in the browser)
            var pendingStremioAddon by remember { mutableStateOf<String?>(null) }
            var isInstallingStremioAddon by remember { mutableStateOf(false) }

            // Video detection state — per-tab
            var showVideoSheet by remember { mutableStateOf(false) }
            val sheetPlayerMode = tvPlayerMode
            var forcePlaylistSheet by remember { mutableStateOf<DetectedVideo?>(null) }
            var forcedVideos by remember { mutableStateOf<List<DetectedVideo>?>(null) }
            var castSheetInitialMode by remember { mutableStateOf("play") }
            var castSheetBrowseOverride by remember { mutableStateOf<String?>(null) }
            var pendingContentPayload by remember { mutableStateOf<playbridge.PlayPayload?>(null) }

            val tvActiveContext by connectionCoordinator.tvActiveContext.collectAsState()
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
                    // DLNA: a third-party renderer is the active target — cast via the proxy, skip WS.
                    val dlnaTarget = connectionViewModel.activeDlnaTarget.value
                    if (dlnaTarget != null) {
                        val video = detectedVideos.filter { !it.isSubtitle }
                            .sortedWith(
                                compareByDescending<DetectedVideo> { v ->
                                    val hasMaster = v.url.contains("master", ignoreCase = true)
                                    val base = when {
                                        v.hlsPlaylist?.videoQualities?.isNotEmpty() == true -> 5
                                        v.isPlayable == true && (v.url.contains(".m3u8", ignoreCase = true) || v.url.contains(".mpd", ignoreCase = true)) -> 4
                                        v.isPlayable == false -> 1
                                        else -> 2
                                    }
                                    if (hasMaster) base + 1 else base
                                }.thenByDescending { it.timestamp }
                            )
                            .firstOrNull()
                        if (video == null) {
                            Toast.makeText(this@BrowserActivity, "No video detected to cast", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val headers = VideoDetector.mediaHeaders(video)
                        if (!video.originUrl.isNullOrEmpty() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                            headers["Referer"] = video.originUrl
                        }
                        connectionViewModel.playOnDlna(
                            com.playbridge.sender.cast.MediaItem(
                                url = video.url,
                                headers = headers,
                                mimeType = video.contentType,
                                title = video.title ?: selectedTab?.content?.title,
                            )
                        )
                        Toast.makeText(this@BrowserActivity, "Casting to ${dlnaTarget.name}", Toast.LENGTH_SHORT).show()
                        if (autoSwitchToRemote) currentScreen = Screen.Remote
                        return@launch
                    }

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
                            connectionCoordinator.tvActiveContext.value = "player"
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
                                connectionCoordinator.tvActiveContext.value = "player"
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
                                connectionCoordinator.tvActiveContext.value = "player"
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

            // WebSocket messages and remote states are now handled dynamically by ConnectionCoordinator

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
                // Don't auto-reconnect the native receiver when a DLNA target is active.
                if (showVideoSheet && connectionState is WebSocketClient.ConnectionState.Disconnected &&
                    connectionViewModel.activeDlnaTarget.value == null) {
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
                onStremioAddonDetected = { uri ->
                    pendingStremioAddon = uri
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
                val title = selectedTab?.content?.title
                val url = currentUrl
                if (url != "about:blank") {
                    browserViewModel.addBookmark(url, title ?: "")
                    Toast.makeText(this@BrowserActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
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
                                                    browserViewModel.setEditUrl(currentUrl)
                                                }
                                            },
                                            onUrlChange = { url ->
                                                editUrl = url
                                                browserViewModel.setEditUrl(url)
                                                urlBarTapped = false
                                            },
                                            onNavigate = { url ->
                                                session.loadUrl(url)
                                                isEditing = false
                                            },
                                            onMagnetDetected = { magnet ->
                                                interceptedMagnet = magnet
                                                isEditing = false
                                            },
                                            onRefresh = { session.reload() },
                                            onStop = { session.stopLoading() },
                                            onRemoteClick = when {
                                                connectionState is WebSocketClient.ConnectionState.Connected -> {
                                                    {
                                                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                                        currentScreen = Screen.Remote
                                                    }
                                                }
                                                activeDlnaTarget != null -> {
                                                    { currentScreen = Screen.Remote }
                                                }
                                                else -> null
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
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text(if (isTabsSearchVisible) "Hide Search" else "Search Tabs") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isTabsSearchVisible = !isTabsSearchVisible
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Search, null) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Select Tabs") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isTabsMultiSelectMode = true
                                                    },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text("Close All Tabs", color = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showTabsCloseAllConfirm = true
                                                    },
                                                    leadingIcon = { 
                                                        Icon(
                                                            Icons.Default.Delete, 
                                                            null,
                                                            tint = MaterialTheme.colorScheme.error
                                                        ) 
                                                    }
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
                            }
                        }
                    ) { innerPadding ->
                    // content
                    AppNavHost(
                        currentScreen = currentScreen,
                        onScreenChange = { currentScreen = it },
                        lastMainScreen = lastMainScreen,
                        onLastMainScreenChange = { lastMainScreen = it },
                        innerPadding = innerPadding,
                        session = session,
                        onMagnetDetected = { interceptedMagnet = it },
                        sessions = tabManager.sessions,
                        tabManager = tabManager,
                        store = store,
                        browserCanGoBack = browserCanGoBack,
                        isEditing = isEditing,
                        onIsEditingChange = { isEditing = it },
                        isFullscreen = isFullscreen,
                        onIsFullscreenChange = { isFullscreen = it },
                        backPressedTime = backPressedTime,
                        onBackPressedTimeChange = { backPressedTime = it },
                        onFinishActivity = { finish() },
                        showVideoSheet = showVideoSheet,
                        onShowVideoSheetChange = { showVideoSheet = it },
                        forcedVideos = forcedVideos,
                        onForcedVideosChange = { forcedVideos = it },
                        castSheetInitialMode = castSheetInitialMode,
                        onCastSheetInitialModeChange = { castSheetInitialMode = it },
                        castSheetBrowseOverride = castSheetBrowseOverride,
                        onCastSheetBrowseOverrideChange = { castSheetBrowseOverride = it },
                        scope = scope,
                        showFindBar = showFindBar,
                        onShowFindBarChange = { showFindBar = it },
                        isDesktopMode = isDesktopMode,
                        onIsDesktopModeChange = { isDesktopMode = it },
                        detectVideosEnabled = detectVideosEnabled,
                        onDetectVideosEnabledChange = { composeScope.launch { settingsRepository.setDetectVideos(it) } },
                        currentUrl = currentUrl,
                        onCurrentUrlChange = { currentUrl = it },
                        urlBarTapped = urlBarTapped,
                        onUrlBarTappedChange = { urlBarTapped = it },
                        urlPanelClipboard = urlPanelClipboard,
                        onUrlPanelClipboardChange = { urlPanelClipboard = it },
                        contextMenuUrl = contextMenuUrl,
                        onContextMenuUrlChange = { contextMenuUrl = it },
                        suggestions = suggestions,
                        isSettingsFromLibrary = isSettingsFromLibrary,
                        onIsSettingsFromLibraryChange = { isSettingsFromLibrary = it },
                        onHandleBookmarkClick = { handleBookmarkClick() },
                        browserViewContent = { s, onLongPress ->
                            BrowserView(session = s, onLongPressLink = onLongPress)
                        },
                        isTabsSearchVisible = isTabsSearchVisible,
                        onTabsSearchVisibleChange = { isTabsSearchVisible = it },
                        isTabsMultiSelectMode = isTabsMultiSelectMode,
                        onTabsMultiSelectModeChange = { isTabsMultiSelectMode = it },
                        showTabsCloseAllConfirm = showTabsCloseAllConfirm,
                        onTabsCloseAllConfirmChange = { showTabsCloseAllConfirm = it }
                    )

                SheetOverlayContainer(
                    // Hamburger Menu Sheet States
                    showMenuSheet = showMenuSheet,
                    onMenuDismiss = { showMenuSheet = false },
                    menuSheetState = sheetState,
                    currentScreen = currentScreen,
                    isDesktopMode = isDesktopMode,
                    detectVideosEnabled = detectVideosEnabled,
                    onBookmarksClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.Bookmarks
                        }
                    },
                    onHistoryClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.History
                        }
                    },
                    onDownloadsClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.Downloads
                        }
                    },
                    onAddBookmarkClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            handleBookmarkClick()
                        }
                    },
                    onFindInPageClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            showFindBar = true
                        }
                    },
                    onExtensionsClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.Extensions
                        }
                    },
                    onSettingsClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.Settings
                        }
                    },
                    onToggleDesktopMode = { isDesktopMode = !isDesktopMode },
                    onToggleVideoDetect = { composeScope.launch { settingsRepository.setDetectVideos(!detectVideosEnabled) } },

                    // Site Info Sheet States
                    showSiteInfoSheet = showSiteInfoSheet,
                    onSiteInfoDismiss = { showSiteInfoSheet = false },
                    siteSecurityInfo = siteSecurityInfo,
                    isSecureConnection = isSecureConnection,
                    currentUrl = currentUrl,

                    // Cast Sheet States
                    showVideoSheet = showVideoSheet,
                    detectedVideos = detectedVideos,
                    pendingContentPayload = pendingContentPayload,
                    isTvPlaying = tvActiveContext == "player",
                    onDismissVideoSheet = {
                        showVideoSheet = false
                        forcePlaylistSheet = null
                        forcedVideos = null
                        castSheetInitialMode = "play"
                        castSheetBrowseOverride = null
                        pendingContentPayload = null
                    },
                    onVideoClick = onVideoClick@ { video, subs ->
                         // DLNA: a third-party renderer is the active target — cast via the proxy.
                         val dlnaTarget = connectionViewModel.activeDlnaTarget.value
                         if (dlnaTarget != null) {
                             val dlnaHeaders = com.playbridge.sender.cast.VideoDetector.mediaHeaders(video)
                             if (!video.originUrl.isNullOrEmpty() && dlnaHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
                                 dlnaHeaders["Referer"] = video.originUrl
                             }
                             connectionViewModel.playOnDlna(
                                 com.playbridge.sender.cast.MediaItem(
                                     url = video.url,
                                     headers = dlnaHeaders,
                                     mimeType = video.contentType,
                                     title = video.title ?: selectedTab?.content?.title,
                                 )
                             )
                             Toast.makeText(this@BrowserActivity, "Casting to ${dlnaTarget.name}", Toast.LENGTH_SHORT).show()
                             showVideoSheet = false
                             forcePlaylistSheet = null
                             if (autoSwitchToRemote) currentScreen = Screen.Remote
                             return@onVideoClick
                         }
                         // A bundle (e.g. "Play All" from the debrid screen) carries its real items in
                         // playlistPayload with a "playlist://…" sentinel URL; send it as a playlist,
                         // not as a single video (otherwise the TV tries to play the sentinel URL).
                         val cmd = if (video.playlistPayload != null) {
                             val items = video.playlistPayload!!.map {
                                 it.copy(
                                     player_mode = sheetPlayerMode.takeIf { m -> m != "tv" },
                                     preferred_audio_language = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                     preferred_subtitle_language = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                     default_video_quality = defaultVideoQuality.takeIf { q -> q != "Auto" },
                                     max_bitrate_cap_mbps = maxBitrateCapMbps,
                                 )
                             }
                             com.playbridge.shared.protocol.createPlaylistCommandJson(
                                 playbridge.PlaylistPayload(items = items)
                             )
                         } else {
                             val headers = com.playbridge.sender.cast.VideoDetector.mediaHeaders(video)
                             if (!video.originUrl.isNullOrEmpty() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                                 headers["Referer"] = video.originUrl
                             }
                             val effectiveQuality = defaultVideoQuality.takeIf { it != "Auto" }
                             createSingleVideoCommandJson(
                                 PlayPayload(
                                     url = video.url,
                                     title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                     headers = headers ?: emptyMap(),
                                     content_type = video.contentType,
                                     detected_by = video.detectedBy,
                                     subtitles = subs.orEmpty(),
                                     player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                     preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                     preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                     default_video_quality = effectiveQuality,
                                     max_bitrate_cap_mbps = maxBitrateCapMbps,
                                 )
                             )
                         }
                         val sent = when (connectionState) {
                             is WebSocketClient.ConnectionState.Connected -> {
                                 val ok = connectionViewModel.webSocketClient.send(cmd)
                                 if (ok) {
                                     connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                     if (video.playlistPayload == null) browserViewModel.logHistory(video.url, video.title)
                                 }
                                 ok
                             }
                             else -> false
                         }
                         if (sent) Toast.makeText(this@BrowserActivity, "Play command sent to TV", Toast.LENGTH_SHORT).show()
                         showVideoSheet = false
                         forcePlaylistSheet = null
                    },
                    onQueueVideo = { video, subtitles ->
                        when (connectionState) {
                            is WebSocketClient.ConnectionState.Connected -> {
                                val items: List<PlayPayload> = video.playlistPayload ?: run {
                                    val headers = com.playbridge.sender.cast.VideoDetector.mediaHeaders(video)
                                    if (!video.originUrl.isNullOrEmpty() && headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                                        headers["Referer"] = video.originUrl
                                    }
                                    listOf(
                                        PlayPayload(
                                            url = video.url,
                                            title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                            headers = headers ?: emptyMap(),
                                            content_type = video.contentType,
                                            detected_by = video.detectedBy,
                                            subtitles = subtitles.orEmpty(),
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
                    onDownloadVideo = { video ->
                        DownloadUtils.enqueueDownload(this@BrowserActivity, video.url, null, video.contentType, video.headers?.get("User-Agent"), video.headers?.get("Cookie"), video.headers?.get("Referer") ?: video.originUrl, pageTitle = selectedTab?.content?.title)
                    },
                    onClearVideos = { com.playbridge.sender.cast.VideoDetector.clearTab(selectedTabId ?: "") },
                    playerMode = sheetPlayerMode,
                    onPlayerModeChange = { mode ->
                        composeScope.launch { settingsRepository.setTvPlayerMode(mode) }
                    },
                    availableTvDevices = remember(discoveredDevices, history, activeDlnaTarget) {
                        (history + discoveredDevices + listOfNotNull(activeDlnaTarget))
                            .distinctBy { it.uuid.ifEmpty { "${it.ip}:${it.port}" } }
                    },
                    // When a DLNA renderer is the active target, show it as the destination.
                    selectedTvDevice = activeDlnaTarget ?: tvDevice,
                    onTvChange = { device ->
                        if (device.isDlna) connectionViewModel.selectDlnaTarget(device)
                        else connectionViewModel.connect(device)
                    },
                    tvConnectionState = when {
                        activeDlnaTarget != null -> true
                        connectionState is WebSocketClient.ConnectionState.Connected -> true
                        connectionState is WebSocketClient.ConnectionState.Error -> false
                        else -> null
                    },
                    browseUrl = castSheetBrowseOverride ?: currentUrl,
                    onBrowseClick = { selectedMode, desktopMode ->
                        val effectiveUrl = castSheetBrowseOverride ?: currentUrl
                        val cmd = com.playbridge.shared.protocol.createBrowserCommandJson(effectiveUrl, browserMode = selectedMode.takeIf { it != "tv" }, desktopMode = desktopMode.takeIf { it })
                        connectionViewModel.sendCommandAndRecord(cmd, "browser", effectiveUrl, "Browser Page")
                        Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                        showVideoSheet = false
                        forcePlaylistSheet = null
                        castSheetInitialMode = "play"
                        castSheetBrowseOverride = null

                        if (connectionState is WebSocketClient.ConnectionState.Connected) {
                            if (autoSwitchToRemote) {
                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                currentScreen = Screen.Remote
                            }
                        }
                    },
                    onOpenNewTab = { url ->
                        tabManager.createTab(url, store)
                        showVideoSheet = false
                        castSheetInitialMode = "play"
                        castSheetBrowseOverride = null
                        currentScreen = Screen.Browser
                    },
                    initialMode = castSheetInitialMode,
                    mediaflowProxyUrl = mediaflowProxyUrl ?: "",
                    mediaflowProxyPassword = mediaflowProxyPassword ?: "",
                    mediaflowAutoSelect = mediaflowAutoSelect,
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
                        val sent = when (connectionState) {
                            is WebSocketClient.ConnectionState.Connected -> {
                                val ok = connectionViewModel.webSocketClient.send(cmd)
                                if (ok) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                    browserViewModel.logHistory(payload.url, payload.title)
                                }
                                ok
                            }
                            else -> false
                        }
                        if (sent) Toast.makeText(this@BrowserActivity, "Play command sent to TV", Toast.LENGTH_SHORT).show()
                        showVideoSheet = false
                        forcePlaylistSheet = null
                    },
                    // Magnet Parsing Sheet States
                    interceptedMagnet = interceptedMagnet,
                    interceptedTorrentBytes = interceptedTorrentBytes,
                    onDismissMagnet = {
                        interceptedMagnet = null
                        interceptedTorrentBytes = null
                    },
                    onPlayMagnetLinks = { links ->
                        val videos = links.map { link ->
                            playbridge.PlayPayload(
                                url = link.downloadUrl,
                                title = link.filename,
                                player_mode = tvPlayerMode.takeIf { it != "tv" },
                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = maxBitrateCapMbps
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

                pendingPopup?.let { popup ->
                    val popupPrefs = remember { getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
                    fun openPopupTab() {
                        scope.launch(Dispatchers.Main) {
                            val tabId = tabManager.createTab(url = popup.popupUrl, store = store, parentId = selectedTab?.id, select = true)
                            tabManager.sessions[tabId] = popup.engineSession

                            // Register standard observer for popup tab
                            popup.engineSession.register(object : EngineSession.Observer {
                                override fun onStateUpdated(state: mozilla.components.concept.engine.EngineSessionState) {
                                    tabManager.engineStates[tabId] = state
                                    tabManager.onAnyStateUpdated?.invoke(tabId)
                                }
                                override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
                                    val current = tabManager.navigationStates[tabId] ?: TabNavigationState()
                                    tabManager.navigationStates[tabId] = current.copy(
                                        canGoBack = canGoBack ?: current.canGoBack,
                                        canGoForward = canGoForward ?: current.canGoForward
                                    )
                                }
                            })
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        PopupBlockedBar(
                            host = popup.openerHost,
                            onAllowOnce = { openPopupTab(); pendingPopup = null; pendingPopupState.value = null },
                            onAlwaysAllow = {
                                 composeScope.launch { settingsRepository.addPopupWhitelist(popup.openerHost) }
                                 openPopupTab()
                                 pendingPopup = null
                                 pendingPopupState.value = null
                             },
                            onDismiss = { popup.rawGeckoSession.close(); pendingPopup = null; pendingPopupState.value = null }
                        )
                    }
                }

                DownloadConfirmDialog(
                    pendingDownload = pendingDownload,
                    onConfirm = { download ->
                        DownloadUtils.enqueueDownload(this@BrowserActivity, download.url, download.fileName, download.contentType, download.userAgent, download.cookie, download.referer, pageTitle = selectedTab?.content?.title)
                        Toast.makeText(this@BrowserActivity, "Download started", Toast.LENGTH_SHORT).show()
                        pendingDownload = null
                        pendingDownloadState.value = null
                    },
                    onPlayOnTv = { download ->
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

                pendingStremioAddon?.let { addonUri ->
                    // Convert stremio:// to https:// for a readable host, then derive a label.
                    val httpsUrl = addonUri.replaceFirst("stremio://", "https://")
                    val host = runCatching { java.net.URI(httpsUrl).host }.getOrNull() ?: httpsUrl
                    AlertDialog(
                        onDismissRequest = { if (!isInstallingStremioAddon) pendingStremioAddon = null },
                        title = { Text("Install Stremio addon?") },
                        text = {
                            Column {
                                Text(host, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    httpsUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !isInstallingStremioAddon,
                                onClick = {
                                    isInstallingStremioAddon = true
                                    scope.launch {
                                        val result = runCatching { addonRepository.installAddon(addonUri) }.getOrNull()
                                        isInstallingStremioAddon = false
                                        pendingStremioAddon = null
                                        Toast.makeText(
                                            this@BrowserActivity,
                                            if (result != null) "Installed: ${result.name}" else "Failed to install addon",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            ) {
                                Text(if (isInstallingStremioAddon) "Installing…" else "Install")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !isInstallingStremioAddon,
                                onClick = { pendingStremioAddon = null }
                            ) { Text("Cancel") }
                        }
                    )
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
