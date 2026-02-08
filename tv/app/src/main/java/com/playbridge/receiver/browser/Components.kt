package com.playbridge.receiver.browser

import android.content.Context
import android.util.Log
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.store.BrowserStore
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.util.concurrent.atomic.AtomicBoolean

object Components {
    private const val TAG = "Components"
    
    // Track initialization state
    private val isInitialized = AtomicBoolean(false)
    
    private lateinit var appContext: Context
    
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
    
    val engine: GeckoEngine by lazy {
        GeckoEngine(appContext, runtime = runtime)
    }
    
    val store: BrowserStore by lazy {
        BrowserStore()
    }
    
    fun initialize(context: Context) {
        if (isInitialized.compareAndSet(false, true)) {
            appContext = context.applicationContext
            Log.d(TAG, "Components initialized")
        }
    }
}
