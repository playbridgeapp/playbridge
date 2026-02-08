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
import com.playbridge.sender.model.QRCodeData
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.QRScannerScreen
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
            
            // Session and navigation state
            val session = remember { Components.engine.createSession() }
            var currentUrl by remember { mutableStateOf("https://www.google.com") }
            var isLoading by remember { mutableStateOf(false) }
            var canGoBack by remember { mutableStateOf(false) }
            var canGoForward by remember { mutableStateOf(false) }
            var menuExpanded by remember { mutableStateOf(false) }
            
            // View state - browser or scanner
            var showScanner by remember { mutableStateOf(false) }
            
            // Back press handling
            var backPressedTime by remember { mutableLongStateOf(0L) }
            
            // Video detection state
            var showVideoSheet by remember { mutableStateOf(false) }
            val detectedVideos = VideoDetector.detectedVideos
            // Read size reactively to trigger recomposition when videos are added
            val videoCount by remember { derivedStateOf { detectedVideos.size } }
            
            // Track if media is actively playing on TV
            var isMediaPlaying by remember { mutableStateOf(false) }
            
            // Track previous URL to avoid clearing on hash changes
            var previousUrl by remember { mutableStateOf("") }
            
            // Register navigation observer with download interception
            DisposableEffect(session) {
                val observer = object : EngineSession.Observer {
                    override fun onLocationChange(url: String, hasUserGesture: Boolean) {
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
                                
                                Box(modifier = Modifier.fillMaxSize()) {
                                    BrowserView(session = session)
                                    

                                }
                            }
                            Screen.Tabs -> {
                                BackHandler { currentScreen = Screen.Browser }
                                TabsScreen(
                                    onTabSelected = { currentScreen = Screen.Browser },
                                    onTabClosed = { /* TODO */ }
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
    object Scanner : Screen()
}
