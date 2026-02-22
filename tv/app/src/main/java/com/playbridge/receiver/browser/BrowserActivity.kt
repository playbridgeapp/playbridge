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
import android.os.Handler
import android.os.Looper
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
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
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

    private var engine: BrowserEngine? = null
    private var canGoBack = false
    private var currentUrl: String? = null
    
    // Ad blocker
    private lateinit var adBlocker: AdBlocker
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Cursor state
    private var cursorX = 960f  // Start at center of 1920 screen
    private var cursorY = 540f  // Start at center of 1080 screen
    private var cursorView: CursorView? = null
    
    // Cursor auto-hide
    private val cursorHideHandler = Handler(Looper.getMainLooper())
    private val cursorHideRunnable = Runnable {
        cursorView?.visibility = View.GONE
    }
    private val cursorHideDelayMs = 3000L  // Hide after 3 seconds of inactivity
    
    // Fullscreen video support
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null
    private var contentContainer: FrameLayout? = null
    private var isGeckoFullscreen = false
    
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

    private lateinit var rootContainer: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Only enable hardware acceleration if we're not running on an emulator
        // Emulators often lack proper OpenGL ES 3.1 support which crashes the WebView
        if (!isEmulator()) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        }
        // We manage KEEP_SCREEN_ON dynamically during fullscreen video playback
        
        // Initialize ad blocker
        adBlocker = AdBlocker(applicationContext)
        scope.launch {
            adBlocker.loadFilterLists()
            Log.d(TAG, adBlocker.getStats())
        }

        // Create root container layout
        rootContainer = FrameLayout(this)
        
        // Create content container (for Browser Engine)
        contentContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        // Initialize Engine based on preferences
        initializeEngine()
        
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
        
        // Create cursor overlay (Topmost layer)
        cursorView = CursorView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Initially hidden until user starts using touchpad
            visibility = View.GONE
            elevation = 100f
        }
        rootContainer.addView(cursorView)
        
        setContentView(rootContainer)
        engine?.getView()?.requestFocus()
        // Load the URL from intent
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            Log.d(TAG, "Loading URL: $url")
            currentUrl = url
            engine?.loadUrl(url)
        } else {
            Log.d(TAG, "No URL provided, loading default")
            currentUrl = "https://www.google.com"
            engine?.loadUrl("https://www.google.com")
        }

        // Handle back press
        onBackPressedDispatcher.addCallback(this) {
            if (fullscreenView != null) {
                exitFullscreen()
            } else if (isGeckoFullscreen) {
                exitGeckoFullscreen()
                engine?.evaluateJavascript("document.exitFullscreen && document.exitFullscreen();", null)
            } else if (engine?.canGoBack() == true) {
                engine?.goBack()
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
    
    private fun initializeEngine() {
        // Remove existing engine view if any
        if (engine != null) {
            contentContainer?.removeView(engine?.getView())
            engine?.destroy()
            engine = null
        }
        
        val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val useGecko = prefs.getBoolean("use_gecko", false)
        
        engine = if (useGecko) {
            Log.d(TAG, "Initializing GeckoView Engine")
            GeckoViewEngine(
                this, 
                adBlocker,
                onFullscreen = { isFullscreen ->
                    if (isFullscreen) {
                        enterGeckoFullscreen()
                    } else {
                        exitGeckoFullscreen()
                    }
                }
            )
        } else {
            Log.d(TAG, "Initializing System WebView Engine")
            SystemWebViewEngine(
                this, 
                adBlocker,
                onFullscreen = { view, callback ->
                    enterFullscreen(view, callback)
                },
                onExitFullscreen = {
                    exitFullscreen()
                }
            )
        }
        
        // Add engine view at index 0 (behind cursor)
        contentContainer?.addView(engine?.getView(), 0)
    }
    
    private fun switchEngine() {
        val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val useGecko = prefs.getBoolean("use_gecko", false)
        
        // Toggle
        prefs.edit().putBoolean("use_gecko", !useGecko).apply()
        
        // Re-initialize
        initializeEngine()
        
        // Reload current URL
        currentUrl?.let { engine?.loadUrl(it) }
        
        val engineName = if (!useGecko) "GeckoView" else "System WebView"
        android.widget.Toast.makeText(this, "Switched to $engineName", android.widget.Toast.LENGTH_SHORT).show()
    }
    
    private fun showEngineSelectionDialog() {
        val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val useGecko = prefs.getBoolean("use_gecko", false)
        
        val options = arrayOf("System WebView", "GeckoView (Mozilla)")
        val checkedItem = if (useGecko) 1 else 0
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Select Browser Engine")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newUseGecko = which == 1
                if (newUseGecko != useGecko) {
                    prefs.edit().putBoolean("use_gecko", newUseGecko).apply()
                    initializeEngine()
                    currentUrl?.let { engine?.loadUrl(it) }
                }
                dialog.dismiss()
            }
            .show()
    }
    
    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        
        fullscreenView = view
        fullscreenCallback = callback
        
        contentContainer?.visibility = View.GONE
        fullscreenContainer?.visibility = View.VISIBLE
        fullscreenContainer?.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    
    private fun enterGeckoFullscreen() {
        isGeckoFullscreen = true
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    
    private fun exitGeckoFullscreen() {
        isGeckoFullscreen = false
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    
    private fun exitFullscreen() {
        if (fullscreenView == null) return
        
        fullscreenContainer?.removeView(fullscreenView)
        fullscreenView = null
        
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        
        fullscreenContainer?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE
        
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun handleMouseCommand(event: String?, dx: Float, dy: Float) {
        when (event) {
            "move" -> {
                showCursorAndResetTimer()
                val displayMetrics = resources.displayMetrics
                cursorX = (cursorX + dx).coerceIn(0f, displayMetrics.widthPixels.toFloat())
                cursorY = (cursorY + dy).coerceIn(0f, displayMetrics.heightPixels.toFloat())
                cursorView?.updatePosition(cursorX, cursorY)
            }
            "click" -> {
                showCursorAndResetTimer()
                Log.d(TAG, "Simulating click at ($cursorX, $cursorY)")
                // Delegate to engine
                engine?.simulateClick(cursorX, cursorY)
                cursorView?.animateClick()
            }
            "scroll" -> {
                engine?.scrollBy(dx.toInt(), dy.toInt())
            }
        }
    }
    
    private fun handleRemoteCommand(key: String?) {
        Log.d(TAG, "Remote command: $key")
        
        if (fullscreenView != null || isGeckoFullscreen) {
            if (key == "back") {
                 runOnUiThread { 
                     if (fullscreenView != null) exitFullscreen()
                     if (isGeckoFullscreen) exitGeckoFullscreen()
                 }
                 engine?.evaluateJavascript("document.exitFullscreen && document.exitFullscreen();", null)
            }
            return
        }
        
        val cursorStepX = 15f // Finer control horizontally
        val cursorStepY = 15f // Finer control vertically
        val scrollStep = 50   // Smaller scroll increments
        
        when (key) {
            "dpad_up" -> {
                showCursorAndResetTimer()
                if (cursorY <= cursorStepY) {
                    engine?.scrollBy(0, -scrollStep)
                } else {
                    cursorY -= cursorStepY
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_down" -> {
                showCursorAndResetTimer()
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                if (cursorY >= screenHeight - cursorStepY) {
                    engine?.scrollBy(0, scrollStep)
                } else {
                    cursorY += cursorStepY
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_left" -> {
                showCursorAndResetTimer()
                if (cursorX <= cursorStepX) {
                    engine?.scrollBy(-scrollStep, 0)
                } else {
                    cursorX -= cursorStepX
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_right" -> {
                showCursorAndResetTimer()
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                if (cursorX >= screenWidth - cursorStepX) {
                    engine?.scrollBy(scrollStep, 0)
                } else {
                    cursorX += cursorStepX
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_center" -> {
                showCursorAndResetTimer()
                engine?.simulateClick(cursorX, cursorY)
                cursorView?.animateClick()
            }
            "back" -> {
                if (engine?.canGoBack() == true) {
                    engine?.goBack()
                } else {
                    val intent = Intent(this, com.playbridge.receiver.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                    finish()
                }
            }
            "menu" -> {
                showEngineSelectionDialog()
            }
        }
    }
    
    private fun handleBrowserControlCommand(action: String?) {
        Log.d(TAG, "Browser control: $action")
        when (action) {
            "refresh" -> engine?.reload()
            // ... other commands
            "switch_engine" -> switchEngine()
        }
    }
    
    // ... (maximizeVideo/restoreVideo kept but updated to use engine?.evaluateJavascript)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            Log.d(TAG, "Loading new URL: $url")
            currentUrl = url
            engine?.loadUrl(url)
        }
    }
    
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    showEngineSelectionDialog()
                    return true
                }
                // ... (other keys same as before)
                KeyEvent.KEYCODE_DPAD_UP -> { handleRemoteCommand("dpad_up"); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { handleRemoteCommand("dpad_down"); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { handleRemoteCommand("dpad_left"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { handleRemoteCommand("dpad_right"); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { handleRemoteCommand("dpad_center"); return true }
                KeyEvent.KEYCODE_BACK -> { handleRemoteCommand("back"); return true }
            }
        }
        // ... (ACTION_UP handling)
        if (event.action == KeyEvent.ACTION_UP) {
             when (event.keyCode) {
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showCursorAndResetTimer() {
        cursorView?.visibility = View.VISIBLE
        cursorHideHandler.removeCallbacks(cursorHideRunnable)
        cursorHideHandler.postDelayed(cursorHideRunnable, cursorHideDelayMs)
    }
    
    override fun onDestroy() {
        cursorHideHandler.removeCallbacks(cursorHideRunnable)
        unregisterReceiver(commandReceiver)
        scope.cancel()
        super.onDestroy()
        engine?.destroy()
        engine = null
    }
    
    /**
     * Custom cursor overlay view
     */
    private class CursorView(context: Context) : View(context) {
        
        // Physics-based SpringAnimations for ultra-fluid movement
        private val springX: SpringAnimation = SpringAnimation(this, DynamicAnimation.X).apply {
            spring = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_VERY_LOW // Smoother tracking
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY // No overshoot
            }
        }
        
        private val springY: SpringAnimation = SpringAnimation(this, DynamicAnimation.Y).apply {
            spring = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_VERY_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
        }

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

        init {
            // Set initial position out of view
            x = 960f
            y = 540f
        }
        
        fun updatePosition(targetX: Float, targetY: Float) {
            // Animate smoothly to the new incoming coordinates instead of teleporting
            springX.animateToFinalPosition(targetX)
            springY.animateToFinalPosition(targetY)
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
            
            // Draw shadow centered (View's X/Y properties handle the actual screen position)
            canvas.drawCircle(8f, 8f, radius + 3, shadowPaint)
            
            // Draw cursor centered
            canvas.drawCircle(0f, 0f, radius, paint)
            
            // Draw border centered
            canvas.drawCircle(0f, 0f, radius, borderPaint)
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("cutf_cvm")
                || Build.BOARD.lowercase() == "goldfish")
    }
}
