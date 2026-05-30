package com.playbridge.player.browser

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
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class BrowserActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PBBrowserActivity"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_DESKTOP_MODE = "extra_desktop_mode"

        const val ACTION_MOUSE = "com.playbridge.player.ACTION_MOUSE"
        const val ACTION_REMOTE = "com.playbridge.player.ACTION_REMOTE"
        const val ACTION_BROWSER_CONTROL = "com.playbridge.player.ACTION_BROWSER_CONTROL"
        const val EXTRA_MOUSE_EVENT = "mouse_event"
        const val EXTRA_MOUSE_DX = "mouse_dx"
        const val EXTRA_MOUSE_DY = "mouse_dy"
        const val EXTRA_REMOTE_KEY = "remote_key"
        const val EXTRA_BROWSER_ACTION = "browser_action"
    }

    private var engine: SystemWebViewEngine? = null
    private var canGoBack = false
    private var currentUrl: String? = null

    // Drag state — tracks an in-progress click-drag (e.g. seekbar scrubbing)
    private var isDragging = false
    private var dragDownTime = 0L

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

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_MOUSE -> {
                    val event = intent.getStringExtra(EXTRA_MOUSE_EVENT)
                    val dx = intent.getFloatExtra(EXTRA_MOUSE_DX, 0f)
                    val dy = intent.getFloatExtra(EXTRA_MOUSE_DY, 0f)
                    handleMouseCommand(event, dx, dy)
                }
                ACTION_REMOTE -> {
                    val key = intent.getStringExtra(EXTRA_REMOTE_KEY)
                    handleRemoteCommand(key)
                }
                ACTION_BROWSER_CONTROL -> {
                    val action = intent.getStringExtra(EXTRA_BROWSER_ACTION)
                    handleBrowserControlCommand(action)
                }
            }
        }
    }

    private lateinit var rootContainer: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android ≤ 9 needs WRITE_EXTERNAL_STORAGE granted at runtime to save into public Downloads.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }

        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Use the singleton AdBlocker
        adBlocker = AdBlocker.getInstance(applicationContext)

        // Create root container layout with dark background
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // Create content container (for Browser Engine) with dark background
        contentContainer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Initialize Engine
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
            } else if (engine?.canGoBack() == true) {
                engine?.goBack()
            } else {
                finish()
            }
        }

        // Register broadcast receiver for remote commands
        val filter = IntentFilter().apply {
            addAction(ACTION_MOUSE)
            addAction(ACTION_REMOTE)
            addAction(ACTION_BROWSER_CONTROL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            androidx.core.content.ContextCompat.registerReceiver(this, commandReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
    }

    private fun initializeEngine() {
        // Remove existing engine view if any
        if (engine != null) {
            contentContainer?.removeView(engine?.getView())
            engine?.destroy()
            engine = null
        }

        val desktopMode = intent.getBooleanExtra(EXTRA_DESKTOP_MODE, false)

        Log.d(TAG, "Initializing System WebView Engine (desktop=$desktopMode)")
        engine = SystemWebViewEngine(
            this,
            adBlocker,
            desktopMode = desktopMode,
            onFullscreen = { view, callback ->
                enterFullscreen(view, callback)
            },
            onExitFullscreen = {
                exitFullscreen()
            },
            onEngineRecreateRequired = { url ->
                Log.w(TAG, "Rebuilding SystemWebViewEngine after render process crash")
                initializeEngine()
                url?.let { engine?.loadUrl(it) }
            },
            onDownloadStarted = { id, name -> showDownloadPopup(id, name) }
        )

        // Add engine view at index 0 (behind cursor)
        contentContainer?.addView(engine?.getView(), 0)
    }

    /**
     * Bottom-right overlay card showing live download progress. Because the phone's cursor/d-pad
     * target the web page (not Android overlays), the card isn't tappable, so the file is opened
     * automatically on completion (the "open" affordance for a pointer-less TV).
     */
    private fun showDownloadPopup(downloadId: Long, fileName: String) {
        runOnUiThread {
            val dm = getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            fun dpf(v: Int) = v * density
            val accent = android.graphics.Color.parseColor("#5B9DFF")
            val muted = android.graphics.Color.parseColor("#9AA0AA")
            val barWidth = dp(320)

            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpf(18)
                    setColor(android.graphics.Color.parseColor("#F0202028"))
                    setStroke(dp(1), android.graphics.Color.parseColor("#22FFFFFF"))
                }
                setPadding(dp(18), dp(14), dp(18), dp(16))
                elevation = dpf(12)
            }
            val header = android.widget.TextView(this).apply {
                text = "↓  DOWNLOADING"
                setTextColor(accent)
                textSize = 10f
                letterSpacing = 0.12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val title = android.widget.TextView(this).apply {
                text = fileName
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = android.widget.LinearLayout.LayoutParams(barWidth, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4) }
            }

            fun roundRect(color: Int) = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpf(3); setColor(color)
            }
            val progressLayer = android.graphics.drawable.LayerDrawable(arrayOf(
                roundRect(android.graphics.Color.parseColor("#33FFFFFF")),
                android.graphics.drawable.ClipDrawable(
                    roundRect(accent), android.view.Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL
                )
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = false
                progressDrawable = progressLayer
                layoutParams = android.widget.LinearLayout.LayoutParams(barWidth, dp(6)).apply { topMargin = dp(12) }
            }

            val pctText = android.widget.TextView(this).apply {
                text = "Starting…"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val sizeText = android.widget.TextView(this).apply {
                setTextColor(muted)
                textSize = 12f
            }
            val bottomRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(barWidth, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
                addView(pctText, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(sizeText)
            }

            card.addView(header); card.addView(title); card.addView(bar); card.addView(bottomRow)
            rootContainer.addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(0, 0, dp(24), dp(24))
            })

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            fun finishCard(autoDismissMs: Long) = handler.postDelayed({ rootContainer.removeView(card) }, autoDismissMs)

            val poll = object : Runnable {
                override fun run() {
                    var reschedule = false
                    dm.query(android.app.DownloadManager.Query().setFilterById(downloadId))?.use { c ->
                        if (!c.moveToFirst()) { rootContainer.removeView(card); return@use }
                        val st = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                        val dl = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val tot = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (st) {
                            android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                                header.text = "✓  DOWNLOADED"
                                bar.progress = 100
                                pctText.text = "Saved to Downloads"; sizeText.text = ""
                                finishCard(6000)
                            }
                            android.app.DownloadManager.STATUS_FAILED -> {
                                header.text = "✕  FAILED"
                                pctText.text = "Download failed"; sizeText.text = ""
                                finishCard(4000)
                            }
                            else -> {
                                if (tot > 0) {
                                    bar.progress = (dl * 100 / tot).toInt()
                                    pctText.text = "${bar.progress}%"
                                    sizeText.text = "${fmtSize(dl)} / ${fmtSize(tot)}"
                                } else {
                                    pctText.text = "Starting…"
                                    sizeText.text = fmtSize(dl)
                                }
                                reschedule = true
                            }
                        }
                    }
                    if (reschedule) handler.postDelayed(this, 500)
                }
            }
            handler.post(poll)
        }
    }

    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format("%.1f %s", v, units[i])
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

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun exitFullscreen() {
        if (fullscreenView == null) return

        fullscreenContainer?.removeView(fullscreenView)
        fullscreenView = null

        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null

        fullscreenContainer?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE

        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Dispatch a single touch action (ACTION_DOWN, ACTION_MOVE, ACTION_UP) to the correct
     * target view based on fullscreen state.
     */
    private fun dispatchTouchToActiveView(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = android.view.MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        val targetView = when {
            fullscreenView != null -> fullscreenView
            else -> engine?.getView()
        }
        targetView?.dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * Simulate a tap (ACTION_DOWN immediately followed by ACTION_UP) at the given position.
     */
    private fun simulateClickOnActiveView(x: Float, y: Float) {
        val downTime = android.os.SystemClock.uptimeMillis()
        dispatchTouchToActiveView(android.view.MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        dispatchTouchToActiveView(android.view.MotionEvent.ACTION_UP, x, y, downTime, downTime + 100)
    }

    private fun handleMouseCommand(event: String?, dx: Float, dy: Float) {
        when (event) {
            "move" -> {
                showCursorAndResetTimer()
                val displayMetrics = resources.displayMetrics
                cursorX = (cursorX + dx).coerceIn(0f, displayMetrics.widthPixels.toFloat())
                cursorY = (cursorY + dy).coerceIn(0f, displayMetrics.heightPixels.toFloat())
                cursorView?.updatePosition(cursorX, cursorY)
                // If a drag is in progress, also send ACTION_MOVE so the page feels it
                if (isDragging) {
                    val eventTime = android.os.SystemClock.uptimeMillis()
                    dispatchTouchToActiveView(
                        android.view.MotionEvent.ACTION_MOVE, cursorX, cursorY, dragDownTime, eventTime
                    )
                }
            }
            "click" -> {
                showCursorAndResetTimer()
                Log.d(TAG, "Simulating click at ($cursorX, $cursorY)")
                simulateClickOnActiveView(cursorX, cursorY)
                cursorView?.animateClick()
            }
            "scroll" -> {
                engine?.scrollBy(dx, dy)
            }
            "down" -> {
                // Start a click-drag: send ACTION_DOWN and remember the time
                showCursorAndResetTimer()
                dragDownTime = android.os.SystemClock.uptimeMillis()
                isDragging = true
                Log.d(TAG, "Drag start at ($cursorX, $cursorY)")
                dispatchTouchToActiveView(
                    android.view.MotionEvent.ACTION_DOWN, cursorX, cursorY, dragDownTime, dragDownTime
                )
                cursorView?.animateClick()
            }
            "up" -> {
                // End a click-drag: send ACTION_UP and clear drag state
                if (isDragging) {
                    val eventTime = android.os.SystemClock.uptimeMillis()
                    Log.d(TAG, "Drag end at ($cursorX, $cursorY)")
                    dispatchTouchToActiveView(
                        android.view.MotionEvent.ACTION_UP, cursorX, cursorY, dragDownTime, eventTime
                    )
                    isDragging = false
                    dragDownTime = 0L
                }
            }
        }
    }

    private fun handleRemoteCommand(key: String?) {
        Log.d(TAG, "Remote command: $key")

        if (fullscreenView != null) {
            when (key) {
                "back" -> {
                    runOnUiThread {
                        exitFullscreen()
                    }
                }
                "dpad_center" -> {
                    showCursorAndResetTimer()
                    simulateClickOnActiveView(cursorX, cursorY)
                    cursorView?.animateClick()
                }
                "dpad_up", "dpad_down", "dpad_left", "dpad_right" -> {
                    // Allow cursor movement during fullscreen
                    showCursorAndResetTimer()
                    val cursorStep = 15f
                    val displayMetrics = resources.displayMetrics
                    when (key) {
                        "dpad_up" -> cursorY = (cursorY - cursorStep).coerceAtLeast(0f)
                        "dpad_down" -> cursorY = (cursorY + cursorStep).coerceAtMost(displayMetrics.heightPixels.toFloat())
                        "dpad_left" -> cursorX = (cursorX - cursorStep).coerceAtLeast(0f)
                        "dpad_right" -> cursorX = (cursorX + cursorStep).coerceAtMost(displayMetrics.widthPixels.toFloat())
                    }
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            return
        }

        // Keyboard input streamed from the phone (browser keyboard mode).
        if (key != null && key.startsWith("text:")) {
            injectFocusedText(key.removePrefix("text:"))
            return
        }
        if (key == "key_enter") {
            submitFocusedInput()
            return
        }

        val cursorStepX = 15f // Finer control horizontally
        val cursorStepY = 15f // Finer control vertically
        val scrollStep = 50   // Smaller scroll increments

        when (key) {
            "dpad_up" -> {
                showCursorAndResetTimer()
                if (cursorY <= cursorStepY) {
                    engine?.scrollBy(0f, -scrollStep.toFloat())
                } else {
                    cursorY -= cursorStepY
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_down" -> {
                showCursorAndResetTimer()
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                if (cursorY >= screenHeight - cursorStepY) {
                    engine?.scrollBy(0f, scrollStep.toFloat())
                } else {
                    cursorY += cursorStepY
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_left" -> {
                showCursorAndResetTimer()
                if (cursorX <= cursorStepX) {
                    engine?.scrollBy(-scrollStep.toFloat(), 0f)
                } else {
                    cursorX -= cursorStepX
                    cursorView?.updatePosition(cursorX, cursorY)
                }
            }
            "dpad_right" -> {
                showCursorAndResetTimer()
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                if (cursorX >= screenWidth - cursorStepX) {
                    engine?.scrollBy(scrollStep.toFloat(), 0f)
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
                // Back goes one page back; only exits when there's no history left.
                if (engine?.canGoBack() == true) {
                    engine?.goBack()
                } else {
                    finish()
                }
            }
            "home" -> {
                // Home always exits the browser.
                finish()
            }
        }
    }

    /** Write [b64] (base64 UTF-8) into the focused web input, replacing its contents. */
    private fun injectFocusedText(b64: String) {
        // Decode in JS so arbitrary text never has to be escaped into the script string.
        val safe = b64.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        val js = "(function(){try{" +
            "var t=decodeURIComponent(escape(atob('$safe')));" +
            "var e=document.activeElement;" +
            "if(e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable)){" +
              "if(e.isContentEditable){e.textContent=t;}else{e.value=t;}" +
              "e.dispatchEvent(new Event('input',{bubbles:true}));" +
              "e.dispatchEvent(new Event('change',{bubbles:true}));" +
              "return 'ok';}" +
            "return 'no_focus';}catch(err){return 'err:'+err;}})();"
        engine?.evaluateJavascript(js) { result -> Log.d(TAG, "injectFocusedText: $result") }
    }

    /** Fire an Enter key on the focused input and submit its form if any. */
    private fun submitFocusedInput() {
        val js = "(function(){var e=document.activeElement;if(!e)return 'no_focus';" +
            "var mk=function(ty){return new KeyboardEvent(ty,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true});};" +
            "e.dispatchEvent(mk('keydown'));e.dispatchEvent(mk('keypress'));e.dispatchEvent(mk('keyup'));" +
            "if(e.form){try{if(e.form.requestSubmit){e.form.requestSubmit();}else{e.form.submit();}}catch(err){}}" +
            "return 'ok';})();"
        engine?.evaluateJavascript(js) { result -> Log.d(TAG, "submitFocusedInput: $result") }
    }

    private fun handleBrowserControlCommand(action: String?) {
        Log.d(TAG, "Browser control: $action")
        when (action) {
            "refresh" -> engine?.reload()
            "maximize_video" -> {
                val js = "(function(){" +
                    "var old=document.getElementById('pb-maximize-style');" +
                    "if(old)old.parentNode.removeChild(old);" +
                    "function findLargestVideo(doc){" +
                      "try{var videos=doc.querySelectorAll('video');var largest=null;var largestArea=0;" +
                      "for(var i=0;i<videos.length;i++){var v=videos[i];var area=v.offsetWidth*v.offsetHeight;" +
                      "if(area>largestArea||(area===0&&!largest)){largest=v;largestArea=area;}}" +
                      "return largest;}catch(e){return null;}}" +
                    "var target=findLargestVideo(document);var targetIsIframe=false;" +
                    "if(!target){var iframes=document.querySelectorAll('iframe');" +
                      "for(var i=0;i<iframes.length;i++){try{" +
                        "var iframeDoc=iframes[i].contentDocument||iframes[i].contentWindow.document;" +
                        "var v=findLargestVideo(iframeDoc);" +
                        "if(v){target=iframes[i];targetIsIframe=true;break;}" +
                      "}catch(e){}}}" +
                    "if(!target){var iframes=document.querySelectorAll('iframe');" +
                      "var largestIframe=null;var largestArea=0;" +
                      "for(var i=0;i<iframes.length;i++){" +
                        "var area=iframes[i].offsetWidth*iframes[i].offsetHeight;" +
                        "if(area>largestArea){largestIframe=iframes[i];largestArea=area;}}" +
                      "if(largestIframe&&largestArea>100){target=largestIframe;targetIsIframe=true;}}" +
                    "if(!target)return 'no_video';" +
                    "if(!targetIsIframe&&target.paused){try{target.play();}catch(e){}}" +
                    "target.dataset.pbMaximized='true';" +
                    "var el=target;while(el&&el!==document.body&&el!==document.documentElement){el.dataset.pbAncestor='true';el=el.parentElement;}" +
                    "var style=document.createElement('style');style.id='pb-maximize-style';" +
                    "style.textContent=" +
                      "'body>*:not([data-pb-ancestor]){display:none!important;}'" +
                      "+'[data-pb-ancestor]>*:not([data-pb-ancestor]):not([data-pb-maximized]){display:none!important;}'" +
                      "+'body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;}'" +
                      "+'[data-pb-ancestor]{width:100vw!important;height:100vh!important;max-width:100vw!important;max-height:100vh!important;margin:0!important;padding:0!important;position:fixed!important;top:0!important;left:0!important;overflow:hidden!important;}'" +
                      "+'[data-pb-maximized]{width:100vw!important;height:100vh!important;max-width:100vw!important;max-height:100vh!important;object-fit:contain!important;position:fixed!important;top:0!important;left:0!important;z-index:2147483647!important;background:#000!important;border:none!important;}';" +
                    "document.head.appendChild(style);" +
                    "return targetIsIframe?'ok_iframe':'ok_video';" +
                    "})();"
                engine?.evaluateJavascript(js) { result ->
                    Log.d(TAG, "maximize_video result: $result")
                }
            }
            "restore_video" -> {
                val js = "(function(){" +
                    "var style=document.getElementById('pb-maximize-style');" +
                    "if(style)style.parentNode.removeChild(style);" +
                    "var ancestors=document.querySelectorAll('[data-pb-ancestor]');" +
                    "for(var i=0;i<ancestors.length;i++){delete ancestors[i].dataset.pbAncestor;}" +
                    "var el=document.querySelector('[data-pb-maximized]');" +
                    "if(el){delete el.dataset.pbMaximized;return 'ok';}" +
                    "if(document.fullscreenElement||document.webkitFullscreenElement){" +
                      "var exitFs=document.exitFullscreen||document.webkitExitFullscreen;" +
                      "if(exitFs)exitFs.call(document);return 'ok_native';}" +
                    "return 'not_maximized';})();"
                engine?.evaluateJavascript(js) { result ->
                    Log.d(TAG, "restore_video result: $result")
                }
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
            engine?.loadUrl(url)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { handleRemoteCommand("dpad_up"); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { handleRemoteCommand("dpad_down"); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { handleRemoteCommand("dpad_left"); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { handleRemoteCommand("dpad_right"); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { handleRemoteCommand("dpad_center"); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
             when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showCursorAndResetTimer() {
        cursorView?.visibility = View.VISIBLE
        cursorHideHandler.removeCallbacks(cursorHideRunnable)
        cursorHideHandler.postDelayed(cursorHideRunnable, cursorHideDelayMs)
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) {
            finish()
        }
    }

    override fun onDestroy() {
        cursorHideHandler.removeCallbacks(cursorHideRunnable)
        unregisterReceiver(commandReceiver)
        scope.cancel()
        engine?.destroy()
        engine = null
        // Reset ServerService.activeContext so PairingScreen can open again after a
        // browser session. Same contract as the external Gecko APK uses.
        try {
            val idleIntent = Intent("com.playbridge.player.ACTION_CONTEXT_IDLE").apply {
                setPackage("com.playbridge.player")
            }
            sendBroadcast(idleIntent, "com.playbridge.permission.CONTEXT_IDLE")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send ACTION_CONTEXT_IDLE: ${e.message}")
        }
        super.onDestroy()
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
}
