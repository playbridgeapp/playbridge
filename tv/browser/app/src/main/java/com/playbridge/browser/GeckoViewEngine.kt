package com.playbridge.browser

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

class GeckoViewEngine(
    private val context: Context,
    private val adBlocker: AdBlocker, // Kept for consistency, though GeckoView has own blocking
    private val desktopMode: Boolean = false,
    private val onFullscreen: ((Boolean) -> Unit)? = null
) : BrowserEngine {

    companion object {
        private const val TAG = "GeckoViewEngine"
    }

    private val geckoView = GeckoView(context)
    private val session = GeckoSession()
    private val runtime = GeckoRuntime.getDefault(context)

    private var _canGoBack: Boolean = false

    // WebExtension bridge port for JS evaluation
    private var bridgePort: WebExtension.Port? = null

    init {
        runtime.warmUp()
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
        return _canGoBack
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        val port = bridgePort
        if (port != null) {
            try {
                val message = JSONObject()
                message.put("type", "eval")
                message.put("code", script)
                port.postMessage(message)
                // Note: The callback is currently invoked with null immediately.
                // In a perfect world, we'd wait for a response, but it's asynchronous.
                callback?.invoke(null)
            } catch (e: Exception) {
                Log.e(TAG, "Bridge eval error", e)
                callback?.invoke(null)
            }
        } else {
            try {
                val clean = script.trim()
                val encoded = android.util.Base64.encodeToString(clean.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                // Use Base64 to bypass all escaping issues
                session.loadUri("javascript:void(eval(decodeURIComponent(escape(window.atob('$encoded')))))")
            } catch (e: Exception) {
                Log.e(TAG, "JS fallback error", e)
            }
            callback?.invoke(null)
        }
    }

    override fun destroy() {
        bridgePort = null
        session.close()
    }

    override fun scrollBy(dx: Float, dy: Float) {
        evaluateJavascript("window.scrollBy($dx, $dy)", null)
    }

    override fun simulateClick(x: Float, y: Float) {
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

    /**
     * Register message delegate for the PB Bridge extension.
     * For background script messaging, the delegate is set directly on the extension object.
     */
    private fun registerBridgeDelegate(ext: WebExtension) {
        ext.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    Log.i(TAG, "PB Bridge port connected")
                    bridgePort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            Log.d(TAG, "PB Bridge response: $message")
                        }
                        override fun onDisconnect(port: WebExtension.Port) {
                            Log.d(TAG, "PB Bridge port disconnected")
                            if (bridgePort === port) {
                                bridgePort = null
                            }
                        }
                    })
                }
            },
            "pbBridge"
        )
        Log.d(TAG, "PB Bridge delegate registered")
    }

    private fun setupGeckoView() {
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

        // Install uBlock Origin from bundled assets
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/extensions/ublock_origin/",
            "uBlock0@raymondhill.net"
        ).accept(
            { _ ->
                Log.i(TAG, "Successfully installed uBlock Origin extension")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "uBlock Origin Protected", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            { e -> Log.e(TAG, "Failed to install uBlock Origin extension", e) }
        )

        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/extensions/pb_bridge/",
            "pb-bridge@playbridge.com"
        ).accept(
            { ext ->
                if (ext != null) {
                    Log.i(TAG, "PB Bridge extension ensured")
                    registerBridgeDelegate(ext)
                } else {
                    Log.w(TAG, "PB Bridge ensureBuiltIn returned null — bridge unavailable")
                }
            },
            { e -> Log.e(TAG, "Failed to install PB Bridge extension", e) }
        )

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
