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
import com.playbridge.shared.browser.browserFullscreenReported
import com.playbridge.shared.logging.redactUrlForLog
import com.playbridge.shared.protocol.statusWithFullscreen

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
        const val ACTION_USER_AGENT_CHANGED = "com.playbridge.player.ACTION_USER_AGENT_CHANGED"
        const val EXTRA_USER_AGENT_VALUE = "extra_user_agent_value"

        // Fullscreen video-control D-pad steps
        private const val SEEK_STEP_SECONDS = 10
        private const val VOLUME_STEP = 0.1
    }

    private var engine: SystemWebViewEngine? = null
    private var canGoBack = false
    private var currentUrl: String? = null
    private var explicitlyClosing = false
    private var replacedByAnotherMode = false

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
    private var lastBrowserStatusJson: String? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null
    private var contentContainer: FrameLayout? = null

    // Custom overlay support controlled by user scripts
    private var customOverlayView: android.widget.FrameLayout? = null
    private var customOverlayTextView: android.widget.TextView? = null
    private val customOverlayHideHandler = Handler(Looper.getMainLooper())
    private val customOverlayHideRunnable = Runnable { customOverlayView?.visibility = android.view.View.GONE }

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
                ACTION_USER_AGENT_CHANGED -> {
                    val value = intent.getStringExtra(EXTRA_USER_AGENT_VALUE)
                    engine?.setUserAgentOverride(value)
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
            Log.d(TAG, "Loading URL: ${redactUrlForLog(url)}")
            currentUrl = url
            engine?.loadUrl(url)
        } else {
            Log.d(TAG, "No URL provided, loading default")
            currentUrl = "https://www.google.com"
            engine?.loadUrl("https://www.google.com")
        }

        // Handle back press
        onBackPressedDispatcher.addCallback(this) {
            if (reportedFullscreen()) {
                exitAnyFullscreen()
            } else if (engine?.canGoBack() == true) {
                engine?.goBack()
            } else {
                closeBrowser()
            }
        }

        // Register broadcast receiver for remote commands
        val filter = IntentFilter().apply {
            addAction(ACTION_MOUSE)
            addAction(ACTION_REMOTE)
            addAction(ACTION_BROWSER_CONTROL)
            addAction(ACTION_USER_AGENT_CHANGED)
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
        val userAgentOverride = intent.getStringExtra(EXTRA_USER_AGENT_VALUE)?.takeIf { it.isNotBlank() }

        Log.d(TAG, "Initializing System WebView Engine (desktop=$desktopMode, uaOverride=${userAgentOverride != null})")
        engine = SystemWebViewEngine(
            this,
            adBlocker,
            desktopMode = desktopMode,
            userAgentOverride = userAgentOverride,
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

        // Render native feedback for video-control command results (incl. those from
        // cross-origin iframe frames, delivered asynchronously over the message channel).
        engine?.onVideoResult = { kind, value -> renderVideoResult(kind, value) }

        // Forward the controller's playback status to the phone (drives its scrubber).
        // Same "status" message the native player emits, so the phone parses it as-is.
        engine?.onVideoStatus = { json -> publishBrowserStatus(json) }
        engine?.onFullscreenFlagsChanged = { publishBrowserStatus(null) }

        // Custom overlay support controlled by user scripts
        engine?.onVideoOverlay = { visible, text, style, bgColor, textColor, autoDismissMs ->
            showCustomVideoOverlay(visible, text, style, bgColor, textColor, autoDismissMs)
        }

        // Auto-unmute: when a muted video becomes the active control target (in-page
        // expand or native fullscreen), dispatch a real tap to restore audio.
        engine?.onRequestUnmuteTap = { autoUnmuteActiveVideo() }

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
        publishBrowserStatus(null)
    }

    /**
     * Dispatch a real centre tap to unmute the active video — the trusted user gesture
     * that injected JS can't supply. Driven by the engine's [SystemWebViewEngine.onRequestUnmuteTap]
     * (fired when a muted video becomes active), so it works for native fullscreen AND
     * YouTube's in-page expand alike. The video dominates the screen when control is
     * active, so a centre tap lands on it (and on YouTube takes its "tap to unmute").
     */
    private fun autoUnmuteActiveVideo() {
        runOnUiThread {
            if (engine?.isActiveVideoMuted() != true) return@runOnUiThread
            val dm = resources.displayMetrics
            Log.d(TAG, "auto-unmute: dispatching real centre tap")
            simulateClickOnActiveView(dm.widthPixels / 2f, dm.heightPixels / 2f)
            // After the tap establishes activation, a JS unmute covers generic players
            // that lack YouTube's tap-to-unmute affordance — and resumes playback if the
            // tap paused it. Delay lets the tap's effect register before that check.
            cursorHideHandler.postDelayed({ engine?.videoUnmute() }, 150)
            showCustomVideoOverlay(visible = true, text = "🔊", style = "minimal", bgColorStr = null, textColorStr = null, autoDismissMs = 1500)
        }
    }

    private fun reportedFullscreen(): Boolean = browserFullscreenReported(
        fullscreenView != null,
        engine?.isFrameFillActive() == true,
        engine?.isNativeClaimActive() == true,
    )

    /** Phone restore and TV Back leave native chrome and the in-frame fill together. */
    private fun exitAnyFullscreen() {
        val native = fullscreenView != null
        engine?.videoRestore()
        if (native) exitFullscreen()
    }

    private fun exitFullscreen() {
        if (fullscreenView == null) {
            engine?.noteChromeHidden()
            return
        }

        fullscreenContainer?.removeView(fullscreenView)
        fullscreenView = null

        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null

        fullscreenContainer?.visibility = View.GONE
        contentContainer?.visibility = View.VISIBLE

        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine?.noteChromeHidden()
        publishBrowserStatus(null)
    }

    private fun publishBrowserStatus(json: String?) {
        if (json != null) lastBrowserStatusJson = json
        val base = lastBrowserStatusJson
            ?: """{"type":"status","state":"idle","position":0,"duration":0}"""
        com.playbridge.player.server.ServerService.broadcastStatus(
            this,
            statusWithFullscreen(base, reportedFullscreen()),
        )
    }

    /**
     * Dispatch a single touch action (ACTION_DOWN, ACTION_MOVE, ACTION_UP) to the correct
     * target view based on fullscreen state.
     */
    private fun dispatchTouchToActiveView(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = android.view.MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
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
                // Scroll the element under the cursor, not just the page — this is what
                // makes inner overflow regions (and horizontal carousels) respond.
                showCursorAndResetTimer()
                engine?.wheelScrollAt(cursorX, cursorY, dx, dy)
            }
            "zoom" -> {
                // Pinch-zoom from the phone touchpad; dx carries the relative scale factor.
                val view = engine?.getView() ?: return
                engine?.zoomBy(dx, cursorX / view.width.coerceAtLeast(1), cursorY / view.height.coerceAtLeast(1))
            }
            "reset" -> engine?.resetPageZoom()
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



    private fun showCustomVideoOverlay(
        visible: Boolean,
        text: String,
        style: String,
        bgColorStr: String?,
        textColorStr: String?,
        autoDismissMs: Long = 0L
    ) {
        runOnUiThread {
            customOverlayHideHandler.removeCallbacks(customOverlayHideRunnable)
            if (!visible) {
                customOverlayView?.visibility = android.view.View.GONE
                return@runOnUiThread
            }

            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            var ov = customOverlayView
            var tv = customOverlayTextView

            if (ov == null || tv == null) {
                ov = android.widget.FrameLayout(this)
                tv = android.widget.TextView(this).apply {
                    textSize = 24f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.CENTER
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(dp(30), dp(22), dp(30), dp(22))
                }
                ov.addView(
                    tv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = android.view.Gravity.CENTER }
                )
                rootContainer.addView(ov)
                customOverlayView = ov
                customOverlayTextView = tv
            }

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            when (style) {
                "fullscreen" -> {
                    lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                    lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                    lp.gravity = android.view.Gravity.CENTER
                }
                "half-screen" -> {
                    lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                    val displayMetrics = resources.displayMetrics
                    lp.height = displayMetrics.heightPixels / 2
                    lp.gravity = android.view.Gravity.BOTTOM
                }
                "minimal" -> {
                    lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
                    lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
                    lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                    lp.bottomMargin = dp(40)
                }
            }
            ov.layoutParams = lp

            val defaultBgColor = when (style) {
                "fullscreen" -> android.graphics.Color.BLACK
                "half-screen" -> android.graphics.Color.parseColor("#E6121416")
                else -> android.graphics.Color.parseColor("#D2141618")
            }
            val bgColor = if (bgColorStr != null) {
                try { android.graphics.Color.parseColor(bgColorStr) } catch (e: Exception) { defaultBgColor }
            } else {
                defaultBgColor
            }

            if (style == "minimal" || style == "half-screen") {
                ov.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(bgColor)
                }
                if (style == "minimal") {
                    lp.leftMargin = dp(20)
                    lp.rightMargin = dp(20)
                }
            } else {
                ov.background = null
                ov.setBackgroundColor(bgColor)
            }

            val textColor = if (textColorStr != null) {
                try { android.graphics.Color.parseColor(textColorStr) } catch (e: Exception) { android.graphics.Color.WHITE }
            } else {
                android.graphics.Color.WHITE
            }
            tv.setTextColor(textColor)
            tv.text = text

            ov.visibility = android.view.View.VISIBLE
            ov.bringToFront()
            if (cursorView?.visibility == android.view.View.VISIBLE) {
                cursorView?.bringToFront()
            }

            if (autoDismissMs > 0) {
                customOverlayHideHandler.postDelayed(customOverlayHideRunnable, autoDismissMs)
            }
        }
    }

    private fun fmtTime(totalSec: Double): String {
        if (!totalSec.isFinite() || totalSec < 0) return "0:00"
        val s = totalSec.toInt()
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
        else String.format("%d:%02d", m, sec)
    }

    private fun progressBar(frac: Double, slots: Int = 16): String {
        val filled = Math.round(frac.coerceIn(0.0, 1.0) * slots).toInt()
        return "█".repeat(filled) + "░".repeat(slots - filled)
    }

    /**
     * Render native feedback for a command result reported by the engine
     * ([SystemWebViewEngine.onVideoResult]). value is already unquoted.
     */
    private fun renderVideoResult(kind: String, value: String) {
        when (kind) {
            "toggle" -> when (value) {
                "playing" -> showCustomVideoOverlay(visible = true, text = "▶", style = "minimal", bgColorStr = null, textColorStr = null, autoDismissMs = 1500)
                "paused" -> showCustomVideoOverlay(visible = true, text = "❙❙", style = "minimal", bgColorStr = null, textColorStr = null, autoDismissMs = 1500)
            }
            "seek" -> {
                val parts = value.split(",")
                if (parts.size != 2) return
                val t = parts[0].toDoubleOrNull() ?: return
                val d = parts[1].toDoubleOrNull() ?: 0.0
                val frac = if (d > 0) t / d else 0.0
                showCustomVideoOverlay(
                    visible = true,
                    text = "${fmtTime(t)}   /   ${fmtTime(d)}\n${progressBar(frac)}",
                    style = "minimal",
                    bgColorStr = null,
                    textColorStr = null,
                    autoDismissMs = 1500
                )
            }
            "vol" -> {
                val vol = value.toDoubleOrNull() ?: return
                showCustomVideoOverlay(
                    visible = true,
                    text = "🔊  ${Math.round(vol * 100)}%\n${progressBar(vol)}",
                    style = "minimal",
                    bgColorStr = null,
                    textColorStr = null,
                    autoDismissMs = 1500
                )
            }
            "rate" -> {
                val r = value.toDoubleOrNull() ?: return
                showCustomVideoOverlay(
                    visible = true,
                    text = "${r.toString().removeSuffix(".0")}×",
                    style = "minimal",
                    bgColorStr = null,
                    textColorStr = null,
                    autoDismissMs = 1500
                )
            }
        }
    }

    private fun handleRemoteCommand(key: String?) {
        Log.d(TAG, if (key?.startsWith("text:") == true) "Remote command: text" else "Remote command: $key")

        // A focused field still receives text in native fullscreen. A fullscreen
        // video with no input or contenteditable target returns no_focus.
        if (key != null && key.startsWith("text:")) {
            injectFocusedText(key.removePrefix("text:"))
            return
        }
        if (key == "key_enter") {
            submitFocusedInput()
            return
        }

        if (fullscreenView != null) {
            when (key) {
                "back" -> {
                    runOnUiThread {
                        exitAnyFullscreen()
                    }
                }
                // In fullscreen the D-pad drives the playing <video> via the injected
                // controller (window.__pbvc); feedback is rendered natively over the
                // WebView. Site chrome (e.g. YouTube) is pointer-driven and won't show.
                "dpad_center" -> engine?.videoToggle()
                "dpad_left" -> engine?.videoSeek(-SEEK_STEP_SECONDS)
                "dpad_right" -> engine?.videoSeek(SEEK_STEP_SECONDS)
                "dpad_up" -> engine?.videoVolume(VOLUME_STEP)
                "dpad_down" -> engine?.videoVolume(-VOLUME_STEP)
            }
            return
        }

        // CSS fill has no custom view. Back restores the video's styles and does not
        // walk history while that fill is what the phone is showing as fullscreen.
        if (reportedFullscreen() && key == "back") {
            exitAnyFullscreen()
            return
        }

        // Not in native (HTML5) fullscreen, but a screen-dominating video is on
        // screen (e.g. m.youtube.com's in-page expand). Intercept the playback keys
        // for video control; let everything else (back, etc.) navigate normally.
        // The phone touchpad still drives the cursor via ACTION_MOUSE.
        if (engine?.isVideoControlActive() == true) {
            when (key) {
                "dpad_center" -> { engine?.videoToggle(); return }
                "dpad_left" -> { engine?.videoSeek(-SEEK_STEP_SECONDS); return }
                "dpad_right" -> { engine?.videoSeek(SEEK_STEP_SECONDS); return }
                "dpad_up" -> { engine?.videoVolume(VOLUME_STEP); return }
                "dpad_down" -> { engine?.videoVolume(-VOLUME_STEP); return }
            }
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
                    closeBrowser()
                }
            }
            "home" -> {
                // Home always exits the browser. Drop an in-frame fill first so the
                // phone's fullscreen flag matches the page we are leaving.
                if (engine?.isFrameFillActive() == true || engine?.isNativeClaimActive() == true) {
                    engine?.videoRestore()
                }
                closeBrowser()
            }
        }
    }

    /** Write [b64] (base64 UTF-8) into the focused field, including one inside an iframe. */
    private fun injectFocusedText(b64: String) {
        engine?.injectFocusedText(b64)
    }

    /** Fire Enter on the focused field and submit its form when it has one. */
    private fun submitFocusedInput() {
        engine?.submitFocusedInput()
    }

    private fun handleBrowserControlCommand(action: String?) {
        if (action == "dismiss") {
            // The replacement owns the context; teardown must not announce idle.
            replacedByAnotherMode = true
            engine?.pauseForBackground()
            finish()
            return
        }
        Log.d(TAG, "Browser control: $action")
        // Absolute seek from the phone scrubber (value in ms) — prefix, so handled
        // before the exact-match when below.
        if (action != null && action.startsWith("seek_to:")) {
            action.removePrefix("seek_to:").toLongOrNull()?.let { ms ->
                engine?.videoSeekTo(ms / 1000.0)
            }
            return
        }
        when (action) {
            "video_target_cycle" -> {
                // Manual override: pin the D-pad to the next on-screen video (main or
                // any iframe). Auto-selection resumes when that frame goes inactive.
                engine?.cycleVideoTarget()
                showCustomVideoOverlay(visible = true, text = "Switched video source", style = "minimal", bgColorStr = null, textColorStr = null, autoDismissMs = 1500)
            }
            "toggle_play" -> engine?.videoToggle()
            "video_unmute" -> {
                // Same guarded path as auto-unmute: only taps when the video is actually
                // muted, so it never pauses an already-audible video. Unmuting needs a
                // *trusted* gesture (a real tap) that injected JS can't supply.
                when (engine?.isActiveVideoMuted()) {
                    true -> autoUnmuteActiveVideo()
                    false -> showCustomVideoOverlay(visible = true, text = "Audio is on", style = "minimal", bgColorStr = null, textColorStr = null, autoDismissMs = 1500)
                    null -> showCustomVideoOverlay(visible = true, text = "No active video", style = "minimal", bgColorStr = null, textColorStr = null, autoDismissMs = 1500)
                }
            }
            "forward" -> engine?.goForward()
            "refresh" -> engine?.reload()
            "maximize_video" -> engine?.videoMaximize(fullscreenView != null)
            "restore_video" -> exitAnyFullscreen()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrEmpty()) {
            Log.d(TAG, "Loading new URL: ${redactUrlForLog(url)}")
            currentUrl = url
            engine?.loadUrl(url)
        }
    }

    private fun closeBrowser() {
        explicitlyClosing = true
        finish()
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

    override fun onResume() {
        super.onResume()
        if (isFinishing || replacedByAnotherMode) return
        engine?.resumeAfterBackground()
        // Claim the server context for the browser whenever we're foregrounded — so a
        // context_query reports "browser" regardless of how this activity was launched,
        // including when opened directly from TV settings.
        com.playbridge.player.server.ServerService.notifyContextBrowser(this)
    }

    override fun onStop() {
        if (!isFinishing && !isChangingConfigurations) {
            engine?.pauseForBackground()
        }
        super.onStop()
    }

    override fun onDestroy() {
        cursorHideHandler.removeCallbacks(cursorHideRunnable)
        customOverlayHideHandler.removeCallbacks(customOverlayHideRunnable)
        unregisterReceiver(commandReceiver)
        scope.cancel()
        engine?.destroy()
        engine = null
        // Reset ServerService.activeContext so PairingScreen can open again after a
        // browser session. Same contract as the external Gecko APK uses.
        try {
            if (!replacedByAnotherMode && (explicitlyClosing || isFinishing)) {
                val idleIntent = Intent("com.playbridge.player.ACTION_CONTEXT_IDLE").apply {
                    setPackage("com.playbridge.player")
                }
                sendBroadcast(idleIntent, "com.playbridge.permission.CONTEXT_IDLE")
            }
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
