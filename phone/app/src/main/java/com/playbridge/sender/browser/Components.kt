package com.playbridge.sender.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.addons.amo.AMOAddonsProvider
import mozilla.components.feature.addons.update.AddonUpdater
import mozilla.components.lib.fetch.okhttp.OkHttpClient
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension as GeckoWebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Central dependency container for browser components.
 * Provides singletons for GeckoEngine, BrowserStore, and AddonManager.
 */
object Components {
    
    private const val TAG = "Components"
    private lateinit var appContext: Context
    
    // Reference to TabManager for resolving Kotlin tab IDs from extension messages
    var tabManager: TabManager? = null
    
    val applicationContext: Context
        get() = appContext
    
    // GeckoRuntime - the core Gecko engine
    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)
            .webManifest(true)
            .javaScriptEnabled(true)
            .remoteDebuggingEnabled(true)
            .consoleOutput(true)
            .build()
        GeckoRuntime.create(appContext, settings)
    }
    
    // GeckoEngine wrapper for Mozilla Components
    val engine: GeckoEngine by lazy {
        GeckoEngine(appContext, runtime = runtime)
    }
    
    // Central state store for tabs, sessions, etc.
    val store: BrowserStore by lazy {
        BrowserStore()
    }
    
    // HTTP client for addon downloads
    val client: Client by lazy {
        OkHttpClient()
    }
    
    // Addon collection provider - fetches available addons from AMO
    val addonsProvider: AMOAddonsProvider by lazy {
        AMOAddonsProvider(
            context = appContext,
            client = client,
            // Mozilla's recommended addons collection for Android
            collectionName = "7dfae8669acc4312a65e8ba5553036",
            maxCacheAgeInMinutes = 60 * 24 // Cache for 24 hours
        )
    }
    
    // Simple no-op addon updater
    private val noOpAddonUpdater = object : AddonUpdater {
        override fun registerForFutureUpdates(addonId: String) {}
        override fun unregisterForFutureUpdates(addonId: String) {}
        override fun update(addonId: String) {}
        override fun onUpdatePermissionRequest(
            extension: WebExtension,
            newPermissions: List<String>,
            newOrigins: List<String>,
            newDataCollectionPermissions: List<String>,
            onPermissionsGranted: (Boolean) -> Unit
        ) {
            // Auto-grant permissions for now (user can manage via ExtensionsScreen)
            onPermissionsGranted(true)
        }
    }
    
    // AddonManager - handles install/uninstall/enable/disable of extensions
    val addonManager: AddonManager by lazy {
        AddonManager(
            store = store,
            runtime = engine,
            addonsProvider = addonsProvider,
            addonUpdater = noOpAddonUpdater
        )
    }
    
    fun isEngineInitialized(): Boolean {
        return ::appContext.isInitialized
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        
        // Set up WebExtension prompt delegate to handle AMO installs
        runtime.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: GeckoWebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<GeckoWebExtension.PermissionPromptResponse>? {
                Log.d(TAG, "Extension install prompt request: ${extension.id}")
                Log.d(TAG, "Permissions: ${permissions.joinToString()}")
                // Auto-allow installation with all permissions granted
                // Constructor: (isPermissionsGranted, isPrivateModeGranted, isTechnicalAndInteractionDataGranted)
                return GeckoResult.fromValue(
                    GeckoWebExtension.PermissionPromptResponse(true, true, true)
                )
            }
            
            override fun onUpdatePrompt(
                extension: GeckoWebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>,
                newDataCollectionPermissions: Array<out String>
            ): GeckoResult<AllowOrDeny>? {
                Log.d(TAG, "Extension update prompt: ${extension.id}")
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
            
            override fun onOptionalPrompt(
                extension: GeckoWebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>
            ): GeckoResult<AllowOrDeny>? {
                Log.d(TAG, "Extension optional permissions prompt: ${extension.id}")
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }
        
        // Set up debug delegate
        runtime.webExtensionController.setDebuggerDelegate(object : WebExtensionController.DebuggerDelegate {
            override fun onExtensionListUpdated() {
                Log.d(TAG, "Extension list updated")
            }
        })
        
        Log.d(TAG, "Browser components initialized")
    }
    
    /**
     * Install the bundled video detector extension from assets.
     * Shows a toast when installation is complete.
     */
    fun installBundledExtension() {
        val extensionId = "video-detector@playbridge"
        val extensionUrl = "resource://android/assets/extensions/video_detector/"
        
        Log.i(TAG, "=== Installing bundled video detector extension ===")
        Log.i(TAG, "Extension ID: $extensionId")
        Log.i(TAG, "Extension URL: $extensionUrl")
        
        // Register a global message delegate on the WebExtensionController
        val globalMessageDelegate = object : GeckoWebExtension.MessageDelegate {
            override fun onConnect(port: GeckoWebExtension.Port) {
                Log.i(TAG, "=== PORT CONNECTED: ${port.name} ===")
                
                port.setDelegate(object : GeckoWebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: GeckoWebExtension.Port) {
                        Log.i(TAG, "=== PORT MESSAGE: $message ===")
                        processMessage(message)
                    }
                    
                    override fun onDisconnect(port: GeckoWebExtension.Port) {
                        Log.i(TAG, "Port disconnected: ${port.name}")
                    }
                })
            }
            
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: GeckoWebExtension.MessageSender
            ): GeckoResult<Any>? {
                Log.i(TAG, "=== NATIVE MESSAGE RECEIVED ===")
                Log.i(TAG, "From app: $nativeApp")
                Log.i(TAG, "Message: $message")
                Log.i(TAG, "Sender: ${sender.webExtension?.id}")
                
                processMessage(message)
                
                // Return a response to avoid "unexpected error"
                return GeckoResult.fromValue(mapOf("received" to true) as Any)
            }
        }
        
        // Use ensureBuiltIn for bundled extensions in assets
        runtime.webExtensionController.ensureBuiltIn(extensionUrl, extensionId).then { extension ->
            if (extension != null) {
                Log.i(TAG, "Video detector extension loaded successfully: ${extension.id}")
                
                // Store extension reference
                videoDetectorExtension = extension
                
                // Show toast on main thread
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        appContext,
                        "Video Detector extension installed!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
                // Set up message delegate on the extension to receive messages
                extension.setMessageDelegate(globalMessageDelegate, "browser")
                Log.i(TAG, "Message delegate registered on extension")
                
                // Connect to extension port for bidirectional messaging
                connectToExtension(extension)
            } else {
                Log.e(TAG, "ensureBuiltIn returned null extension")
            }
            
            GeckoResult.fromValue(extension)
        }.exceptionally { throwable ->
            Log.e(TAG, "Extension ensureBuiltIn FAILED", throwable)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    appContext,
                    "Extension install failed: ${throwable.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            GeckoResult.fromValue(null)
        }
    }
    
    // Store extension and port for later use
    private var videoDetectorExtension: GeckoWebExtension? = null
    private var extensionPort: GeckoWebExtension.Port? = null
    
    /**
     * Connect to extension for bidirectional messaging
     */
    private fun connectToExtension(extension: GeckoWebExtension) {
        Log.i(TAG, "Connecting to extension port...")
        
        // The native app can't directly call extension's background script
        // Instead, we receive messages when the extension sends them
        // Start polling for videos periodically
        startVideoPolling()
    }
    
    private var pollingHandler: Handler? = null
    private val pollingRunnable = object : Runnable {
        override fun run() {
            requestVideosFromExtension()
            pollingHandler?.postDelayed(this, 2000) // Poll every 2 seconds
        }
    }
    
    /**
     * Start polling for videos from extension storage
     */
    private fun startVideoPolling() {
        Log.i(TAG, "Starting video polling...")
        pollingHandler = Handler(Looper.getMainLooper())
        pollingHandler?.postDelayed(pollingRunnable, 1000)
    }
    
    /**
     * Request videos from extension - currently not possible to directly call
     * extension from native side, so we rely on extension pushing updates
     */
    private fun requestVideosFromExtension() {
        // GeckoView doesn't support native -> extension messaging directly
        // The extension must push updates via the port connection
        Log.d(TAG, "Polling for video updates...")
    }
    

    /**
     * Process incoming message from extension.
     * Resolves the Kotlin tab ID by matching the message's originUrl against
     * the currently open tabs in BrowserStore.
     */
    private fun processMessage(message: Any) {
        try {
            val jsonString = when (message) {
                is JSONObject -> message.toString()
                is String -> message
                else -> message.toString()
            }
            
            Log.i(TAG, "Processing message: $jsonString")
            
            val jsonObject = Json.parseToJsonElement(jsonString) as? JsonObject
            if (jsonObject != null) {
                val type = jsonObject["type"]?.jsonPrimitive?.content
                if (type == "http_error") {
                    val statusCode = jsonObject["statusCode"]?.jsonPrimitive?.content ?: "unknown"
                    val url = jsonObject["url"]?.jsonPrimitive?.content ?: "unknown"
                    val tabId = jsonObject["tabId"]?.jsonPrimitive?.content
                    Log.e(TAG, "HTTP ERROR detected via extension: $statusCode for $url (Tab: $tabId)")

                    Handler(Looper.getMainLooper()).post {
                        // Find the session for the tab and load error page
                        val sessionToLoad = if (tabId != null) {
                            // The extension tabId matches our session keys if it's the internal Gecko ID
                            // However, we need to map Gecko tabId back to our Kotlin UUID tabId
                            // For now, let's load it into the selected tab if it's the current one,
                            // or search for the matching session.
                            tabManager?.sessions?.get(resolveKotlinTabId(jsonObject))
                        } else {
                            tabManager?.sessions?.get(store.state.selectedTabId)
                        }

                        sessionToLoad?.loadUrl(ErrorPageUtils.generateErrorPage(url, statusCode))
                    }
                } else {
                    val kotlinTabId = resolveKotlinTabId(jsonObject)
                    VideoDetector.onMessageReceived(jsonObject, kotlinTabId)
                    Log.i(TAG, "Message sent to VideoDetector for tab $kotlinTabId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }
    
    /**
     * Resolve the Kotlin tab ID from a video detection message.
     * Matches the originUrl from the message against the URLs of currently open tabs.
     * Falls back to the selected tab, then "_unknown".
     */
    private fun resolveKotlinTabId(message: JsonObject): String {
        try {
            val originUrl = message["originUrl"]?.jsonPrimitive?.content
            if (!originUrl.isNullOrEmpty()) {
                val state = store.state
                // Try exact match first
                val matchedTab = state.tabs.find { tab ->
                    tab.content.url == originUrl ||
                    tab.content.url.substringBefore("#") == originUrl.substringBefore("#")
                }
                if (matchedTab != null) {
                    return matchedTab.id
                }
                
                // Try domain match as fallback
                val originDomain = try { java.net.URI(originUrl).host } catch (e: Exception) { null }
                if (originDomain != null) {
                    val domainMatch = state.tabs.find { tab ->
                        try { java.net.URI(tab.content.url).host == originDomain } catch (e: Exception) { false }
                    }
                    if (domainMatch != null) {
                        return domainMatch.id
                    }
                }
            }
            
            // Fallback: use the currently selected tab
            val selectedId = store.state.selectedTabId
            if (selectedId != null) {
                return selectedId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving Kotlin tab ID", e)
        }
        return "_unknown"
    }
    
    /**
     * Set up message delegate to receive messages from the extension's background script
     */
    private fun setupNativeMessaging(extension: GeckoWebExtension) {
        Log.i(TAG, "Setting up message delegate for ${extension.id}")
        
        val messageDelegate = object : GeckoWebExtension.MessageDelegate {
            override fun onConnect(port: GeckoWebExtension.Port) {
                Log.i(TAG, "Port connected from extension: ${port.name}")
                
                port.setDelegate(object : GeckoWebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: GeckoWebExtension.Port) {
                        Log.i(TAG, "Port message received: $message")
                        
                        try {
                            val jsonString = when (message) {
                                is JSONObject -> message.toString()
                                is String -> message
                                else -> message.toString()
                            }
                            
                            val jsonObject = Json.parseToJsonElement(jsonString) as? JsonObject
                            if (jsonObject != null) {
                                VideoDetector.onMessageReceived(jsonObject)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing port message", e)
                        }
                    }
                    
                    override fun onDisconnect(port: GeckoWebExtension.Port) {
                        Log.i(TAG, "Port disconnected")
                    }
                })
            }
            
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: GeckoWebExtension.MessageSender
            ): GeckoResult<Any>? {
                Log.i(TAG, "Direct message received from extension: $message")
                
                try {
                    val jsonString = when (message) {
                        is JSONObject -> message.toString()
                        is String -> message
                        else -> message.toString()
                    }
                    
                    val jsonObject = Json.parseToJsonElement(jsonString) as? JsonObject
                    if (jsonObject != null) {
                        VideoDetector.onMessageReceived(jsonObject)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message", e)
                }
                
                return null
            }
        }
        
        // Register delegate for background script messages
        extension.setMessageDelegate(messageDelegate, "browser")
        Log.i(TAG, "Message delegate registered for background script (port: browser)")
    }
}
