package com.playbridge.receiver.browser

import android.content.Context
import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController.EnableSource

/**
 * Manages browser extensions including uBlock Origin
 */
class ExtensionManager(
    private val context: Context,
    private val runtime: GeckoRuntime
) {
    companion object {
        private const val TAG = "ExtensionManager"
        private const val PREFS_NAME = "extension_prefs"
        private const val KEY_UBLOCK_ENABLED = "ublock_enabled"
        
        // uBlock Origin extension info
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_URI = "resource://android/assets/extensions/ublock_origin/"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var ublockExtension: WebExtension? = null
    private var isInitialized = false
    
    /**
     * Initialize extensions - call once on app startup
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true
        
        Log.d(TAG, "Initializing extensions...")
        
        // Install uBlock Origin as a built-in extension
        runtime.webExtensionController.ensureBuiltIn(UBLOCK_URI, UBLOCK_ID).accept(
            { extension ->
                Log.d(TAG, "uBlock Origin installed successfully: ${extension?.id}")
                ublockExtension = extension
                
                // Apply saved enabled state
                val shouldBeEnabled = prefs.getBoolean(KEY_UBLOCK_ENABLED, true)
                if (extension != null && extension.metaData.enabled != shouldBeEnabled) {
                    setUblockEnabled(shouldBeEnabled)
                }
            },
            { error ->
                Log.e(TAG, "Failed to install uBlock Origin", error)
            }
        )
    }
    
    /**
     * Check if uBlock is currently enabled
     */
    fun isUblockEnabled(): Boolean {
        return prefs.getBoolean(KEY_UBLOCK_ENABLED, true)
    }
    
    /**
     * Enable or disable uBlock Origin
     */
    fun setUblockEnabled(enabled: Boolean) {
        Log.d(TAG, "Setting uBlock enabled: $enabled")
        prefs.edit().putBoolean(KEY_UBLOCK_ENABLED, enabled).apply()
        
        ublockExtension?.let { ext ->
            if (enabled) {
                runtime.webExtensionController.enable(ext, EnableSource.USER).accept(
                    { updatedExt ->
                        Log.d(TAG, "uBlock enabled: ${updatedExt?.id}")
                        ublockExtension = updatedExt
                    },
                    { error -> Log.e(TAG, "Failed to enable uBlock", error) }
                )
            } else {
                runtime.webExtensionController.disable(ext, EnableSource.USER).accept(
                    { updatedExt ->
                        Log.d(TAG, "uBlock disabled: ${updatedExt?.id}")
                        ublockExtension = updatedExt
                    },
                    { error -> Log.e(TAG, "Failed to disable uBlock", error) }
                )
            }
        } ?: run {
            Log.w(TAG, "uBlock extension not loaded yet, will apply setting on next init")
        }
    }
    
    /**
     * Toggle uBlock Origin enabled state
     */
    fun toggleUblock(): Boolean {
        val newState = !isUblockEnabled()
        setUblockEnabled(newState)
        return newState
    }
}
