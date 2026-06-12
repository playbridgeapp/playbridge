package com.playbridge.sender.browser
import com.playbridge.sender.cast.*

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import com.playbridge.shared.protocol.decodeVisualMetadataJson
import playbridge.PlayPayload
import playbridge.VisualMetadata
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
    
    /**
     * Process-wide TabManager singleton. Owning it here (instead of per-Activity)
     * means live EngineSessions survive Activity recreation (theme change, split
     * screen, system-initiated recreation) instead of being closed and rebuilt
     * from scratch — which previously lost history and produced blank tabs.
     */
    val tabManager: TabManager = TabManager()
    var onBridgeCastRequest: ((items: List<PlayPayload>, startIndex: Int, playlistMetadata: VisualMetadata?) -> Unit)? = null

    /**
     * Hooks for the engine-level [requestInterceptor] (set by SessionObserverSetup).
     * Magnet/Stremio links are intercepted for ALL tabs at the engine level —
     * previously this only worked on the selected tab via a delegate proxy.
     */
    @Volatile var onMagnetDetected: ((String) -> Unit)? = null
    @Volatile var onStremioAddonDetected: ((String) -> Unit)? = null

    /**
     * Engine-level request interceptor (Fenix-style): serves friendly error
     * pages on load failures and intercepts magnet:/stremio: scheme links.
     * Replaces the old per-session NavigationDelegate reflection proxy.
     */
    private val requestInterceptor = object : mozilla.components.concept.engine.request.RequestInterceptor {
        override fun onLoadRequest(
            engineSession: mozilla.components.concept.engine.EngineSession,
            uri: String,
            lastUri: String?,
            hasUserGesture: Boolean,
            isSameDomain: Boolean,
            isRedirect: Boolean,
            isDirectNavigation: Boolean,
            isSubframeRequest: Boolean,
        ): mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse? {
            if (uri.startsWith("magnet:?")) {
                Log.d(TAG, "Intercepted magnet link: $uri")
                Handler(Looper.getMainLooper()).post { onMagnetDetected?.invoke(uri) }
                return mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse.Deny
            }
            if (uri.startsWith("stremio://")) {
                Log.d(TAG, "Intercepted Stremio addon link: $uri")
                Handler(Looper.getMainLooper()).post { onStremioAddonDetected?.invoke(uri) }
                return mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse.Deny
            }
            return null
        }

        override fun onErrorRequest(
            session: mozilla.components.concept.engine.EngineSession,
            errorType: mozilla.components.browser.errorpages.ErrorType,
            uri: String?,
        ): mozilla.components.concept.engine.request.RequestInterceptor.ErrorResponse {
            Log.e(TAG, "Load error for $uri: $errorType")
            return mozilla.components.concept.engine.request.RequestInterceptor.ErrorResponse(
                ErrorPageUtils.generateErrorPage(uri ?: "unknown", "NETWORK_ERROR", errorType.name)
            )
        }
    }

    /**
     * Mirrors the "detect videos" setting. Detection messages from the extension
     * arrive over native messaging regardless of the setting, so they are gated
     * here. Kept in sync from BrowserActivity.
     */
    @Volatile
    var detectVideosEnabled: Boolean = true

    val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    
    val applicationContext: Context
        get() = appContext
    
    // GeckoRuntime - the core Gecko engine
    val runtime: GeckoRuntime by lazy {
        // BuildConfig generation is disabled by default on AGP 8+; the
        // debuggable flag is the dependency-free equivalent of BuildConfig.DEBUG.
        val isDebugBuild = (appContext.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)
            .webManifest(true)
            .javaScriptEnabled(true)
            // Remote debugging only in debug builds — it was previously
            // always-on, exposing a debugging surface in release builds.
            .remoteDebuggingEnabled(isDebugBuild)
            .consoleOutput(isDebugBuild)
            // Run extensions in their own process (Fenix does the same) so a
            // video-detector crash can't take web content down with it.
            .extensionsProcessEnabled(true)
            .extensionsWebAPIEnabled(true)
            .build()
        val r = GeckoRuntime.create(appContext, settings)
        // If the runtime dies (Gecko crash/exit), every session is dead and all
        // tabs would render as permanent white pages — GeckoView does NOTHING
        // by default when no delegate is set. Exit the process instead: the
        // next launch starts a fresh runtime and restores tabs from the DB.
        r.delegate = GeckoRuntime.Delegate {
            Log.e(TAG, "GeckoRuntime shut down — exiting process for a clean restart")
            kotlin.system.exitProcess(0)
        }
        r.warmUp()
        r
    }
    
    // GeckoEngine wrapper for Mozilla Components
    val engine: GeckoEngine by lazy {
        val isDarkMode = (appContext.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val defaultSettings = mozilla.components.concept.engine.DefaultSettings(
            // Tracking protection is OFF by default (pre-2026-06 behavior).
            // recommended() would also enable cookie isolation (dFPI) + purging,
            // which risks breaking embedded players on streaming sites — the
            // core PlayBridge use case. If TP is wanted, expose it as a user
            // setting with per-site exceptions rather than flipping the default.
            trackingProtectionPolicy = mozilla.components.concept.engine.EngineSession
                .TrackingProtectionPolicy.none(),
            // Paint the surface with a theme-appropriate color before first paint
            // instead of flashing white (a major contributor to perceived
            // "white blank page" moments, especially in dark mode).
            clearColor = if (isDarkMode) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt(),
            // Let websites see the system color scheme (dark mode sites).
            preferredColorScheme = mozilla.components.concept.engine.mediaquery.PreferredColorScheme.System,
            // Background tab playback is a core PlayBridge feature.
            suspendMediaWhenInactive = false,
            // Error pages + magnet:/stremio: scheme handling for all tabs.
            requestInterceptor = requestInterceptor,
        )
        GeckoEngine(appContext, defaultSettings = defaultSettings, runtime = runtime)
    }
    
    // Central state store for tabs, sessions, etc.
    // EngineMiddleware OWNS engine session lifecycle (Fenix-style): it creates
    // sessions on CreateEngineSessionAction (restoring state automatically),
    // suspends them on SuspendEngineSessionAction, closes them when tabs are
    // removed, syncs URL/title/back-forward/session-state into the store via
    // EngineObserver, and marks tabs crashed on content-process death.
    // trimMemoryAutomatically=false matches Fenix: rely on GeckoView/OS for
    // memory pressure instead of suspending sessions behind the user's back.
    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = mozilla.components.browser.state.engine.EngineMiddleware.create(
                engine = engine,
                trimMemoryAutomatically = false,
            )
        )
    }
    
    // Session use cases (goBack/goForward/loadUrl/reload via the store; used by
    // SessionFeature rendering and anywhere a tab might not have a live session).
    val sessionUseCases by lazy {
        mozilla.components.feature.session.SessionUseCases(store)
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

        // Start mirroring store state (engine sessions, nav state) into
        // TabManager and ensuring the selected tab has a live session.
        tabManager.start(store)

        // Configure Coil with explicit memory + disk cache so poster images survive
        // screen rotations (memory) and app restarts (disk) without re-downloading.
        Coil.setImageLoader(
            ImageLoader.Builder(appContext)
                .memoryCache {
                    MemoryCache.Builder(appContext)
                        .maxSizePercent(0.20) // 20% of available RAM
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(appContext.cacheDir.resolve("tmdb_image_cache"))
                        .maxSizeBytes(150L * 1024 * 1024) // 150 MB
                        .build()
                }
                .crossfade(true)
                .build()
        )
        
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
                        
                        // Send feedback back to the extension
                        try {
                            val feedback = org.json.JSONObject().apply {
                                put("type", "feedback")
                                put("status", "received")
                            }
                            port.postMessage(feedback)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send feedback message", e)
                        }
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
        Handler(Looper.getMainLooper()).post {
            runtime.webExtensionController.ensureBuiltIn(extensionUrl, extensionId).then { extension ->
                if (extension != null) {
                    Log.i(TAG, "Video detector extension loaded successfully: ${extension.id}")

                    // Store extension reference
                    videoDetectorExtension = extension

                    // Set up message delegate on the extension instance to receive messages
                    extension.setMessageDelegate(globalMessageDelegate, "browser")
                    Log.i(TAG, "Message delegate registered on Extension instance: ${extension.id}")
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
    }
    
    // Store extension reference for later use
    private var videoDetectorExtension: GeckoWebExtension? = null

    // NOTE: the old 2-second "video polling" Handler was removed — it was a
    // no-op (native→extension messaging isn't possible; the extension pushes
    // detections itself) that ran forever, including in the background.

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
                            tabManager.sessions[resolveKotlinTabId(jsonObject)]
                        } else {
                            store.state.selectedTabId?.let { tabManager.sessions[it] }
                        }

                        sessionToLoad?.loadUrl(ErrorPageUtils.generateErrorPage(url, statusCode))
                    }
                } else if (type == "cast") {
                    val itemsJson = jsonObject["items"]?.jsonArray
                    val startIndex = jsonObject["startIndex"]?.jsonPrimitive?.int ?: 0
                    val playlistMetadata = jsonObject["metadata"]?.let { decodeVisualMetadataJson(it.toString()) }
                    if (itemsJson != null) {
                        val items = itemsJson.mapNotNull { item ->
                            val obj = item as? JsonObject ?: return@mapNotNull null
                            val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val title = obj["title"]?.jsonPrimitive?.content

                            val metadata = obj["metadata"]?.let { decodeVisualMetadataJson(it.toString()) }

                            PlayPayload(url = url, title = title, visual_metadata = metadata)
                        }
                        if (items.isNotEmpty()) {
                            Log.i(TAG, "CAST MESSAGE received via extension: ${items.size} items, startIndex: $startIndex")
                            onBridgeCastRequest?.invoke(items, startIndex, playlistMetadata)
                        }
                    } else {
                        // Fallback for legacy single item
                        val url = jsonObject["url"]?.jsonPrimitive?.content
                        val title = jsonObject["title"]?.jsonPrimitive?.content
                        if (url != null) {
                            Log.i(TAG, "CAST MESSAGE received via extension (legacy): $url")
                            onBridgeCastRequest?.invoke(listOf(PlayPayload(url = url, title = title)), 0, null)
                        }
                    }
                } else if (type == "navigation") {
                    // Top-level navigation (including reloads) committed in a tab:
                    // the extension has cleared its per-tab detection state and
                    // will re-report videos as the new page loads — reset ours too
                    // so the cast sheet count starts from 0.
                    val kotlinTabId = resolveKotlinTabId(jsonObject)
                    VideoDetector.clearTab(kotlinTabId)
                    Log.d(TAG, "Navigation committed — cleared detected videos for tab $kotlinTabId")
                } else if (type == "video_detected" && !detectVideosEnabled) {
                    Log.d(TAG, "Video detection disabled — ignoring detection message")
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
                // Since EngineMiddleware, ALL tabs have live URLs in the store —
                // multiple tabs can share a URL/domain. Prefer the selected tab
                // when it matches, so detections aren't misfiled to a background
                // tab and the cast sheet (keyed by selected tab) misses them.
                val selectedTab = state.selectedTabId?.let { id -> state.tabs.find { it.id == id } }
                fun urlMatches(tabUrl: String) =
                    tabUrl == originUrl || tabUrl.substringBefore("#") == originUrl.substringBefore("#")

                if (selectedTab != null && urlMatches(selectedTab.content.url)) {
                    return selectedTab.id
                }
                val matchedTab = state.tabs.find { urlMatches(it.content.url) }
                if (matchedTab != null) {
                    return matchedTab.id
                }

                // Try domain match as fallback — selected tab first
                val originDomain = try { java.net.URI(originUrl).host } catch (e: Exception) { null }
                if (originDomain != null) {
                    fun domainMatches(tabUrl: String) =
                        try { java.net.URI(tabUrl).host == originDomain } catch (e: Exception) { false }

                    if (selectedTab != null && domainMatches(selectedTab.content.url)) {
                        return selectedTab.id
                    }
                    val domainMatch = state.tabs.find { domainMatches(it.content.url) }
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
    
}
