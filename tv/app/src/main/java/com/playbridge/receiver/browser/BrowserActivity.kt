package com.playbridge.receiver.browser

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import com.playbridge.receiver.server.ServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class BrowserActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TVBrowserActivity"
        const val EXTRA_URL = "extra_url"
    }

    private var webView: WebView? = null
    private var canGoBack = false
    private var currentUrl: String? = null
    
    // Ad blocker
    private lateinit var adBlocker: AdBlocker
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Cursor state
    private var cursorX = 960f  // Start at center of 1920 screen
    private var cursorY = 540f  // Start at center of 1080 screen
    private var cursorView: CursorView? = null
    
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_MOUSE -> {
                    val event = intent.getStringExtra(ServerService.EXTRA_MOUSE_EVENT)
                    val dx = intent.getFloatExtra(ServerService.EXTRA_MOUSE_DX, 0f)
                    val dy = intent.getFloatExtra(ServerService.EXTRA_MOUSE_DY, 0f)
                    handleMouseCommand(event, dx, dy)
                }
                ServerService.ACTION_REMOTE -> {
                    val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                    handleRemoteCommand(key)
                }
                ServerService.ACTION_BROWSER_CONTROL -> {
                    val action = intent.getStringExtra(ServerService.EXTRA_BROWSER_ACTION)
                    handleBrowserControlCommand(action)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize ad blocker - built-in rules load immediately, filter list loads async
        adBlocker = AdBlocker(applicationContext)
        scope.launch {
            adBlocker.loadFilterLists()
            Log.d(TAG, adBlocker.getStats())
        }

        // Create container layout
        val container = FrameLayout(this)
        
        // Create the WebView
        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            
            // Configure WebView settings
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // Disable popups
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
            
            // Set WebViewClient with ad blocking
            webViewClient = AdBlockingWebViewClient()
            
            // Set WebChromeClient to block popups
            webChromeClient = object : WebChromeClient() {
                // Block window.open() popups
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    Log.d(TAG, "Blocked popup window creation (isUserGesture=$isUserGesture)")
                    return false
                }
            }
        }
        container.addView(webView)
        
        // Create cursor overlay
        cursorView = CursorView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Initially hidden until user starts using touchpad
            visibility = View.GONE
        }
        container.addView(cursorView)
        
        setContentView(container)
        webView?.requestFocus()

        // Load the URL from intent
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            Log.d(TAG, "Loading URL: $url")
            currentUrl = url
            webView?.loadUrl(url)
        } else {
            Log.d(TAG, "No URL provided, loading default")
            currentUrl = "https://www.google.com"
            webView?.loadUrl("https://www.google.com")
        }

        // Handle back press
        onBackPressedDispatcher.addCallback(this) {
            if (canGoBack) {
                webView?.goBack()
            } else {
                finish()
            }
        }
        
        // Register broadcast receiver for remote commands
        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_MOUSE)
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_BROWSER_CONTROL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }
    
    /**
     * Custom WebViewClient that uses AdBlocker to block ads and prevent redirects
     */
    private inner class AdBlockingWebViewClient : WebViewClient() {
        
        // Track the last navigation time to detect rapid redirects
        private var lastNavigationTime = 0L
        private var navigationCount = 0
        
        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            canGoBack = view?.canGoBack() ?: false
            if (!isReload && url != null) {
                currentUrl = url
            }
        }
        
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            val host = request.url?.host ?: ""
            
            // Block non-HTTP schemes (intent://, market://, etc.)
            val scheme = request.url?.scheme
            if (scheme != "http" && scheme != "https") {
                Log.d(TAG, "Blocked non-HTTP scheme: $scheme")
                return true
            }
            
            // Use AdBlocker to check if this navigation should be blocked
            if (adBlocker.shouldBlock(url, currentUrl, AdBlocker.TYPE_SUBDOCUMENT)) {
                Log.d(TAG, "Blocked navigation: $url")
                return true
            }
            
            // Detect rapid redirects (potential ad/scam behavior)
            val now = System.currentTimeMillis()
            if (now - lastNavigationTime < 500) {
                navigationCount++
                if (navigationCount > 3) {
                    Log.d(TAG, "Blocked rapid redirect chain: $url")
                    navigationCount = 0
                    return true
                }
            } else {
                navigationCount = 0
            }
            lastNavigationTime = now
            
            // Block cross-domain popups/redirects that look suspicious
            val currentHost = Uri.parse(currentUrl)?.host
            if (currentHost != null && host != currentHost) {
                // Check for suspicious redirect patterns
                if (url.contains("redirect=") || 
                    url.contains("goto=") || 
                    url.contains("out.php") ||
                    url.contains("click.php") ||
                    url.contains("/cgi-bin/")) {
                    Log.d(TAG, "Blocked suspicious redirect: $url")
                    return true
                }
            }
            
            return false
        }
        
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            
            // Determine resource type for more accurate blocking
            val resourceType = getResourceType(request)
            
            // Use AdBlocker to check if this resource should be blocked
            if (adBlocker.shouldBlock(url, currentUrl, resourceType)) {
                return createEmptyResponse()
            }
            
            return null
        }
        
        private fun getResourceType(request: WebResourceRequest): Int {
            // Try to determine resource type from Accept header or URL
            val acceptHeader = request.requestHeaders["Accept"] ?: ""
            val url = request.url?.toString()?.lowercase() ?: ""
            
            return when {
                acceptHeader.contains("text/html") -> AdBlocker.TYPE_SUBDOCUMENT
                acceptHeader.contains("text/css") -> AdBlocker.TYPE_STYLESHEET
                acceptHeader.contains("image/") -> AdBlocker.TYPE_IMAGE
                acceptHeader.contains("javascript") || url.endsWith(".js") -> AdBlocker.TYPE_SCRIPT
                acceptHeader.contains("application/json") || 
                    acceptHeader.contains("application/xml") -> AdBlocker.TYPE_XMLHTTPREQUEST
                url.endsWith(".css") -> AdBlocker.TYPE_STYLESHEET
                url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".gif") || 
                    url.endsWith(".webp") || url.endsWith(".svg") -> AdBlocker.TYPE_IMAGE
                else -> AdBlocker.TYPE_OTHER
            }
        }
        
        private fun createEmptyResponse(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }
    
    private fun handleMouseCommand(event: String?, dx: Float, dy: Float) {
        Log.d(TAG, "Mouse command: $event ($dx, $dy)")
        
        when (event) {
            "move" -> {
                // Show cursor if hidden
                cursorView?.visibility = View.VISIBLE
                
                // Update cursor position with bounds checking
                val displayMetrics = resources.displayMetrics
                cursorX = (cursorX + dx).coerceIn(0f, displayMetrics.widthPixels.toFloat())
                cursorY = (cursorY + dy).coerceIn(0f, displayMetrics.heightPixels.toFloat())
                
                // Update cursor view
                cursorView?.updatePosition(cursorX, cursorY)
            }
            "click" -> {
                // Show cursor if hidden
                cursorView?.visibility = View.VISIBLE
                
                Log.d(TAG, "Simulating click at ($cursorX, $cursorY)")
                
                // Simulate touch event at cursor position
                simulateClick(cursorX, cursorY)
                
                // Visual feedback
                cursorView?.animateClick()
            }
            "scroll" -> {
                // Scroll the view
                webView?.scrollBy(dx.toInt(), dy.toInt())
            }
        }
    }
    
    private fun simulateClick(x: Float, y: Float) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventTime = android.os.SystemClock.uptimeMillis()
        
        val downEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0
        )
        val upEvent = MotionEvent.obtain(
            downTime, eventTime + 100, MotionEvent.ACTION_UP, x, y, 0
        )
        
        webView?.dispatchTouchEvent(downEvent)
        webView?.dispatchTouchEvent(upEvent)
        
        downEvent.recycle()
        upEvent.recycle()
    }
    
    private fun handleRemoteCommand(key: String?) {
        Log.d(TAG, "Remote command: $key")
        
        val keyCode = when (key) {
            "dpad_up" -> KeyEvent.KEYCODE_DPAD_UP
            "dpad_down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "dpad_left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "dpad_right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "dpad_center" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            else -> null
        }
        
        if (keyCode != null) {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            
            runOnUiThread {
                dispatchKeyEvent(downEvent)
                dispatchKeyEvent(upEvent)
            }
        }
    }
    
    private fun handleBrowserControlCommand(action: String?) {
        Log.d(TAG, "Browser control: $action")
        
        when (action) {
            "refresh" -> {
                webView?.reload()
            }
            "toggle_ublock" -> {
                // Ad blocking is built-in and always enabled with EasyList-style rules
                Log.d(TAG, "Ad blocking is built-in: ${adBlocker.getStats()}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            Log.d(TAG, "Loading new URL: $url")
            currentUrl = url
            webView?.loadUrl(url)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(commandReceiver)
        scope.cancel()
        super.onDestroy()
        webView?.destroy()
        webView = null
    }
    
    /**
     * Custom cursor overlay view
     */
    private class CursorView(context: Context) : View(context) {
        private var cursorX = 960f
        private var cursorY = 540f
        private var scale = 1f
        
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 100, 100)
            style = Paint.Style.FILL
        }
        
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 0, 0, 0)
            style = Paint.Style.FILL
        }
        
        fun updatePosition(x: Float, y: Float) {
            cursorX = x
            cursorY = y
            invalidate()
        }
        
        fun animateClick() {
            scale = 0.7f
            invalidate()
            postDelayed({
                scale = 1f
                invalidate()
            }, 100)
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            val radius = 15f * scale
            
            // Draw shadow
            canvas.drawCircle(cursorX + 2, cursorY + 2, radius + 3, shadowPaint)
            
            // Draw cursor
            canvas.drawCircle(cursorX, cursorY, radius, paint)
            
            // Draw border
            canvas.drawCircle(cursorX, cursorY, radius, borderPaint)
        }
    }
}
