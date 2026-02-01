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

class BrowserActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "BrowserActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!Components.isEngineInitialized()) {
            Components.initialize(applicationContext)
        }

        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Browser) }
            
            // Session and navigation state
            val session = remember { Components.engine.createSession() }
            var currentUrl by remember { mutableStateOf("https://www.google.com") }
            var isLoading by remember { mutableStateOf(false) }
            var canGoBack by remember { mutableStateOf(false) }
            var canGoForward by remember { mutableStateOf(false) }
            var menuExpanded by remember { mutableStateOf(false) }
            
            // Register navigation observer with download interception
            DisposableEffect(session) {
                val observer = object : EngineSession.Observer {
                    override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                        currentUrl = url
                    }
                    override fun onLoadingStateChange(loading: Boolean) {
                        isLoading = loading
                    }
                    override fun onNavigationStateChange(canGoBackNow: Boolean?, canGoForwardNow: Boolean?) {
                        canGoBackNow?.let { canGoBack = it }
                        canGoForwardNow?.let { canGoForward = it }
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

            MaterialTheme {
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
                                        onUrlChange = { },
                                        onNavigate = { url -> session.loadUrl(url) },
                                        onBack = { session.goBack() },
                                        onForward = { session.goForward() },
                                        onRefresh = { session.reload() },
                                        onStop = { session.stopLoading() },
                                        onMenuClick = { menuExpanded = true }
                                    )
                                    
                                    // Dropdown menu
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        // Navigation buttons row
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceEvenly
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
                                                    if (isLoading) "Stop" else "Refresh"
                                                )
                                            }
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Tabs") },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                            onClick = {
                                                menuExpanded = false
                                                currentScreen = Screen.Tabs
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Extensions") },
                                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                                            onClick = {
                                                menuExpanded = false
                                                currentScreen = Screen.Extensions
                                            }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Install uBlock Origin") },
                                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                                            onClick = {
                                                menuExpanded = false
                                                // Open AMO page for uBlock Origin
                                                session.loadUrl("https://addons.mozilla.org/android/addon/ublock-origin/")
                                            }
                                        )
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
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.Browser -> BrowserView(session = session)
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
            }
        }
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
