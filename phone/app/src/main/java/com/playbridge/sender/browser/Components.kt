package com.playbridge.sender.browser

import android.content.Context
import android.util.Log
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.addons.AddonManager
import mozilla.components.feature.addons.amo.AMOAddonsProvider
import mozilla.components.feature.addons.update.AddonUpdater
import mozilla.components.lib.fetch.okhttp.OkHttpClient
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
}
