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
import com.playbridge.sender.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import com.playbridge.shared.network.MediaNetworkPolicy
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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

internal class OwnedCallbackGate {
    private var owner: Any? = null

    @Synchronized
    fun claim(newOwner: Any) {
        owner = newOwner
    }

    @Synchronized
    fun release(releasingOwner: Any): Boolean {
        if (owner !== releasingOwner) return false
        owner = null
        return true
    }
}

/**
 * Central dependency container for browser components.
 * Provides singletons for GeckoEngine, BrowserStore, and AddonManager.
 */
object Components {
    
    private const val TAG = "Components"
    private lateinit var appContext: Context

    /** Detector native traffic may include media URLs and headers — debug builds only. */
    private fun debugDetectorLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
    
    /**
     * Process-wide TabManager singleton. Owning it here (instead of per-Activity)
     * means live EngineSessions survive Activity recreation (theme change, split
     * screen, system-initiated recreation) instead of being closed and rebuilt
     * from scratch — which previously lost history and produced blank tabs.
     */
    val tabManager: TabManager = TabManager()
    var onBridgeCastRequest: ((items: List<PlayPayload>, startIndex: Int, playlistMetadata: VisualMetadata?, skipPreplay: Boolean, origin: String, tabId: Int, navigationGeneration: Long, requestedPrivateOrigins: List<String>) -> Unit)? = null
    var onLinkedCastRequest: ((JSONObject) -> Unit)? = null
    var onPageNavigation: ((tabId: Int, navigationGeneration: Long) -> Unit)? = null
    var onLinkedNativePortDisconnected: (() -> Unit)? = null
    var onLinkedDevicePickerDismissed: (() -> Unit)? = null
    var onLinkedDevicePicked: ((com.playbridge.sender.model.TvDevice) -> Unit)? = null
    private val pageCastCallbackGate = OwnedCallbackGate()
    private val pageNavigationGenerations = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    val linkedDevicePickerRequests = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var linkedNativePort: GeckoWebExtension.Port? = null

    fun claimPageCastCallbacks(owner: Any) {
        pageCastCallbackGate.claim(owner)
        clearPageCastCallbackValues()
    }

    fun clearPageCastCallbacks(owner: Any) {
        if (!pageCastCallbackGate.release(owner)) return
        clearPageCastCallbackValues()
    }

    private fun clearPageCastCallbackValues() {
        onBridgeCastRequest = null
        onLinkedCastRequest = null
        onPageNavigation = null
        onLinkedNativePortDisconnected = null
        onLinkedDevicePickerDismissed = null
        onLinkedDevicePicked = null
    }

    fun isCurrentPageNavigation(tabId: Int, navigationGeneration: Long): Boolean =
        pageNavigationGenerations[tabId] == navigationGeneration

    fun postLinkedMessage(message: JSONObject) {
        Handler(Looper.getMainLooper()).post {
            val port = linkedNativePort
            if (port == null) {
                Log.w(TAG, "Dropping linked-cast native message because the extension port is disconnected")
                return@post
            }
            runCatching { port.postMessage(message) }
                .onFailure { Log.w(TAG, "Could not post linked-cast native event: ${it.message}") }
        }
    }

    fun requestLinkedDevicePicker() {
        linkedDevicePickerRequests.value += 1L
    }

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
    
