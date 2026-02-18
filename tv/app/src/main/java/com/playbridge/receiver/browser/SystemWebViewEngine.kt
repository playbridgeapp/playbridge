package com.playbridge.receiver.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
class SystemWebViewEngine(
    private val context: Context,
    private val adBlocker: AdBlocker,
    private val onFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    private val onExitFullscreen: () -> Unit
) : BrowserEngine {

    companion object {
        private const val TAG = "SystemWebViewEngine"
    }

    private val webView: WebView = WebView(context)
    private var canGoBack = false
    private var currentUrl: String? = null

    init {
        setupWebView()
    }

    override fun getView(): View = webView

    override fun loadUrl(url: String) {
        currentUrl = url
        webView.loadUrl(url)
    }

    override fun reload() {
        webView.reload()
    }

    override fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    override fun canGoBack(): Boolean = canGoBack

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script, callback)
    }

    override fun destroy() {
        webView.destroy()
    }

    override fun scrollBy(dx: Int, dy: Int) {
        webView.scrollBy(dx, dy)
    }

    override fun simulateClick(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        val downEvent = MotionEvent.obtain(
            downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0
        )
        val upEvent = MotionEvent.obtain(
            downTime, eventTime + 100, MotionEvent.ACTION_UP, x, y, 0
        )

        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()
    }

    private fun setupWebView() {
        webView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

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

                // Use standard Android Mobile User Agent to mimic a phone
                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                // Optimize for media streaming
                cacheMode = WebSettings.LOAD_DEFAULT
                databaseEnabled = true

                // Enable off-screen rendering for smoother video
                offscreenPreRaster = true

                // Disable popups
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }

            // Enable hardware acceleration for video
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Set high renderer priority for better video performance
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }

            // Enable third-party cookies (required for many iframe embeds)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // Set WebViewClient with ad blocking
            webViewClient = AdBlockingWebViewClient()

            // Set WebChromeClient for popups and fullscreen
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    Log.d(TAG, "Blocked popup window creation (isUserGesture=$isUserGesture)")
                    return false
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    Log.d(TAG, "onShowCustomView - entering fullscreen")
                    if (view != null) {
                        onFullscreen(view, callback ?: object : CustomViewCallback {
                            override fun onCustomViewHidden() {}
                        })
                    }
                }

                override fun onHideCustomView() {
                    Log.d(TAG, "onHideCustomView - exiting fullscreen")
                    onExitFullscreen()
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    Log.d(TAG, "onPermissionRequest: ${request?.resources?.joinToString()}")
                    request?.deny()
                }
            }
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

            // Block non-HTTP schemes
            val scheme = request.url?.scheme
            if (scheme != "http" && scheme != "https") {
                Log.d(TAG, "Blocked non-HTTP scheme: $scheme")
                return true
            }

            // Use AdBlocker
            if (adBlocker.shouldBlock(url, currentUrl, AdBlocker.TYPE_SUBDOCUMENT)) {
                Log.d(TAG, "Blocked navigation: $url")
                return true
            }

            // Detect rapid redirects
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

            val resourceType = getResourceType(request)

            if (adBlocker.shouldBlock(url, currentUrl, resourceType)) {
                return createEmptyResponse()
            }

            return null
        }

        private fun getResourceType(request: WebResourceRequest): Int {
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
}
