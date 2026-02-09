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
    
    // Fullscreen video support
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null
    private var contentContainer: FrameLayout? = null
    
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

        // Create root container layout
        val rootContainer = FrameLayout(this)
        
        // Create content container (for WebView and cursor)
        contentContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
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
            
            // Set WebChromeClient for popups and fullscreen
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
                
                // Handle fullscreen video requests
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    Log.d(TAG, "onShowCustomView - entering fullscreen")
                    if (fullscreenView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    
                    fullscreenView = view
                    fullscreenCallback = callback
                    
                    // Hide the WebView and show fullscreen content
                    contentContainer?.visibility = View.GONE
                    fullscreenContainer?.visibility = View.VISIBLE
                    fullscreenContainer?.addView(view, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    
                    // Make fullscreen immersive
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                }
                
                override fun onHideCustomView() {
                    Log.d(TAG, "onHideCustomView - exiting fullscreen")
                    if (fullscreenView == null) return
                    
                    // Remove fullscreen view
                    fullscreenContainer?.removeView(fullscreenView)
                    fullscreenView = null
                    
                    // Notify callback
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                    
                    // Show WebView again
                    fullscreenContainer?.visibility = View.GONE
                    contentContainer?.visibility = View.VISIBLE
                    
                    // Restore system UI
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
        contentContainer?.addView(webView)
        
        // Create cursor overlay
        cursorView = CursorView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Initially hidden until user starts using touchpad
            visibility = View.GONE
        }
        contentContainer?.addView(cursorView)
        
        rootContainer.addView(contentContainer)
        
        // Create fullscreen container (initially hidden)
        fullscreenContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE
        }
        rootContainer.addView(fullscreenContainer)
        
        setContentView(rootContainer)
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
        
        // If in native fullscreen, control video via JavaScript
        if (fullscreenView != null) {
            val js = when (key) {
                "dpad_left" -> """
                    (function() {
                        var v = document.fullscreenElement?.querySelector('video') || document.querySelector('video');
                        if (v) v.currentTime = Math.max(0, v.currentTime - 10);
                    })();
                """.trimIndent()
                "dpad_right" -> """
                    (function() {
                        var v = document.fullscreenElement?.querySelector('video') || document.querySelector('video');
                        if (v) v.currentTime = Math.min(v.duration || 0, v.currentTime + 10);
                    })();
                """.trimIndent()
                "dpad_up" -> """
                    (function() {
                        var v = document.fullscreenElement?.querySelector('video') || document.querySelector('video');
                        if (v) v.volume = Math.min(1, v.volume + 0.1);
                    })();
                """.trimIndent()
                "dpad_down" -> """
                    (function() {
                        var v = document.fullscreenElement?.querySelector('video') || document.querySelector('video');
                        if (v) v.volume = Math.max(0, v.volume - 0.1);
                    })();
                """.trimIndent()
                "dpad_center" -> """
                    (function() {
                        var v = document.fullscreenElement?.querySelector('video') || document.querySelector('video');
                        if (v) {
                            if (v.paused) v.play();
                            else v.pause();
                        }
                    })();
                """.trimIndent()
                "back" -> {
                    // Exit fullscreen via the WebChromeClient callback
                    runOnUiThread {
                        fullscreenCallback?.onCustomViewHidden()
                        fullscreenContainer?.removeView(fullscreenView)
                        fullscreenView = null
                        fullscreenCallback = null
                        fullscreenContainer?.visibility = View.GONE
                        contentContainer?.visibility = View.VISIBLE
                        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                    }
                    // Also exit JS fullscreen
                    webView?.evaluateJavascript("document.exitFullscreen && document.exitFullscreen();", null)
                    return
                }
                else -> null
            }
            if (js != null) {
                webView?.evaluateJavascript(js, null)
            }
            return
        }
        
        // D-pad moves cursor with edge scrolling
        val cursorStep = 30f  // Pixels to move cursor per D-pad press
        val scrollStep = 100  // Pixels to scroll when at edge
        
        when (key) {
            "dpad_up" -> {
                cursorView?.visibility = View.VISIBLE
                if (cursorY <= cursorStep) {
                    // At top edge, scroll up
                    webView?.scrollBy(0, -scrollStep)
                } else {
                    cursorY -= cursorStep
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_down" -> {
                cursorView?.visibility = View.VISIBLE
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                if (cursorY >= screenHeight - cursorStep) {
                    // At bottom edge, scroll down
                    webView?.scrollBy(0, scrollStep)
                } else {
                    cursorY += cursorStep
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_left" -> {
                cursorView?.visibility = View.VISIBLE
                if (cursorX <= cursorStep) {
                    // At left edge, scroll left
                    webView?.scrollBy(-scrollStep, 0)
                } else {
                    cursorX -= cursorStep
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_right" -> {
                cursorView?.visibility = View.VISIBLE
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                if (cursorX >= screenWidth - cursorStep) {
                    // At right edge, scroll right
                    webView?.scrollBy(scrollStep, 0)
                } else {
                    cursorX += cursorStep
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_center" -> {
                // Click at cursor position
                cursorView?.visibility = View.VISIBLE
                Log.d(TAG, "D-pad center click at ($cursorX, $cursorY)")
                simulateClick(cursorX, cursorY)
                cursorView?.animateClick()
            }
            "back" -> {
                if (canGoBack) {
                    webView?.goBack()
                } else {
                    finish()
                }
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
            "maximize_video" -> {
                maximizeVideo()
            }
            "restore_video" -> {
                restoreVideo()
            }
        }
    }
    
    private fun maximizeVideo() {
        // Use native fullscreen only - triggers onShowCustomView callback
        val js = """
            (function() {
                // Find video in main document
                var video = document.querySelector('video');
                
                // If not found, search in same-origin iframes
                if (!video) {
                    var iframes = document.querySelectorAll('iframe');
                    for (var i = 0; i < iframes.length; i++) {
                        try {
                            var iframeDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                            video = iframeDoc.querySelector('video');
                            if (video) {
                                console.log('PlayBridge: Found video in iframe');
                                break;
                            }
                        } catch(e) {
                            console.log('PlayBridge: Cannot access iframe (cross-origin)');
                        }
                    }
                }
                
                if (!video) {
                    // Try to find an iframe with fullscreen support
                    var iframe = document.querySelector('iframe[allowfullscreen]');
                    if (iframe) {
                        console.log('PlayBridge: Requesting fullscreen on iframe');
                        iframe.requestFullscreen().catch(function(e) {
                            console.log('PlayBridge: Iframe fullscreen failed: ' + e.message);
                        });
                        return true;
                    }
                    console.log('PlayBridge: No video found');
                    return false;
                }
                
                // Request native fullscreen
                console.log('PlayBridge: Requesting native fullscreen');
                video.requestFullscreen().catch(function(e) {
                    console.log('PlayBridge: Fullscreen failed: ' + e.message);
                });
                return true;
            })();
        """.trimIndent()
        
        webView?.evaluateJavascript(js) { result ->
            Log.d(TAG, "Maximize video result: $result")
        }
    }
    
    private fun restoreVideo() {
        // Exit native fullscreen
        val js = """
            (function() {
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                }
                return true;
            })();
        """.trimIndent()
        
        webView?.evaluateJavascript(js) { result ->
            Log.d(TAG, "Restore video result: $result")
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
