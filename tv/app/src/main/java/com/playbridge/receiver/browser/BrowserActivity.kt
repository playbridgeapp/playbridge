package com.playbridge.receiver.browser

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.engine.gecko.GeckoEngineView
import mozilla.components.concept.engine.EngineSession

class BrowserActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TVBrowserActivity"
        const val EXTRA_URL = "extra_url"
    }

    private var session: EngineSession? = null
    private var engineView: GeckoEngineView? = null
    private var canGoBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize components if needed
        Components.initialize(applicationContext)

        // Create the engine view
        // Create the engine view
        engineView = GeckoEngineView(this)
        engineView?.isFocusable = true
        engineView?.isFocusableInTouchMode = true
        setContentView(engineView)
        engineView?.requestFocus()

        // Create a session
        val geckoSession = Components.engine.createSession()
        session = geckoSession

        // Register observer to track navigation state
        geckoSession.register(object : EngineSession.Observer {
            override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
                super.onNavigationStateChange(canGoBack, canGoForward)
                this@BrowserActivity.canGoBack = canGoBack ?: false
            }
        })

        // Render the session in the view
        engineView?.render(geckoSession)

        // Load the URL from intent
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            Log.d(TAG, "Loading URL: $url")
            session?.loadUrl(url)
        } else {
            Log.d(TAG, "No URL provided, loading default")
            session?.loadUrl("https://www.google.com")
        }

        // Handle back press
        onBackPressedDispatcher.addCallback(this) {
            if (canGoBack) {
                session?.goBack()
            } else {
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            Log.d(TAG, "Loading new URL: $url")
            session?.loadUrl(url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.let {
            // Clean up session if needed, though for a single session activity 
            // the engine handles most cleanup.
            // If using a store, correct cleanup would be via store action.
            // Here we just release the view render.
            engineView?.release()
        }
        engineView = null
        session = null
    }
}
