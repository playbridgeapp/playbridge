package com.playbridge.receiver.browser

import android.view.View

/**
 * Abstraction for different browser engines (System WebView, GeckoView)
 */
interface BrowserEngine {
    /**
     * Get the view component to be added to the layout
     */
    fun getView(): View

    /**
     * Load a URL
     */
    fun loadUrl(url: String)

    /**
     * Reload the current page
     */
    fun reload()

    /**
     * Navigate back in history
     */
    fun goBack()

    /**
     * Check if back navigation is possible
     */
    fun canGoBack(): Boolean

    /**
     * Run JavaScript in the current page
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)

    /**
     * Handle cleanup
     */
    fun destroy()
    
    /**
     * Simulate a scroll event
     */
    fun scrollBy(dx: Int, dy: Int)
    
    /**
     * Simulate a click event at the given coordinates relative to the view
     */
    fun simulateClick(x: Float, y: Float)
}
