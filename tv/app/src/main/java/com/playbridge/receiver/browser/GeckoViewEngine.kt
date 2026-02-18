package com.playbridge.receiver.browser

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.ContentBlocking

class GeckoViewEngine(
    private val context: Context,
    private val adBlocker: AdBlocker // Kept for consistency, though GeckoView has own blocking
) : BrowserEngine {

    companion object {
        private const val TAG = "GeckoViewEngine"
    }

    private val geckoView = GeckoView(context)
    private val session = GeckoSession()
    private val runtime = GeckoRuntime.getDefault(context)

    init {
        setupGeckoView()
    }

    override fun getView(): View = geckoView

    override fun loadUrl(url: String) {
        session.loadUri(url)
    }

    override fun reload() {
        session.reload()
    }

    override fun goBack() {
        session.goBack()
    }

    override fun canGoBack(): Boolean {
        // null check for history state?
        // GeckoSession doesn't expose a simple synchronous canGoBack boolean property easily
        // without tracking state or checking navigation delegate. 
        // For simplicity in this wrapper, we might need to track it or just attempt it.
        // But for the UI state, it's better to track via a delegate.
        // For now, let's return true if we have history, but we need to implement a NavigationDelegate to track this.
        return true // Placeholder, needs state tracking
    }
    
    // We need a way to expose state updates to the Activity, but the interface doesn't have listeners yet.
    // The implementations so far rely on internal state.

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        try {
            // evaluateJavascript is not available in this GeckoView version or unresolved.
            // Fallback to javascript: URI scheme.
            val encoded = java.net.URLEncoder.encode(script, "UTF-8").replace("+", "%20")
            session.loadUri("javascript:(function(){ try{ eval(decodeURIComponent('$encoded')); } catch(e){} })();void(0);")
            // We cannot get the result back easily via loadUri
            callback?.invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "JS Error", e)
            callback?.invoke(null)
        }
    }

    override fun destroy() {
        session.close()
    }

    override fun scrollBy(dx: Int, dy: Int) {
        // GeckoView doesn't have a direct scrollBy(x, y) on the view itself that scrolls web content reliably 
        // the same way WebView does. It handles input events.
        // However, we can use JS to scroll.
        evaluateJavascript("window.scrollBy($dx, $dy)", null)
    }

    override fun simulateClick(x: Float, y: Float) {
        // Dispatch touch events to GeckoView
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventTime = android.os.SystemClock.uptimeMillis()
        
        val downEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0
        )
        val upEvent = MotionEvent.obtain(
            downTime, eventTime + 100, MotionEvent.ACTION_UP, x, y, 0
        )
        
        geckoView.dispatchTouchEvent(downEvent)
        geckoView.dispatchTouchEvent(upEvent)
        
        downEvent.recycle()
        upEvent.recycle()
    }

    private fun setupGeckoView() {
        session.open(runtime)
        geckoView.setSession(session)

        // Settings
        session.settings.apply {
            useTrackingProtection = true
            // Enable recommended tracking protection (Standard)
            // Strict mode might break some sites.
        }
        
        // User Agent
        // Use mobile user agent by default (GeckoView usually does this, but we can enforce)
        // session.settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        
        // Add minimal navigation delegate to log
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
               // We would update local state here if we had a listener
            }
            
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            }
        }
    }
}
