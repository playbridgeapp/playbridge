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
                // Find sessions that need to be removed
                val removedSessionIds = sessions.keys.filter { !activeTabIds.contains(it) }
                
                removedSessionIds.forEach { id ->
                    val session = sessions[id]
                    if (session != null) {
                        try {
                            // Stop loading first
                            session.stopLoading()
                            
                            // Use reflection to close the internal GeckoSession
                            // This ensures media playback stops immediately
                            val geckoEngineSession = session as? GeckoEngineSession
                            if (geckoEngineSession != null) {
                                val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
                                field.isAccessible = true
                                val internalSession = field.get(geckoEngineSession) as? GeckoSession
                                internalSession?.close()
                                Log.d(TAG, "Closed GeckoSession for tab $id")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing session for tab $id", e)
                        }
                    }
                    sessions.remove(id)
                }
            }
            
            val selectedTabId = browserState.selectedTabId
            val selectedTab = browserState.tabs.find { it.id == selectedTabId }
            val session = if (selectedTab != null) sessions[selectedTab.id] else null
            
            // State for download dialog
            data class PendingDownload(
                val url: String,
                val fileName: String? = null,
                val contentType: String? = null,
                val userAgent: String? = null,
                val cookie: String? = null,
                val referer: String? = null
            )
            var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
            var downloadDialogCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
            
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
            var browserCanGoBack by remember { mutableStateOf(false) }
            var browserCanGoForward by remember { mutableStateOf(false) }
            var menuExpanded by remember { mutableStateOf(false) }
            
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
            
            // Helper to get GeckoSession from EngineSession using reflection
            fun getGeckoSession(engineSession: EngineSession?): GeckoSession? {
                 if (engineSession == null) return null
                 val geckoEngineSession = engineSession as? GeckoEngineSession ?: return null
                 try {
                     val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
                     field.isAccessible = true
                     return field.get(geckoEngineSession) as? GeckoSession
                 } catch (e: Exception) {
                     Log.e(TAG, "Error accessing GeckoSession", e)
                     return null
                 }
            }

            // Find helper
            fun findInPage(text: String, direction: Int = 0) {
                 val geckoSession = getGeckoSession(session)
                 if (geckoSession != null) {
                     try {
                         // Use reflection to get constants to avoid unresolved references
                         val finderClass = Class.forName("org.mozilla.geckoview.GeckoSession\$Finder")
                         val findDisplayHighlights = finderClass.getField("FIND_DISPLAY_HIGHLIGHTS").getInt(null)
                         val findBackwards = finderClass.getField("FIND_BACKWARDS").getInt(null)
                         
                         val flags = if (direction == 0) {
                             findDisplayHighlights
                         } else {
                             findDisplayHighlights or findBackwards
                         }
                         
                         geckoSession.finder.find(text, flags)
                     } catch (e: Exception) {
                         Log.e(TAG, "Error finding in page", e)
                         // Fallback to finding without flags if reflection fails
                         try {
                              geckoSession.finder.find(text, 0)
                         } catch (e2: Exception) {}
                     }
                 }
            }
            
            // Clear finding when bar closes
            LaunchedEffect(showFindBar) {
                if (!showFindBar) {
                     val geckoSession = getGeckoSession(session)
                     geckoSession?.finder?.clear()
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
            
            if (contextMenuUrl != null) {
                Dialog(onDismissRequest = { contextMenuUrl = null }) {
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).width(IntrinsicSize.Max)) {
                            Text(
                                text = "Link Options",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            
                            Text(
                                text = contextMenuUrl!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 16.dp),
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )

                            // Play on TV
                            if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                FilledTonalButton(
                                    onClick = {
                                        val cmd = com.playbridge.protocol.createPlayCommandJson(url = contextMenuUrl!!)
                                        webSocketClient.send(cmd)
                                        Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                                        contextMenuUrl = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Play on TV")
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            // Open in new tab
                            OutlinedButton(
                                onClick = {
                                    val newTabId = UUID.randomUUID().toString()
                                    store.dispatch(TabListAction.AddTabAction(
                                        tab = TabSessionState(
                                            id = newTabId,
                                            content = ContentState(url = contextMenuUrl!!),
                                            parentId = null
                                        ),
                                        select = true
                                    ))
                                    Toast.makeText(this@BrowserActivity, "Opened in new tab", Toast.LENGTH_SHORT).show()
                                    contextMenuUrl = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open in new tab")
                            }
                            Spacer(Modifier.height(8.dp))

                            // Copy Link
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(contextMenuUrl!!))
                                    Toast.makeText(this@BrowserActivity, "Link copied", Toast.LENGTH_SHORT).show()
                                    contextMenuUrl = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copy Link")
                            }
                            
                            Spacer(Modifier.height(8.dp))
                            
                            // Cancel
                            TextButton(
                                onClick = { contextMenuUrl = null },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
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
                        
                        // Record history
                        // Record history
                        if (baseUrl != previousBaseUrl && !url.startsWith("about:")) {
                            scope.launch(Dispatchers.IO) {
                                val title = selectedTab?.content?.title
                                historyDao.insert(HistoryEntity(url = url, title = title, timestamp = System.currentTimeMillis()))
                            }
                        }
                        
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
                    override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
                        canGoBack?.let { browserCanGoBack = it }
                        canGoForward?.let { browserCanGoForward = it }
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
                        } else {
                            // General download interception
                             runOnUiThread {
                                 // Check if we already have a pending dialog for this URL to avoid double triggering
                                 // Check if we already have a pending dialog for this URL to avoid double triggering
                                 if (pendingDownload?.url != url) {
                                     pendingDownload = PendingDownload(
                                         url = url,
                                         fileName = fileName,
                                         contentType = contentType,
                                         userAgent = userAgent,
                                         cookie = cookie,
                                         referer = currentUrl
                                     )
                                     downloadDialogCallback = { _ -> }
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
                            // NavigationDelegate proxy restored for onNewSession
                            // We forward other calls (like onLoadRequest) to the original delegate to avoid download issues
                            val existingNav = gs.navigationDelegate
                            originalNavDelegate = existingNav
                            
                            val navProxy = java.lang.reflect.Proxy.newProxyInstance(
                                GeckoSession.NavigationDelegate::class.java.classLoader,
                                arrayOf(GeckoSession.NavigationDelegate::class.java)
                            ) { _, method, args ->
                                if (method.name == "onNewSession" && args != null && args.size >= 2) {
                                    val uri = args[1] as? String
                                    if (uri != null) {
                                        Log.d(TAG, "Opening new tab for: $uri")
                                        
                                        // We cannot return a pre-opened session (which Components.engine.createSession() likely provides).
                                        // Returning null declines the engine-level new window, but we manually create a new tab in our app state.
                                        // This achieves the desired "Open in New Tab" behavior without the crash.
                                        val newTabId = UUID.randomUUID().toString()
                                        
                                        runOnUiThread {
                                            store.dispatch(TabListAction.AddTabAction(
                                                tab = TabSessionState(
                                                    id = newTabId,
                                                    content = ContentState(url = uri),
                                                    parentId = selectedTab?.id
                                                ),
                                                select = true
                                            ))
                                        }
                                        return@newProxyInstance GeckoResult.fromValue(null)
                                    }

                                }
                                
                                // Forward to original delegate for everything else (including onLoadRequest)
                                if (existingNav != null) {
                                    try {
                                        if (args != null) method.invoke(existingNav, *args)
                                        else method.invoke(existingNav)
                                    } catch (e: java.lang.reflect.InvocationTargetException) {
                                        throw e.targetException
                                    }
                                } else {
                                    // Default return values if no original delegate
                                    null
                                }
                            } as GeckoSession.NavigationDelegate
                            gs.navigationDelegate = navProxy

                            
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
                                            }
                                            }
                                        )
                                        

                                    
                                    // Find on Page Bar
                                    if (showFindBar) {
                                        FindOnPageBar(
                                            onFind = { text -> findInPage(text) },
                                            onNext = { findInPage("", 0) },
                                            onPrev = { findInPage("", 1) },
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
                                    } else if ((targetState == Screen.Downloads || targetState == Screen.Extensions || targetState == Screen.Settings) && initialState == Screen.Browser) {
                                         slideInVertically { height -> height } + fadeIn() togetherWith
                                                slideOutVertically { height -> -height } + fadeOut()
                                    } else if (targetState == Screen.Browser && (initialState == Screen.Downloads || initialState == Screen.Extensions || initialState == Screen.Settings)) {
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
                                                onBack = { currentScreen = Screen.Browser },
                                                onAddExtension = {
                                                    val newId = UUID.randomUUID().toString()
                                                    store.dispatch(TabListAction.AddTabAction(
                                                        tab = TabSessionState(
                                                            id = newId,
                                                            content = ContentState(url = "https://addons.mozilla.org/android/"),
                                                            parentId = null
                                                        ),
                                                        select = true
                                                    ))
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
                if (pendingDownload != null) {
                    AlertDialog(
                        onDismissRequest = {
                            pendingDownload = null
                            downloadDialogCallback?.invoke(false)
                            downloadDialogCallback = null
                        },
                        title = { Text("Download file?") },
                        text = { 
                            Column {
                                Text("Do you want to download this file?")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = pendingDownload!!.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                pendingDownload!!.fileName?.let {
                                     Spacer(modifier = Modifier.height(4.dp))
                                     Text("File: $it", style = MaterialTheme.typography.bodySmall)
                                }
                                pendingDownload!!.contentType?.let {
                                     Spacer(modifier = Modifier.height(4.dp))
                                     Text("Type: $it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val download = pendingDownload!!
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
                                    downloadDialogCallback?.invoke(true)
                                    downloadDialogCallback = null
                                }
                            ) {
                                Text("Download")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    pendingDownload = null
                                    downloadDialogCallback?.invoke(false)
                                    downloadDialogCallback = null
                                }
                            ) {
                                Text("Cancel")
                            }
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
    object Connection : Screen()
    object Downloads : Screen()
    object Settings : Screen()
    object History : Screen()
}
