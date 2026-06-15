package com.playbridge.geckoview.plugin

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.ScreenLength

class GeckoViewEngine(
    private val context: Context,
    private val desktopMode: Boolean = false,
    private val onFullscreen: ((Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GeckoViewEngine"
    }

    private val geckoView = GeckoView(context)
    private val session = GeckoSession()
    private val runtime = GeckoRuntime.getDefault(context)

    private var _canGoBack: Boolean = false

    init {
        runtime.warmUp()
        setupGeckoView()
    }

    fun getView(): View = geckoView

    fun loadUrl(url: String) {
        session.loadUri(url)
    }

    fun reload() {
        session.reload()
    }

    fun goBack() {
        session.goBack()
    }

    fun canGoBack(): Boolean {
        return _canGoBack
    }

    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        // The bridge port lives in PbBridge (registered on the extension at app startup) and is
        // shared across sessions; PbBridge queues if it isn't connected yet.
        PbBridge.eval(script)
        callback?.invoke(null)
    }

    fun destroy() {
        session.close()
    }

    fun scrollBy(dx: Float, dy: Float) {
        // Apply a 3x multiplier to make scrolling feel more responsive on TV.
        // GeckoView supports sub-pixel scrolling via PanZoomController, so we
        // can pass the raw double values directly for maximum smoothness.
        val multiplier = 3.0
        geckoView.panZoomController.scrollBy(
            ScreenLength.fromPixels(dx.toDouble() * multiplier),
            ScreenLength.fromPixels(dy.toDouble() * multiplier)
        )
    }

    fun simulateClick(x: Float, y: Float) {
        // Dispatch touch events to GeckoView
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventTime = downTime

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
        geckoView.isFocusable = true
        geckoView.isFocusableInTouchMode = true
        // Set background to black immediately to prevent the white waiting screen
        geckoView.setBackgroundColor(android.graphics.Color.BLACK)
        
        session.open(runtime)
        geckoView.setSession(session)

        // Settings
        session.settings.apply {
            useTrackingProtection = true
            userAgentMode = if (desktopMode) {
                org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            } else {
                org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            }
        }

        // PB Bridge + uBlock extensions are ensured (and the bridge delegate registered) once at
        // startup in PlayBridgeBrowserApplication; the runtime is shared, so nothing to do here.

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                onFullscreen?.invoke(fullScreen)
            }

            override fun onExternalResponse(
                session: GeckoSession,
                response: org.mozilla.geckoview.WebResponse
            ) {
                // Handle file downloads from GeckoView
                try {
                    val url = response.uri
                    val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))

                    response.headers.forEach { entry ->
                        request.addRequestHeader(entry.key, entry.value)
                    }

                    val mimeType = response.headers["Content-Type"] ?: "application/octet-stream"
                    val contentDisposition = response.headers["Content-Disposition"]

                    var fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)

                    request.setMimeType(mimeType)
                    request.setTitle(fileName)
                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)

                    val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    dm.enqueue(request)

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, "Downloading file...", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start GeckoView download", e)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, "Download failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Navigation delegate
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            }
        }
    }
}
