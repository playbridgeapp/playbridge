package com.playbridge.sender.browser

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.engine.gecko.GeckoEngineView
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.fetch.Response

import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.ui.theme.PlayBridgeTheme

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
            
            // Auto-connect to stored TV device
            LaunchedEffect(Unit) {
                connectionStore.tvDevice.collect { device ->
                    if (device != null && connectionState is WebSocketClient.ConnectionState.Disconnected) {
                        Log.d(TAG, "Auto-connecting to TV: ${device.name} at ${device.ip}:${device.port}")
                        webSocketClient.connect(device.ip, device.port, device.token, device.name)
                    }
                }
            }
            
            // Session and navigation state
            val session = remember { Components.engine.createSession() }
            var currentUrl by remember { mutableStateOf("https://www.google.com") }
            var isLoading by remember { mutableStateOf(false) }
            var canGoBack by remember { mutableStateOf(false) }
            var canGoForward by remember { mutableStateOf(false) }
            var menuExpanded by remember { mutableStateOf(false) }
            
            // Video detection state
            var showVideoSheet by remember { mutableStateOf(false) }
            val detectedVideos = VideoDetector.detectedVideos
            // Read size reactively to trigger recomposition when videos are added
            val videoCount by remember { derivedStateOf { detectedVideos.size } }
            
            // Register navigation observer with download interception
            DisposableEffect(session) {
                val observer = object : EngineSession.Observer {
                    override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                        currentUrl = url
                        
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
                    }
                    override fun onNavigationStateChange(canGoBackNow: Boolean?, canGoForwardNow: Boolean?) {
                        canGoBackNow?.let { canGoBack = it }
                        canGoForwardNow?.let { canGoForward = it }
                    }
                    
                    // Detect video count from page title [PlayBridge:X] marker
                    override fun onTitleChange(title: String) {
                        Log.d(TAG, "Title changed: $title")
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
                session.loadUrl("https://www.google.com")
                
                onDispose {
                    session.unregister(observer)
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
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                DropdownMenuItem(
                                                    text = { Text("Install uBlock Origin", style = MaterialTheme.typography.bodyLarge) },
                                                    leadingIcon = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        // Open AMO page for uBlock Origin
                                                        session.loadUrl("https://addons.mozilla.org/android/addon/ublock-origin/")
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                                )
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
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.Browser -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    BrowserView(session = session)
                                    

                                }
                            }
                            Screen.Tabs -> TabsScreen(
                                onTabSelected = { currentScreen = Screen.Browser },
                                onTabClosed = { /* TODO */ }
                            )
                            Screen.Extensions -> ExtensionsScreen(
                                onBack = { currentScreen = Screen.Browser }
                            )
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
                                    val commandJson = com.playbridge.sender.model.createPlayCommandJson(
                                        url = video.url,
                                        title = "Video from browser"
                                    )
                                    Log.d(TAG, "Sending play command: $commandJson")
                                    val sent = webSocketClient.send(commandJson)
                                    Log.d(TAG, "Command sent: $sent")
                                    
                                    if (sent) {
                                        Toast.makeText(
                                            this@BrowserActivity,
                                            "Playing on ${state.serverName}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Log.e(TAG, "Failed to send command")
                                        Toast.makeText(
                                            this@BrowserActivity,
                                            "Failed to send to TV",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                is WebSocketClient.ConnectionState.Disconnected -> {
                                    Log.e(TAG, "Not connected to TV")
                                    Toast.makeText(
                                        this@BrowserActivity,
                                        "Not connected to TV. Please connect from home screen.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                is WebSocketClient.ConnectionState.Connecting -> {
                                    Log.w(TAG, "Still connecting to TV")
                                    Toast.makeText(
                                        this@BrowserActivity,
                                        "Connecting to TV...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                is WebSocketClient.ConnectionState.Error -> {
                                    Log.e(TAG, "Connection error: ${state.message}")
                                    Toast.makeText(
                                        this@BrowserActivity,
                                        "Connection error: ${state.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onClear = {
                            VideoDetector.clear()
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
    fun BrowserView(session: EngineSession) {
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
    }
}

sealed class Screen {
    object Browser : Screen()
    object Tabs : Screen()
    object Extensions : Screen()
}