    /**
     * Writes a GeckoView startup config (read via configFilePath, which works in
     * release builds too — see Mozilla's automation docs) that caps the HTTP disk
     * cache. By default Gecko "smart sizes" the cache off free disk space, which
     * on a roomy phone lets it balloon to 600+ MB of media fragments and web
     * assets. Capping at 200 MB (the ceiling Firefox itself uses on Android)
     * with LRU eviction keeps hot assets fast without unbounded growth.
     */
    private fun writeGeckoConfig(): String? = try {
        val file = java.io.File(appContext.filesDir, "geckoview-config.yaml")
        val yaml = """
            |prefs:
            |  browser.cache.disk.smart_size.enabled: false
            |  browser.cache.disk.capacity: 204800
            |""".trimMargin() // capacity is in KB → 200 MB
        // Avoid rewriting on every start; only touch the file when content changes.
        if (!file.exists() || file.readText() != yaml) file.writeText(yaml)
        file.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "Failed to write gecko config; cache cap disabled", e)
        null
    }

    // GeckoRuntime - the core Gecko engine
    val runtime: GeckoRuntime by lazy {
        // BuildConfig generation is disabled by default on AGP 8+; the
        // debuggable flag is the dependency-free equivalent of BuildConfig.DEBUG.
        val isDebugBuild = (appContext.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)
            .apply { writeGeckoConfig()?.let { configFilePath(it) } }
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
                debugDetectorLog("PORT CONNECTED: ${port.name}")
                linkedNativePort = port
                
                port.setDelegate(object : GeckoWebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: GeckoWebExtension.Port) {
                        debugDetectorLog("PORT MESSAGE: $message")
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
                        debugDetectorLog("Port disconnected: ${port.name}")
                        if (linkedNativePort === port) {
                            linkedNativePort = null
                            Handler(Looper.getMainLooper()).post { onLinkedNativePortDisconnected?.invoke() }
                        }
                    }
                })
            }
            
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: GeckoWebExtension.MessageSender
            ): GeckoResult<Any>? {
                debugDetectorLog(
                    "NATIVE MESSAGE from=$nativeApp sender=${sender.webExtension?.id} body=$message",
                )
                
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

                    // Set up message delegate on the extension instance to receive messages on the UI thread
                    Handler(Looper.getMainLooper()).post {
                        extension.setMessageDelegate(globalMessageDelegate, "browser")
                        Log.i(TAG, "Message delegate registered on Extension instance: ${extension.id}")
                    }
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

            // uBlock Origin: real AMO install (signed store build) instead of the old
            // bundled ensureBuiltIn copy. A store install starts with fresh filter
            // lists, keeps regional/locale list selection intact, exposes the full
            // dashboard, and can actually update — we trigger the update check
            // ourselves since GeckoView has no background extension updater.
            installOrUpdateUblock()
        }
    }
    
    // ── uBlock Origin (AMO install) ──────────────────────────────────────────

    private const val UBLOCK_ID = "uBlock0@raymondhill.net"
    // AMO's stable "latest signed XPI" redirect for uBlock Origin.
    private const val UBLOCK_AMO_XPI =
        "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private const val UBLOCK_UPDATE_CHECK_PREF = "ublock_last_update_check"
    private const val UBLOCK_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /**
     * Ensure uBlock Origin is installed as a real AMO install and kept current.
     * Runs on the main looper (posted by [installBundledExtension]) like every
     * other webExtensionController call in this file. Three cases:
     *
     *  - not installed → install from AMO (an offline first launch simply retries
     *    on the next startup, since this runs every launch);
     *  - legacy bundled built-in copy present → uninstall it once, then AMO-install
     *    (the built-in froze filter lists at build time and stripped locale data,
     *    which is why it blocked worse than a store install);
     *  - AMO copy present → trigger a signed update check via its AMO update_url,
     *    throttled to once a day (GeckoView never updates extensions on its own).
     */
    private fun installOrUpdateUblock() {
        val controller = runtime.webExtensionController
        controller.list().then({ extensions ->
            val existing = extensions?.firstOrNull { it.id == UBLOCK_ID }
            when {
                existing == null -> installUblockFromAmo(reason = "fresh install")
                existing.isBuiltIn -> {
                    Log.i(TAG, "Migrating uBlock Origin: bundled built-in → AMO install")
                    controller.uninstall(existing).then({
                        installUblockFromAmo(reason = "migration from built-in")
                        GeckoResult.fromValue(null)
                    }, { throwable ->
                        // Keep the bundled copy working rather than ending up with none.
                        Log.e(TAG, "Failed to remove built-in uBlock; keeping bundled copy", throwable)
                        GeckoResult.fromValue(null)
                    })
                }
                else -> maybeCheckUblockUpdate(existing)
            }
            GeckoResult.fromValue(null)
        }, { throwable ->
            Log.e(TAG, "webExtensionController.list() failed; skipping uBlock setup", throwable)
            GeckoResult.fromValue(null)
        })
    }

    private fun installUblockFromAmo(reason: String) {
        Log.i(TAG, "Installing uBlock Origin from AMO ($reason)…")
        runtime.webExtensionController
            .install(UBLOCK_AMO_XPI, WebExtensionController.INSTALLATION_METHOD_MANAGER)
            .then({ extension ->
                Log.i(TAG, "uBlock Origin ${extension?.metaData?.version} installed from AMO ($reason)")
                GeckoResult.fromValue(null)
            }, { throwable ->
                Log.e(TAG, "uBlock Origin AMO install failed ($reason) — will retry next launch", throwable)
                GeckoResult.fromValue(null)
            })
    }

    private fun maybeCheckUblockUpdate(extension: GeckoWebExtension) {
        val prefs = appContext.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(UBLOCK_UPDATE_CHECK_PREF, 0L) < UBLOCK_UPDATE_INTERVAL_MS) return
        prefs.edit().putLong(UBLOCK_UPDATE_CHECK_PREF, now).apply()
        runtime.webExtensionController.update(extension).then({ updated ->
            if (updated != null) {
                Log.i(TAG, "uBlock Origin updated to ${updated.metaData?.version}")
            } else {
                Log.d(TAG, "uBlock Origin is up to date")
            }
            GeckoResult.fromValue(null)
        }, { throwable ->
            Log.w(TAG, "uBlock Origin update check failed", throwable)
            GeckoResult.fromValue(null)
        })
    }

    // Store extension reference for later use
    private var videoDetectorExtension: GeckoWebExtension? = null
    private val detectorTabBindingTracker = DetectorTabBindingTracker()

    // NOTE: the old 2-second "video polling" Handler was removed — it was a
    // no-op (native→extension messaging isn't possible; the extension pushes
    // detections itself) that ran forever, including in the background.

    /**
     * Process incoming message from extension.
     * Resolves the Kotlin tab through an epoch-scoped WebExtension tab binding,
     * bootstrapped only by exact current/previous document URL matches.
     */
    private fun processMessage(message: Any, resolutionAttempt: Int = 0) {
        try {
            val jsonString = when (message) {
                is JSONObject -> message.toString()
                is String -> message
                else -> message.toString()
            }
            
            // Full payload can include authenticated media URLs and header maps.
            debugDetectorLog("Processing detector message: $jsonString")
            
            val jsonObject = Json.parseToJsonElement(jsonString) as? JsonObject
            if (jsonObject != null) {
                val type = jsonObject["type"]?.jsonPrimitive?.content
                if (type?.startsWith("linked_") == true) {
                    if (jsonString.toByteArray().size > PAGE_CAST_REQUEST_BYTES) return
                    if (type == "linked_open") {
                        val detectorTabId = jsonObject["tabId"]?.jsonPrimitive?.intOrNull
                        val generation =
                            jsonObject["navigationGeneration"]?.jsonPrimitive?.longOrNull
                        if (detectorTabId != null && generation != null) {
                            pageNavigationGenerations.putIfAbsent(detectorTabId, generation)
                        }
                    }
                    Handler(Looper.getMainLooper()).post {
                        onLinkedCastRequest?.invoke(JSONObject(jsonString))
                    }
                } else if (type == "detector_hello") {
                    debugDetectorLog(
                        "Video detector native channel ready " +
                            "epoch=${jsonObject["detectorEpoch"]?.jsonPrimitive?.longOrNull ?: "legacy"}",
                    )
                } else if (type == "detector_tab_closed") {
                    val detectorEpoch = jsonObject["detectorEpoch"]?.jsonPrimitive?.longOrNull
                    val detectorTabId = jsonObject["tabId"]?.jsonPrimitive?.intOrNull
                    if (detectorEpoch != null && detectorTabId != null) {
                        detectorTabBindingTracker.forgetDetectorTab(detectorEpoch, detectorTabId)
                        pageNavigationGenerations[detectorTabId] = Long.MAX_VALUE
                        Handler(Looper.getMainLooper()).post {
                            onPageNavigation?.invoke(detectorTabId, Long.MAX_VALUE)
                        }
                    }
                } else if (type == "http_error") {
                    val statusCode = jsonObject["statusCode"]?.jsonPrimitive?.content ?: "unknown"
                    val url = jsonObject["url"]?.jsonPrimitive?.content ?: "unknown"
                    val tabId = jsonObject["tabId"]?.jsonPrimitive?.content
                    // Status-only in release; full URL only in debug builds.
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "HTTP ERROR detected via extension: $statusCode for $url (Tab: $tabId)")
                    } else {
                        Log.e(TAG, "HTTP ERROR detected via extension: $statusCode (Tab: $tabId)")
                    }

                    val kotlinTabId = resolveKotlinTabId(jsonObject)
                    if (kotlinTabId == null) {
                        retryUnresolvedDetectorMessage(jsonString, resolutionAttempt, type)
                        return
                    }
                    Handler(Looper.getMainLooper()).post {
                        // Find the session for the tab and load error page
                        val sessionToLoad = tabManager.sessions[kotlinTabId]
                        sessionToLoad?.loadUrl(ErrorPageUtils.generateErrorPage(url, statusCode))
                    }
                } else if (type == "cast") {
                    if (jsonString.toByteArray().size > PAGE_CAST_REQUEST_BYTES) return
                    val origin = PageCastConsentStore.normalizeOrigin(
                        jsonObject["origin"]?.jsonPrimitive?.contentOrNull,
                    )
                    if (origin == null) {
                        Log.w(TAG, "Ignoring page cast request without a valid origin")
                        return
                    }
                    val tabId = jsonObject["tabId"]?.jsonPrimitive?.intOrNull ?: return
                    val navigationGeneration =
                        jsonObject["navigationGeneration"]?.jsonPrimitive?.longOrNull ?: return
                    pageNavigationGenerations.putIfAbsent(tabId, navigationGeneration)
                    if (!isCurrentPageNavigation(tabId, navigationGeneration)) return
                    val itemsJson = jsonObject["items"]?.jsonArray
                    if (itemsJson != null && itemsJson.size !in 1..PAGE_CAST_MAX_ITEMS) return
                    val startIndex = jsonObject["startIndex"]?.jsonPrimitive?.int ?: 0
                    val playlistMetadata = jsonObject["metadata"]?.let {
                        if (it.toString().toByteArray().size > PAGE_CAST_METADATA_BYTES) return
                        decodeVisualMetadataJson(it.toString())
                    }
                    val skipPreplayElement = jsonObject["skipPreplay"]
                    val skipPreplay = skipPreplayElement?.jsonPrimitive?.booleanOrNull
                    if (skipPreplayElement != null && skipPreplay == null) return
                    if (itemsJson != null) {
                        val items = itemsJson.mapNotNull { item ->
                            val obj = item as? JsonObject ?: return@mapNotNull null
                            val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            if (!MediaNetworkPolicy.isHttpUrl(url)) return@mapNotNull null
                            val title = obj["title"]?.jsonPrimitive?.content
                            if (title != null && title.length > 4_096) return@mapNotNull null
                            val contentType = obj["contentType"]?.jsonPrimitive?.contentOrNull
                            if (contentType != null && contentType.length > 256) return@mapNotNull null
                            val mediaKind = obj["mediaKind"]?.jsonPrimitive?.contentOrNull
                            if (mediaKind != null && mediaKind !in setOf("video", "audio", "image")) {
                                return@mapNotNull null
                            }
                            val displayDurationMs = obj["displayDurationMs"]?.jsonPrimitive?.longOrNull
                            if (displayDurationMs != null && displayDurationMs < 0L) return@mapNotNull null
                            val headers = parsePageCastHeaders(obj["headers"] as? JsonObject)
                                ?: return@mapNotNull null
                            val subtitles = obj["subtitles"]?.jsonArray?.mapNotNull { value ->
                                value.jsonPrimitive.contentOrNull
                            }.orEmpty()
                            if (subtitles.size > PAGE_CAST_MAX_SUBTITLES ||
                                subtitles.any { !MediaNetworkPolicy.isHttpUrl(it) }
                            ) return@mapNotNull null
                            val subtitleResourcesJson = obj["subtitleResources"]?.jsonArray
                            if ((subtitleResourcesJson?.size ?: 0) > PAGE_CAST_MAX_SUBTITLES) {
                                return@mapNotNull null
                            }
                            val subtitleResources = subtitleResourcesJson?.mapNotNull { value ->
                                val resource = value as? JsonObject ?: return@mapNotNull null
                                val subtitleUrl = resource["url"]?.jsonPrimitive?.contentOrNull
                                    ?: return@mapNotNull null
                                if (!MediaNetworkPolicy.isHttpUrl(subtitleUrl)) return@mapNotNull null
                                val resourceHeaders = parsePageCastHeaders(resource["headers"] as? JsonObject)
                                    ?: return@mapNotNull null
                                val label = resource["label"]?.jsonPrimitive?.contentOrNull
                                val language = resource["language"]?.jsonPrimitive?.contentOrNull
                                if ((label?.length ?: 0) > 256 || (language?.length ?: 0) > 64) {
                                    return@mapNotNull null
                                }
                                playbridge.SubtitleResource(subtitleUrl, resourceHeaders, label, language)
                            }.orEmpty()
                            if (subtitleResources.size != (subtitleResourcesJson?.size ?: 0)) {
                                return@mapNotNull null
                            }
                            if (subtitles.size + subtitleResources.size > PAGE_CAST_MAX_SUBTITLES) {
                                return@mapNotNull null
                            }

                            val metadata = obj["metadata"]?.let {
                                if (it.toString().toByteArray().size > PAGE_CAST_METADATA_BYTES) {
                                    return@mapNotNull null
                                }
                                decodeVisualMetadataJson(it.toString())
                            }

                            PlayPayload(
                                url = url,
                                title = title,
                                headers = headers,
                                content_type = contentType,
                                media_kind = mediaKind,
                                display_duration_ms = displayDurationMs,
                                subtitles = subtitles,
                                subtitle_resources = subtitleResources,
                                detected_by = "page_cast",
                                visual_metadata = metadata,
                            )
                        }
                        if (items.isNotEmpty() && items.size == itemsJson.size && startIndex in items.indices) {
                            debugDetectorLog(
                                "CAST MESSAGE received via extension: ${items.size} items, " +
                                    "startIndex: $startIndex",
                            )
                            val requestedPrivateOrigins = jsonObject["privateNetworkOrigins"]?.jsonArray
                                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                                .orEmpty()
                            val normalizedPrivateOrigins =
                                MediaNetworkPolicy.normalizePrivateOrigins(requestedPrivateOrigins)
                                    ?: return
                            onBridgeCastRequest?.invoke(
                                items,
                                startIndex,
                                playlistMetadata,
                                skipPreplay == true,
                                origin,
                                tabId,
                                navigationGeneration,
                                normalizedPrivateOrigins.toList(),
                            )
                        }
                    } else {
                        // Fallback for legacy single item
                        val url = jsonObject["url"]?.jsonPrimitive?.content
                        val title = jsonObject["title"]?.jsonPrimitive?.content
                        if (url != null && MediaNetworkPolicy.isHttpUrl(url)) {
                            debugDetectorLog("CAST MESSAGE received via extension (legacy): $url")
                            onBridgeCastRequest?.invoke(
                                listOf(PlayPayload(url = url, title = title, detected_by = "page_cast")),
                                0,
                                null,
                                false,
                                origin,
                                tabId,
                                navigationGeneration,
                                emptyList(),
                            )
                        }
                    }
                } else if (type == "navigation") {
                    val detectorTabId = jsonObject["tabId"]?.jsonPrimitive?.intOrNull
                    val navigationGeneration =
                        jsonObject["navigationGeneration"]?.jsonPrimitive?.longOrNull
                    if (detectorTabId != null && navigationGeneration != null) {
                        pageNavigationGenerations[detectorTabId] = navigationGeneration
                        Handler(Looper.getMainLooper()).post {
                            onPageNavigation?.invoke(detectorTabId, navigationGeneration)
                        }
                    }
                    val kotlinTabId = resolveKotlinTabId(jsonObject)
                    if (kotlinTabId == null) {
                        retryUnresolvedDetectorMessage(jsonString, resolutionAttempt, type)
                        return
                    }
                    val version = detectorPageVersion(jsonObject)
                    val transitionType = jsonObject["transitionType"]?.jsonPrimitive?.contentOrNull
                    if (transitionType == "same_document") {
                        // SPA view change: rows are kept, but the tab's media lifecycle
                        // advances so ranking prefers the view the user navigated to.
                        if (version != null) {
                            val accepted = VideoDetector.onSameDocumentNavigation(
                                kotlinTabId,
                                version,
                                jsonObject["timestamp"]?.jsonPrimitive?.longOrNull
                                    ?: System.currentTimeMillis(),
                            )
                            debugDetectorLog(
                                "Detector same-document navigation tab=$kotlinTabId " +
                                    "generation=${version.navigationGeneration} accepted=$accepted",
                            )
                        }
                    } else if (version == null) {
                        VideoDetector.clearTab(kotlinTabId)
                        debugDetectorLog(
                            "Legacy navigation — cleared detected videos for tab $kotlinTabId",
                        )
                    } else {
                        val order = VideoDetector.onDetectorNavigation(kotlinTabId, version)
                        debugDetectorLog(
                            "Detector navigation tab=$kotlinTabId " +
                                "generation=${version.navigationGeneration} order=$order",
                        )
                    }
                } else if (type == "video_detected" && !detectVideosEnabled) {
                    debugDetectorLog("Video detection disabled — ignoring detection message")
                } else {
                    val kotlinTabId = resolveKotlinTabId(jsonObject)
                    if (kotlinTabId == null) {
                        retryUnresolvedDetectorMessage(jsonString, resolutionAttempt, type)
                        return
                    }
                    val version = detectorPageVersion(jsonObject)
                    if (
                        type == "video_detected" &&
                        version != null &&
                        !VideoDetector.acceptDetectorVideo(kotlinTabId, version)
                    ) {
                        debugDetectorLog(
                            "Ignoring stale detector video tab=$kotlinTabId " +
                                "generation=${version.navigationGeneration}",
                        )
                        return
                    }
                    VideoDetector.onMessageReceived(jsonObject, kotlinTabId)
                    debugDetectorLog("Message sent to VideoDetector for tab $kotlinTabId type=$type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }

    private fun detectorPageVersion(message: JsonObject): DetectorPageVersion? {
        val detectorEpoch = message["detectorEpoch"]?.jsonPrimitive?.longOrNull ?: return null
        val navigationGeneration =
            message["navigationGeneration"]?.jsonPrimitive?.longOrNull ?: return null
        return DetectorPageVersion(detectorEpoch, navigationGeneration)
    }

    private fun parsePageCastHeaders(value: JsonObject?): Map<String, String>? {
        if (value == null) return emptyMap()
        if (value.size > 16) return null
        val allowed = setOf(
            "authorization", "cookie", "referer", "origin", "user-agent", "accept", "accept-language",
        )
        val names = mutableSetOf<String>()
        val result = linkedMapOf<String, String>()
        var bytes = 0
        for ((name, element) in value) {
            val lowerName = name.lowercase()
            val headerValue = element.jsonPrimitive.contentOrNull ?: return null
            if (name != name.trim() || !HEADER_NAME.matches(name) || lowerName !in allowed ||
                !names.add(lowerName) || headerValue.any { it.code in 0..31 || it.code == 127 }
            ) return null
            if (lowerName in setOf("origin", "referer") && !MediaNetworkPolicy.isHttpUrl(headerValue)) return null
            bytes += name.toByteArray().size + headerValue.toByteArray().size
            if (bytes > 16 * 1024) return null
            result[name] = headerValue
        }
        return result
    }

    private const val PAGE_CAST_MAX_SUBTITLES = 16
    private const val PAGE_CAST_MAX_ITEMS = 50
    private const val PAGE_CAST_METADATA_BYTES = 16 * 1024
    private const val PAGE_CAST_REQUEST_BYTES = 64 * 1024
    private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    
    /**
     * Resolve the WebExtension tab to a Kotlin tab using an existing binding or
     * an exact current/previous document URL match. Unknown tabs are left
     * unresolved so a blocked popup cannot clear the selected opener tab.
     */
    private fun resolveKotlinTabId(message: JsonObject): String? {
        return try {
            val state = store.state
            val messageUrls = listOfNotNull(
                message["previousUrl"]?.jsonPrimitive?.contentOrNull,
                message["originUrl"]?.jsonPrimitive?.contentOrNull,
                message["url"]?.jsonPrimitive?.contentOrNull,
            )
            val candidates = state.tabs.map { tab ->
                DetectorTabCandidate(tab.id, tab.content.url)
            }
            val detectorEpoch = message["detectorEpoch"]?.jsonPrimitive?.longOrNull
            val detectorTabId = message["tabId"]?.jsonPrimitive?.intOrNull
            if (detectorEpoch != null && detectorTabId != null) {
                detectorTabBindingTracker.resolve(
                    incomingEpoch = detectorEpoch,
                    detectorTabId = detectorTabId,
                    messageUrls = messageUrls,
                    candidates = candidates,
                    selectedKotlinTabId = state.selectedTabId,
                )
            } else {
                detectorTabBindingTracker.resolveLegacy(
                    messageUrls = messageUrls,
                    candidates = candidates,
                    selectedKotlinTabId = state.selectedTabId,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving Kotlin tab ID", e)
            null
        }
    }

    private fun retryUnresolvedDetectorMessage(
        jsonString: String,
        resolutionAttempt: Int,
        type: String?,
    ) {
        val delayMs = listOf(50L, 150L, 400L).getOrNull(resolutionAttempt)
        if (delayMs == null) {
            debugDetectorLog("Ignoring detector $type from an unmapped Gecko tab")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { processMessage(jsonString, resolutionAttempt + 1) },
            delayMs,
        )
    }
    
}
