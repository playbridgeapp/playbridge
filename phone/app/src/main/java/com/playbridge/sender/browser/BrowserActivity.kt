package com.playbridge.sender.browser

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.playbridge.sender.model.QRCodeData
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.QRScannerScreen
import com.playbridge.sender.ui.theme.PlayBridgeTheme
import mozilla.components.lib.state.ext.flow

class BrowserActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "BrowserActivity"
    }
    
    private val webSocketClient = WebSocketClient()
    private lateinit var connectionStore: ConnectionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        connectionStore = ConnectionStore(applicationContext)
        
        if (!Components.isEngineInitialized()) {
            Components.initialize(applicationContext)
        }
        
        // Install the bundled video detector extension
        Components.installBundledExtension()

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Browser) }
            val connectionState by webSocketClient.connectionState.collectAsState()
            val history by connectionStore.deviceHistory.collectAsState(initial = emptyList())
            val scope = rememberCoroutineScope()
            
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
                if (browserState.tabs.isEmpty()) {
                    val tabId = UUID.randomUUID().toString()
                    store.dispatch(TabListAction.AddTabAction(
                        tab = TabSessionState(
                            id = tabId,
                            content = ContentState(url = "https://www.google.com"),
                            parentId = null
                        )
                    ))
                }
            }
            
            // Session management
            val sessions = remember { mutableStateMapOf<String, EngineSession>() }
            
            // Sync sessions with tabs (create sessions for all tabs, not just selected)
            // This ensures background tabs start loading immediately
            LaunchedEffect(browserState.tabs) {
                browserState.tabs.forEach { tab ->
                    if (!sessions.containsKey(tab.id)) {
                        val newSession = Components.engine.createSession()
                        if (tab.content.url.isNotEmpty() && tab.content.url != "about:blank") {
                             newSession.loadUrl(tab.content.url)
                        } else {
                             newSession.loadUrl("https://www.google.com")
                        }
                        sessions[tab.id] = newSession
                    }
                }
                // Cleanup sessions for closed tabs
                val activeTabIds = browserState.tabs.map { it.id }.toSet()
                sessions.keys.retainAll(activeTabIds) 
            }
            
            val selectedTabId = browserState.selectedTabId
            val selectedTab = browserState.tabs.find { it.id == selectedTabId }
            val session = if (selectedTab != null) sessions[selectedTab.id] else null
            
            // If no session is available (e.g. during init), show loading or empty
            if (session == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@setContent
            }
            
            // Existing state variables
            var currentUrl by remember { mutableStateOf("https://www.google.com") }
            var isLoading by remember { mutableStateOf(false) }
            var canGoBack by remember { mutableStateOf(false) }
            var canGoForward by remember { mutableStateOf(false) }
            var menuExpanded by remember { mutableStateOf(false) }
            
            // Update UI state from session
            LaunchedEffect(session) {
                // relying on observer for URL updates
                // currentUrl = session.url  // if available
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
            var tvMode by remember { mutableStateOf(TvMode.Unknown) }
            
            // Track if media is actively playing on TV
            var isMediaPlaying by remember { mutableStateOf(false) }
            
            // Track previous URL to avoid clearing on hash changes
            var previousUrl by remember { mutableStateOf("") }
            
            // Context menu state for "Open in new tab"
            var contextMenuUrl by remember { mutableStateOf<String?>(null) }
            
            if (contextMenuUrl != null) {
                AlertDialog(
                    onDismissRequest = { contextMenuUrl = null },
                    title = { Text("Link Options") },
                    text = { Text(contextMenuUrl!!) },
                    confirmButton = {
                        TextButton(onClick = {
                            val newTabId = UUID.randomUUID().toString()
                            store.dispatch(TabListAction.AddTabAction(
                                tab = TabSessionState(
                                    id = newTabId,
                                    content = ContentState(url = contextMenuUrl!!),
                                    parentId = null
                                )
                            ))
                            Toast.makeText(this@BrowserActivity, "Opened in new tab", Toast.LENGTH_SHORT).show()
                            contextMenuUrl = null
                        }) {
                            Text("Open in new tab")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { contextMenuUrl = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            
            // Register navigation observer with download interception
            DisposableEffect(session) {
                val observer = object : EngineSession.Observer {
                    override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                         // Update store state too to keep it in sync
                        if (selectedTab != null) {
                            store.dispatch(ContentAction.UpdateUrlAction(
                                sessionId = selectedTab.id,
                                url = url
                            ))
                        }

                        // Get base URL without hash/fragment
                        val baseUrl = url.substringBefore("#")
                        val previousBaseUrl = previousUrl.substringBefore("#")
                        
                        currentUrl = url
                        
                        // Clear detected videos only when navigating to a different page
                        // (ignore hash/fragment changes on the same page)
                        if (baseUrl != previousBaseUrl && previousBaseUrl.isNotEmpty()) {
                            VideoDetector.clear()
                            Log.d(TAG, "Cleared detected videos - navigated from $previousBaseUrl to $baseUrl")
                        }
                        
                        previousUrl = url
                        
                        // Check for playbridge-video hash signal from content script
                        if (url.contains("#playbridge-video=")) {
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
                    }
                    override fun onLoadingStateChange(loading: Boolean) {
                        isLoading = loading
                        if (selectedTab != null) {
                            // store.dispatch(ContentAction.UpdateLoading(
                            //    sessionId = selectedTab.id,
                            //    loading = loading
                            // ))
                        }
                    }
                    override fun onNavigationStateChange(canGoBackNow: Boolean?, canGoForwardNow: Boolean?) {
                        canGoBackNow?.let { canGoBack = it }
                        canGoForwardNow?.let { canGoForward = it }
                    }
                    
                    // Detect video count from page title [PlayBridge:X] marker
                    override fun onTitleChange(title: String) {
                        Log.d(TAG, "Title changed: $title")
                        if (selectedTab != null) {
                            store.dispatch(ContentAction.UpdateTitleAction(
                                sessionId = selectedTab.id,
                                title = title
                            ))
                        }
                        val match = Regex("\\[PlayBridge:(\\d+)\\]").find(title)
                        if (match != null) {
                            val count = match.groupValues[1].toIntOrNull() ?: 0
                            Log.d(TAG, "PlayBridge video count detected: $count")
                            // Note: The content script also stores videos in localStorage
                            // but we can't read it directly. The count marker tells us videos exist.
                        }
                    }
                    
                    // Intercept downloads - this catches XPI files from AMO
                    override fun onExternalResource(
                        url: String,
                        fileName: String?,
                        contentLength: Long?,
                        contentType: String?,
                        cookie: String?,
                        userAgent: String?,
                        isPrivate: Boolean,
                        skipConfirmation: Boolean,
                        openInApp: Boolean,
                        response: Response?
                    ) {
                        Log.d(TAG, "Download intercepted: $url, type: $contentType, file: $fileName")
                        
                        // Check if this is an XPI file (Firefox extension)
                        if (url.endsWith(".xpi") || contentType == "application/x-xpinstall") {
                            Log.d(TAG, "XPI detected, installing addon from: $url")
                            
                            runOnUiThread {
                                Toast.makeText(
                                    this@BrowserActivity,
                                    "Installing extension...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            
                            // Install the addon via AddonManager
                            CoroutineScope(Dispatchers.Main).launch {
                                try {
                                    Components.addonManager.installAddon(
                                        url = url,
                                        onSuccess = { addon ->
                                            Log.d(TAG, "Addon installed: ${addon.id}")
                                            runOnUiThread {
                                                Toast.makeText(
                                                    this@BrowserActivity,
                                                    "Extension installed successfully!",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        },
                                        onError = { throwable ->
                                            Log.e(TAG, "Addon install failed", throwable)
                                            runOnUiThread {
                                                Toast.makeText(
                                                    this@BrowserActivity,
                                                    "Install failed: ${throwable.message}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error installing addon", e)
                                }
                            }
                        }
                    }
                }
                session.register(observer)
                
                // Set up GeckoSession delegates using reflection (since geckoSession is internal)
                val geckoEngineSession = session as? GeckoEngineSession
                
                // Variables to hold original dlegates for restoration
                var originalNavDelegate: GeckoSession.NavigationDelegate? = null
                var originalContentDelegate: GeckoSession.ContentDelegate? = null
                var geckoSessionInstance: GeckoSession? = null

                if (geckoEngineSession != null) {
                    try {
                        val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
                        field.isAccessible = true
                        val internalSession = field.get(geckoEngineSession) as? GeckoSession
                        geckoSessionInstance = internalSession
                        
                        internalSession?.let { gs ->
                            // Wrap NavigationDelegate using Proxy
                            val existingNav = gs.navigationDelegate
                            originalNavDelegate = existingNav
                            
                            if (existingNav != null) {
                                val navProxy = java.lang.reflect.Proxy.newProxyInstance(
                                    GeckoSession.NavigationDelegate::class.java.classLoader,
                                    arrayOf(GeckoSession.NavigationDelegate::class.java)
                                ) { _, method, args ->
                                    if (method.name == "onNewSession" && args != null && args.size >= 2) {
                                        val uri = args[1] as? String
                                        if (uri != null) {
                                            val newTabId = UUID.randomUUID().toString()
                                            store.dispatch(TabListAction.AddTabAction(
                                                tab = TabSessionState(
                                                    id = newTabId,
                                                    content = ContentState(url = uri),
                                                    parentId = selectedTab?.id
                                                ),
                                                select = true
                                            ))
                                            // dispatch UpdateUrl to set the actual URI if needed, or rely on Engine
                                            // Actually, AddTabAction content url should be the uri.
                                            // But let's stick to what we had: content = ContentState(url = uri)
                                            // Re-correcting the inner logic below to match previous step
                                        }
                                         // If we handled it, strict return null or handle appropriately?
                                        return@newProxyInstance GeckoResult.fromValue(null)
                                    }
                                    
                                    // Forward to original
                                    try {
                                        if (args != null) method.invoke(existingNav, *args)
                                        else method.invoke(existingNav)
                                    } catch (e: java.lang.reflect.InvocationTargetException) {
                                        throw e.targetException
                                    }
                                } as GeckoSession.NavigationDelegate
                                gs.navigationDelegate = navProxy
                            }
                            
                            // Wrap ContentDelegate using Proxy
                            val existingContent = gs.contentDelegate
                            originalContentDelegate = existingContent
                            
                            if (existingContent != null) {
                                val contentProxy = java.lang.reflect.Proxy.newProxyInstance(
                                    GeckoSession.ContentDelegate::class.java.classLoader,
                                    arrayOf(GeckoSession.ContentDelegate::class.java)
                                ) { _, method, args ->
                                    if (method.name == "onContextMenuItemSelected" && args != null && args.size >= 2) {
                                        val item = args[1]
                                        if (item != null) {
                                            try {
                                                 val idField = item.javaClass.getField("id")
                                                 val id = idField.getInt(item)
                                                 val idOpenInNewTabField = item.javaClass.getField("ID_OPEN_IN_NEW_TAB")
                                                 val idOpenInNewTab = idOpenInNewTabField.getInt(null)
                                                 
                                                 if (id == idOpenInNewTab) {
                                                     return@newProxyInstance true
                                                 }
                                            } catch (e: Exception) {
                                                // Ignore reflection errors
                                            }
                                        }
                                    }
                                    if (method.name == "onContextMenu" && args != null && args.size >= 4) {
                                        val element = args[3]
                                        if (element != null) {
                                            try {
                                                val linkUriField = element.javaClass.getField("linkUri")
                                                val linkUri = linkUriField.get(element) as? String
                                                if (linkUri != null) {
                                                    contextMenuUrl = linkUri
                                                }
                                            } catch (e: Exception) {
                                                // Ignore reflection errors
                                            }
                                        }
                                    }
                                    try {
                                        if (args != null) method.invoke(existingContent, *args)
                                        else method.invoke(existingContent)
                                    } catch (e: java.lang.reflect.InvocationTargetException) {
                                        throw e.targetException
                                    }
                                } as GeckoSession.ContentDelegate
                                gs.contentDelegate = contentProxy
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to setup GeckoSession delegates", e)
                    }
                }
                
                onDispose {
                    session.unregister(observer)
                    // Restore original delegates
                    geckoSessionInstance?.let { gs ->
                        if (originalNavDelegate != null) gs.navigationDelegate = originalNavDelegate
                        if (originalContentDelegate != null) gs.contentDelegate = originalContentDelegate
                    }
                }
            }


            PlayBridgeTheme {
                Scaffold(
                    topBar = {
                        when (currentScreen) {
                            Screen.Browser -> {
                                Box {
                                    BrowserToolbar(
                                        currentUrl = currentUrl,
                                        isLoading = isLoading,
                                        canGoBack = canGoBack,
                                        canGoForward = canGoForward,
                                        videoCount = videoCount,
                                        onUrlChange = { },
                                        onNavigate = { url -> session.loadUrl(url) },
                                        onBack = { session.goBack() },
                                        onForward = { session.goForward() },
                                        onRefresh = { session.reload() },
                                        onStop = { session.stopLoading() },
                                        onMenuClick = { menuExpanded = true },
                                        onVideoClick = { showVideoSheet = true },
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
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 8.dp), // Removed horizontal padding to allow full width evenly
                                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            session.goBack()
                                                            menuExpanded = false
                                                        },
                                                        enabled = canGoBack
                                                    ) {
                                                        Icon(
                                                            Icons.AutoMirrored.Filled.ArrowBack,
                                                            "Back",
                                                            tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            session.goForward()
                                                            menuExpanded = false
                                                        },
                                                        enabled = canGoForward
                                                    ) {
                                                        Icon(
                                                            Icons.AutoMirrored.Filled.ArrowForward,
                                                            "Forward",
                                                            tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
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
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                DropdownMenuItem(
                                                    text = { Text("Tabs", style = MaterialTheme.typography.bodyLarge) },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.Tabs
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Extensions", style = MaterialTheme.typography.bodyLarge) },
                                                    leadingIcon = { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.Extensions
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                )
                                                
                                                // Dynamic Connect to TV menu item with status
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
                                                
                                                DropdownMenuItem(
                                                    text = { Text(connectText, style = MaterialTheme.typography.bodyLarge) },
                                                    leadingIcon = { Icon(connectIcon, null, tint = connectColor) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        currentScreen = Screen.Scanner
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                )

                                                // Open on TV (only when connected)
                                                if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                    DropdownMenuItem(
                                                        text = { Text("Open on TV", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            val cmd = com.playbridge.sender.model.createBrowserCommandJson(currentUrl)
                                                            webSocketClient.send(cmd)
                                                            Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                    
                                                    // Remote Control
                                                    DropdownMenuItem(
                                                        text = { Text("Remote Control", style = MaterialTheme.typography.bodyLarge) },
                                                        leadingIcon = { Icon(Icons.Default.Gamepad, null, tint = MaterialTheme.colorScheme.primary) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            showRemoteSheet = true
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                    )
                                                }
                                                
                                                // Media controls (only show when connected AND media is playing)
                                                if (connectionState is WebSocketClient.ConnectionState.Connected && isMediaPlaying) {
                                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                    
                                                    // Media controls header
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Tv,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(20.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Spacer(modifier = Modifier.width(12.dp))
                                                        Text(
                                                            "Media Controls",
                                                            style = MaterialTheme.typography.labelLarge,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    
                                                    // Control buttons row
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                                        horizontalArrangement = Arrangement.SpaceEvenly
                                                    ) {
                                                        // Track play/pause state
                                                        var isPlaying by remember { mutableStateOf(false) }
                                                        
                                                        // Play/Pause toggle button
                                                        IconButton(
                                                            onClick = {
                                                                val cmd = if (isPlaying) {
                                                                    com.playbridge.sender.model.createControlCommandJson("pause")
                                                                } else {
                                                                    com.playbridge.sender.model.createControlCommandJson("play")
                                                                }
                                                                webSocketClient.send(cmd)
                                                                isPlaying = !isPlaying
                                                                Toast.makeText(
                                                                    this@BrowserActivity, 
                                                                    if (isPlaying) "▶ Playing" else "⏸ Paused", 
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        ) {
                                                            Icon(
                                                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                                if (isPlaying) "Pause" else "Play",
                                                                tint = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                        
                                                        // Stop button
                                                        IconButton(
                                                            onClick = {
                                                                val cmd = com.playbridge.sender.model.createControlCommandJson("stop")
                                                                webSocketClient.send(cmd)
                                                                isMediaPlaying = false
                                                                Toast.makeText(this@BrowserActivity, "⏹ Stop", Toast.LENGTH_SHORT).show()
                                                            }
                                                        ) {
                                                            Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.error)
                                                        }
                                                    }
                                                }
                                                
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            }
                                        }
                                    )
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
                            Screen.Scanner -> {
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
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.Browser -> {
                                // Browser: first back goes to browser history, second back exits
                                BackHandler {
                                    if (canGoBack) {
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
                                        onLongPressLink = { url -> contextMenuUrl = url }
                                    )
                                }
                            }
                            Screen.Tabs -> {
                                BackHandler { currentScreen = Screen.Browser }
                                TabsScreen(
                                    onTabSelected = { tabId ->
                                        store.dispatch(TabListAction.SelectTabAction(tabId))
                                        currentScreen = Screen.Browser
                                    },
                                    onTabClosed = { tabId ->
                                        store.dispatch(TabListAction.RemoveTabAction(tabId))
                                    },
                                    onNewTab = {
                                        val newId = UUID.randomUUID().toString()
                                        store.dispatch(TabListAction.AddTabAction(
                                            tab = TabSessionState(
                                                id = newId,
                                                content = ContentState(url = "https://www.google.com"),
                                                parentId = null
                                            ),
                                            select = true
                                        ))
                                        currentScreen = Screen.Browser
                                    }
                                )
                            }
                            Screen.Extensions -> {
                                BackHandler { currentScreen = Screen.Browser }
                                ExtensionsScreen(
                                    session = session,
                                    onBack = { currentScreen = Screen.Browser }
                                )
                            }
                            Screen.Scanner -> {
                                BackHandler { currentScreen = Screen.Browser }
                                QRScannerScreen(
                                    history = history,
                                    onQRCodeScanned = { qrData ->
                                        scope.launch {
                                            Log.d(TAG, "QR Code scanned: ${qrData.name} at ${qrData.ip}:${qrData.port}")
                                            
                                            // Save TV device
                                            val tvDevice = TvDevice(
                                                ip = qrData.ip,
                                                port = qrData.port,
                                                token = qrData.token,
                                                name = qrData.name
                                            )
                                            connectionStore.saveTvDevice(tvDevice)
                                            connectionStore.addToHistory(tvDevice)
                                            Log.d(TAG, "TV device saved")
                                            
                                            // Connect
                                            webSocketClient.connect(
                                                qrData.ip,
                                                qrData.port,
                                                qrData.token,
                                                qrData.name
                                            )
                                            Log.d(TAG, "Connecting to TV...")
                                            
                                            // Show toast
                                            runOnUiThread {
                                                Toast.makeText(
                                                    this@BrowserActivity,
                                                    "Connecting to ${qrData.name}...",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            
                                            // Return to browser
                                            currentScreen = Screen.Browser
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                
                // Video detection bottom sheet
                if (showVideoSheet) {
                    DetectedVideosSheet(
                        videos = detectedVideos.toList(),
                        onDismiss = { showVideoSheet = false },
                        onVideoClick = { video ->
                            Log.d(TAG, "=== PLAY ON TV CLICKED ===")
                            Log.d(TAG, "Video URL: ${video.url}")
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
                                    
                                    val commandJson = com.playbridge.sender.model.createPlayCommandJson(
                                        url = video.url,
                                        title = "Video from browser",
                                        headers = headers,
                                        contentType = video.contentType
                                    )
                                    Log.d(TAG, "Sending play command: $commandJson")
                                    val sent = webSocketClient.send(commandJson)
                                    Log.d(TAG, "Command sent: $sent")
                                    
                                    if (sent) {
                                        isMediaPlaying = true
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
                        }
                    )
                }
                
                // Remote control bottom sheet
                if (showRemoteSheet) {
                    RemoteControlSheet(
                        tvMode = tvMode,
                        onDismiss = { showRemoteSheet = false },
                        onRemoteKey = { key ->
                            val cmd = com.playbridge.sender.model.createRemoteCommandJson(key)
                            webSocketClient.send(cmd)
                        },
                        onMouseMove = { dx, dy ->
                            val cmd = com.playbridge.sender.model.createMouseCommandJson("move", dx, dy)
                            webSocketClient.send(cmd)
                        },
                        onMouseClick = {
                            val cmd = com.playbridge.sender.model.createMouseCommandJson("click")
                            webSocketClient.send(cmd)
                        },
                        onMouseScroll = { dx, dy ->
                            val cmd = com.playbridge.sender.model.createMouseCommandJson("scroll", dx, dy)
                            webSocketClient.send(cmd)
                        },
                        onBrowserControl = { action ->
                            val cmd = com.playbridge.sender.model.createBrowserControlCommandJson(action)
                            webSocketClient.send(cmd)
                        }
                    )
                }
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
    object Scanner : Screen()
}
