package com.playbridge.sender.browser

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
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
    
    private val webSocketClient = WebSocketClient()
    private val tabManager = TabManager()
    private lateinit var connectionStore: ConnectionStore
    private lateinit var nsdHelper: NsdHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        connectionStore = ConnectionStore(applicationContext)
        nsdHelper = NsdHelper(applicationContext)
        
        if (!Components.isEngineInitialized()) {
            Components.initialize(applicationContext)
        }
        
        // Install the bundled video detector extension
        Components.installBundledExtension()

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Browser) }
            val clipboardManager = LocalClipboardManager.current
            val connectionState by webSocketClient.connectionState.collectAsState()
            val history by connectionStore.deviceHistory.collectAsState(initial = emptyList())
            val scope = rememberCoroutineScope()
            
            // Database and History
            val database = remember { DatabaseProvider.getDatabase(applicationContext) }
            val historyDao = remember { database.historyDao() }
            val bookmarkDao = remember { database.bookmarkDao() }
            
            // Suggestions State
            var isEditing by remember { mutableStateOf(false) }
            var editUrl by remember { mutableStateOf("") }
            val suggestions by historyDao.search(editUrl).collectAsState(initial = emptyList())
            val allHistory by historyDao.getAll().collectAsState(initial = emptyList())
            
            // Auto-connect to stored TV device
            LaunchedEffect(Unit) {
                connectionStore.tvDevice.collect { device ->
                    if (device != null && connectionState is WebSocketClient.ConnectionState.Disconnected) {
                        Log.d(TAG, "Auto-connecting to TV: ${device.name} at ${device.ip}:${device.port}")
                        webSocketClient.connect(device.ip, device.port, device.token, device.name)
                    }
                }
            }
            
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
            LaunchedEffect(browserState.tabs) {
                tabManager.syncSessions(browserState.tabs)
            }
            
            val sessions = tabManager.sessions
            
            val selectedTabId = browserState.selectedTabId
            val selectedTab = browserState.tabs.find { it.id == selectedTabId }
            val session = if (selectedTab != null) sessions[selectedTab.id] else null
            
            // State for download dialog
            var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
            
            // If no session is available (e.g. during init), show loading or empty
            if (session == null) {
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
            var isDesktopMode by remember { mutableStateOf(false) }
            var isSecureConnection by remember { mutableStateOf(false) }
            
            // Update UI state from session
            LaunchedEffect(session, selectedTab) {
                if (selectedTab != null) {
                    currentUrl = selectedTab.content.url
                }
            }
            
            // View state - browser or scanner
            var showScanner by remember { mutableStateOf(false) }
            
            // Back press handling
            var backPressedTime by remember { mutableLongStateOf(0L) }
            
            // Video detection state
            var showVideoSheet by remember { mutableStateOf(false) }
            val detectedVideos = VideoDetector.detectedVideos
            val videoCount by remember { derivedStateOf { detectedVideos.size } }
            
            // Remote control state
            var showRemoteSheet by remember { mutableStateOf(false) }
            
            // TV active context - updated via WebSocket messages from TV
            var tvActiveContext by remember { mutableStateOf("idle") } // "player", "browser", or "idle"
            
            // Find in Page state
            var showFindBar by remember { mutableStateOf(false) }
            
            // Clear finding when bar closes
            LaunchedEffect(showFindBar) {
                if (!showFindBar) {
                    tabManager.clearFind(session)
                }
            }

            // Listen for context messages from TV
            LaunchedEffect(Unit) {
                launch {
                    webSocketClient.messages.collect { message ->
                        try {
                            val json = org.json.JSONObject(message)
                            if (json.optString("type") == "context") {
                                tvActiveContext = json.optString("active", "idle")
                            }
                        } catch (_: Exception) { }
                    }
                }
                
                // Listen for new auth tokens (e.g. after PIN exchange)
                launch {
                    webSocketClient.newToken.collect { token ->
                        val currentDevice = connectionStore.tvDevice.first()
                        if (currentDevice != null) {
                            Log.i(TAG, "Updating stored token for ${currentDevice.ip}")
                            val updatedDevice = currentDevice.copy(token = token)
                            connectionStore.saveTvDevice(updatedDevice)
                            connectionStore.addToHistory(updatedDevice)
                        }
                    }
                }
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
            
            // Sync wrapper states back to local vars
            currentUrl = currentUrlState.value
            isLoading = isLoadingState.value
            browserCanGoBack = canGoBackState.value
            browserCanGoForward = canGoForwardState.value
            contextMenuUrl = contextMenuUrlState.value
            previousUrl = previousUrlState.value
            pendingDownload = pendingDownloadState.value
            isSecureConnection = isSecureConnectionState.value
            // isDesktopMode is controlled by the UI, so we sync downwards to the observer setup
            // which will react to changes

            
            // Link context menu
            LinkContextMenu(
                url = contextMenuUrl,
                isConnected = connectionState is WebSocketClient.ConnectionState.Connected,
                onPlayOnTv = { linkUrl ->
                    val cmd = com.playbridge.protocol.createPlayCommandJson(url = linkUrl)
                    webSocketClient.send(cmd)
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
                isSecureConnection = isSecureConnectionState,
                onXpiDetected = { url ->
                    runOnUiThread {
                        Toast.makeText(this@BrowserActivity, "Installing extension...", Toast.LENGTH_SHORT).show()
                    }
                    CoroutineScope(Dispatchers.Main).launch {
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
                onVideoHashDetected = { url ->
                    try {
                        val hashData = url.substringAfter("#playbridge-video=")
                        val decoded = java.net.URLDecoder.decode(hashData, "UTF-8")
                        Log.d(TAG, "PlayBridge video signal: $decoded")
                        
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
                            )))
                            Log.d(TAG, "Video added to VideoDetector from hash signal")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing playbridge-video hash", e)
                    }
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
                    topBar = {
                        when (currentScreen) {
                            Screen.Browser -> {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        BrowserToolbar(
                                            currentUrl = currentUrl,
                                            isLoading = isLoading,
                                            canGoBack = browserCanGoBack,
                                            canGoForward = browserCanGoForward,
                                            videoCount = videoCount,
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
                                            onVideoClick = { showVideoSheet = true },
                                            onTabsClick = { currentScreen = Screen.Tabs },
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
                                                            showRemoteSheet = true
                                                            webSocketClient.send(com.playbridge.protocol.createContextQueryJson())
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
                                                            val cmd = com.playbridge.protocol.createBrowserCommandJson(currentUrl)
                                                            webSocketClient.send(cmd)
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

                                                // Connection Status
                                                val (connectText, connectIcon, connectColor) = when (connectionState) {
                                                    is WebSocketClient.ConnectionState.Connected -> {
                                                        val serverName = (connectionState as WebSocketClient.ConnectionState.Connected).serverName
                                                        Triple("Connected: $serverName", Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary)
                                                    }
                                                    is WebSocketClient.ConnectionState.Connecting -> {
                                                        Triple("Connecting to TV...", Icons.Default.Refresh, MaterialTheme.colorScheme.tertiary)
                                                    }
                                                    is WebSocketClient.ConnectionState.Error -> {
                                                        Triple("Connection Error - Tap to Retry", Icons.Default.Warning, MaterialTheme.colorScheme.error)
                                                    }
                                                    else -> {
                                                        Triple("Connect to TV", Icons.Default.Settings, MaterialTheme.colorScheme.onSurface)
                                                    }
                                                }
                                                
                                                AnimatedMenuItem(
                                                    index = 6,
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.Connection
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text(connectText, style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(connectIcon, null, tint = connectColor) },
                                                        onClick = onClick,
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }
                                                
                                                AnimatedMenuItem(
                                                    index = 7,
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.Settings
                                                    }
                                                ) { onClick ->
                                                    DropdownMenuItem(
                                                        text = { Text("Settings", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurface) },
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
                                @OptIn(ExperimentalMaterial3Api::class)
                                TopAppBar(
                                    title = { Text("Connect to TV") },
                                    navigationIcon = {
                                        IconButton(onClick = { currentScreen = Screen.Browser }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                        }
                                    }
                                )
                            }
                            Screen.History -> {
                                // No TopAppBar here as HistoryScreen has its own
                            }
                            Screen.Bookmarks -> {}
                            Screen.Home -> {}

                            Screen.Downloads -> {
                                // No TopAppBar here as DownloadsScreen has its own
                            }
                            Screen.Settings -> {
                                // No TopAppBar here as SettingsScreen has its own
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
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
                                    } else if ((targetState == Screen.Downloads || targetState == Screen.Extensions || targetState == Screen.Settings || targetState == Screen.Bookmarks) && initialState == Screen.Browser) {
                                         slideInVertically { height -> height } + fadeIn() togetherWith
                                                slideOutVertically { height -> -height } + fadeOut()
                                    } else if (targetState == Screen.Browser && (initialState == Screen.Downloads || initialState == Screen.Extensions || initialState == Screen.Settings || initialState == Screen.Bookmarks)) {
                                         slideInVertically { height -> -height } + fadeIn() togetherWith
                                                slideOutVertically { height -> height } + fadeOut()
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
                                            // Browser: first back goes to browser history, second back exits
                                            BackHandler {
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
                                            
                                            // BrowserView call site
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                BrowserView(
                                                    session = session,
                                                    onLongPressLink = { url: String -> contextMenuUrl = url }
                                                )
                                                
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
                                                nsdHelper = nsdHelper,
                                                history = history,
                                                onConnect = { device ->
                                                    scope.launch {
                                                        Log.d(TAG, "Connecting to: ${device.name} at ${device.ip}:${device.port}")
                                                        
                                                        // Save TV device
                                                        connectionStore.saveTvDevice(device)
                                                        connectionStore.addToHistory(device)
                                                        Log.d(TAG, "TV device saved")
                                                        
                                                        // Connect
                                                        webSocketClient.connect(
                                                            device.ip,
                                                            device.port,
                                                            device.token,
                                                            device.name
                                                        )
                                                        Log.d(TAG, "Connecting to TV...")
                                                        
                                                        // Show toast
                                                        runOnUiThread {
                                                            Toast.makeText(
                                                                this@BrowserActivity,
                                                                "Connecting to ${device.name}...",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                        
                                                        // Return to browser
                                                        currentScreen = Screen.Browser
                                                    }
                                                },
                                                onRemove = { device ->
                                                    scope.launch {
                                                        connectionStore.removeFromHistory(device)
                                                    }
                                                }
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
                                                            contentType = type
                                                        )
                                                        webSocketClient.send(cmd)
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
                                                onBack = { currentScreen = Screen.Browser }
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
                                        Screen.Home -> {
                                            // Home is root, but if we came from elsewhere back might exit?
                                            // Actually Home might be the starting screen.
                                            // For now, let's say Home -> Browser (if url entered) or back exits app?
                                            // If Home is "New Tab", then back might close tab?
                                            // Let's treat Home as a screen that navigates to Browser.
                                            BackHandler { 
                                                // If on Home, back exits app or goes to Browser?
                                                // If we have tabs, maybe go to Browser?
                                                // For now, mimicking standard behavior:
                                                if (browserCanGoBack) {
                                                    session.goBack() 
                                                    // But Home is not part of session history directly usually.
                                                    // If URL is "playbridge://home", we show Home.
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
                                    }
                                }
                            }
                    }
                }
                
                // Video detection bottom sheet
                if (showVideoSheet) {
                    DetectedVideosSheet(
                        videos = detectedVideos.toList(),
                        onDismiss = { showVideoSheet = false },
                        onVideoClick = { video, subtitles ->
                            Log.d(TAG, "=== PLAY ON TV CLICKED ===")
                            Log.d(TAG, "Video URL: ${video.url}")
                            Log.d(TAG, "Subtitles: $subtitles")
                            Log.d(TAG, "Connection state: $connectionState")
                            
                            when (val state = connectionState) {
                                is WebSocketClient.ConnectionState.Connected -> {
                                    Log.d(TAG, "Connected to: ${state.serverName}")
                                    
                                    val headers = mutableMapOf<String, String>()
                                    // Default UA
                                    headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                    
                                    // Default Referer from originUrl
                                    if (!video.originUrl.isNullOrEmpty()) {
                                        headers["Referer"] = video.originUrl
                                    }
                                    
                                    // Apply captured headers (these take precedence and include Cookies)
                                    video.headers?.forEach { (k, v) -> 
                                        headers[k] = v 
                                    }
                                    
                                    val commandJson = com.playbridge.protocol.createPlayCommandJson(
                                        url = video.url,
                                        title = selectedTab?.content?.title ?: "Video from browser",
                                        headers = headers,
                                        contentType = video.contentType,
                                        subtitles = subtitles
                                    )
                                    Log.d(TAG, "Sending play command: $commandJson")
                                    val sent = webSocketClient.send(commandJson)
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

                        },
                        onClear = {
                            VideoDetector.clear()
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
                
                // Remote control bottom sheet
                if (showRemoteSheet) {
                    RemoteControlSheet(
                        isMediaPlaying = tvActiveContext == "player",
                        onDismiss = { showRemoteSheet = false },
                        onRemoteKey = { key ->
                            val cmd = com.playbridge.protocol.createRemoteCommandJson(key)
                            webSocketClient.send(cmd)
                        },
                        onMouseMove = { dx, dy ->
                            val cmd = com.playbridge.protocol.createMouseCommandJson("move", dx, dy)
                            webSocketClient.send(cmd)
                        },
                        onMouseClick = {
                            val cmd = com.playbridge.protocol.createMouseCommandJson("click")
                            webSocketClient.send(cmd)
                        },
                        onMouseScroll = { dx, dy ->
                            val cmd = com.playbridge.protocol.createMouseCommandJson("scroll", dx, dy)
                            webSocketClient.send(cmd)
                        },
                        onBrowserControl = { action ->
                            val cmd = com.playbridge.protocol.createBrowserControlCommandJson(action)
                            webSocketClient.send(cmd)
                        },
                        onPlayerControl = { command ->
                            val cmd = com.playbridge.protocol.createControlCommandJson(command)
                            webSocketClient.send(cmd)
                            if (command == "stop") {
                                tvActiveContext = "idle"
                            }
                        }
                    )
                }

                // Download Confirmation Dialog
                DownloadConfirmDialog(
                    pendingDownload = pendingDownload,
                    onConfirm = { download ->
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
                    onDismiss = {
                        pendingDownload = null
                        pendingDownloadState.value = null
                    }
                )
            }
        }
    }
    


    override fun onDestroy() {
        webSocketClient.destroy()
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
    object Bookmarks : Screen()
    object Home : Screen()
}
