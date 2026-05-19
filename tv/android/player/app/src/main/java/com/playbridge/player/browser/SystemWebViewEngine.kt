package com.playbridge.player.browser

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
    private val desktopMode: Boolean = false,
    private val onFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    private val onExitFullscreen: () -> Unit,
    private val onEngineRecreateRequired: (url: String?) -> Unit = {}
) {

    companion object {
        private const val TAG = "SystemWebViewEngine"
    }

    private val webView: WebView = WebView(context)
    private var canGoBack = false
    private var currentUrl: String? = null
    
    // Accumulators for fractional scroll deltas to prevent loss of small movements
    private var scrollAccumulatorX = 0f
    private var scrollAccumulatorY = 0f

    init {
        setupWebView()
    }

    fun getView(): View = webView

    fun loadUrl(url: String) {
        currentUrl = url
        webView.loadUrl(url)
    }

    fun reload() {
        webView.reload()
    }

    fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    fun canGoBack(): Boolean = canGoBack

    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null) {
        webView.evaluateJavascript(script, callback)
    }

    fun destroy() {
        webView.destroy()
    }

    fun scrollBy(dx: Float, dy: Float) {
        scrollAccumulatorX += dx
        scrollAccumulatorY += dy
        
        val scrollX = scrollAccumulatorX.toInt()
        val scrollY = scrollAccumulatorY.toInt()
        
        if (scrollX != 0 || scrollY != 0) {
            webView.scrollBy(scrollX, scrollY)
            scrollAccumulatorX -= scrollX
            scrollAccumulatorY -= scrollY
        }
    }

    fun simulateClick(x: Float, y: Float) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventTime = downTime

        val downEvent = android.view.MotionEvent.obtain(
            downTime, eventTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0
        )
        val upEvent = android.view.MotionEvent.obtain(
            downTime, eventTime + 100, android.view.MotionEvent.ACTION_UP, x, y, 0
        )

        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()
    }

    /**
     * Anti-popup/redirect JavaScript injected on every page load.
     * Overrides window.open, neutralizes common redirect tricks,
     * and uses MutationObserver to remove dynamically injected ad iframes.
     */
    private val antiPopupScript = """
        (function() {
            'use strict';
            if (window.__pbAdblockInjected) return;
            window.__pbAdblockInjected = true;

            // 1. Block window.open() entirely
            window.open = function() { return null; };

            // 2. Block popup techniques via addEventListener override
            var origAddEventListener = EventTarget.prototype.addEventListener;
            EventTarget.prototype.addEventListener = function(type, fn, opts) {
                // Block click handlers that try to open new windows
                if (type === 'click' && this === document) {
                    var fnStr = fn.toString();
                    if (fnStr.indexOf('window.open') !== -1 ||
                        fnStr.indexOf('window.location') !== -1) {
                        return; // Don't register suspicious click handlers
                    }
                }
                return origAddEventListener.call(this, type, fn, opts);
            };

            // 3. Remove target="_blank" from all links (prevents popup navigation)
            function cleanLinks() {
                var links = document.querySelectorAll('a[target="_blank"]');
                for (var i = 0; i < links.length; i++) {
                    links[i].removeAttribute('target');
                }
            }

            // 4. Block meta-refresh redirects
            function blockMetaRefresh() {
                var metas = document.querySelectorAll('meta[http-equiv="refresh"]');
                for (var i = 0; i < metas.length; i++) {
                    metas[i].parentNode.removeChild(metas[i]);
                }
            }

            // 5. MutationObserver to catch dynamically injected ad elements
            var observer = new MutationObserver(function(mutations) {
                for (var i = 0; i < mutations.length; i++) {
                    var nodes = mutations[i].addedNodes;
                    for (var j = 0; j < nodes.length; j++) {
                        var node = nodes[j];
                        if (node.nodeType !== 1) continue;

                        // Remove suspicious iframes
                        if (node.tagName === 'IFRAME') {
                            var src = (node.src || '').toLowerCase();
                            if (src.indexOf('about:blank') !== -1 ||
                                src === '' ||
                                src.indexOf('ad') !== -1 ||
                                src.indexOf('pop') !== -1 ||
                                src.indexOf('click') !== -1) {
                                // Check if it's a zero/tiny size (hidden ad iframe)
                                var style = node.getAttribute('style') || '';
                                if (style.indexOf('display:none') !== -1 ||
                                    style.indexOf('width:0') !== -1 ||
                                    style.indexOf('height:0') !== -1 ||
                                    style.indexOf('position:absolute') !== -1 ||
                                    node.width === '0' || node.height === '0' ||
                                    node.width === '1' || node.height === '1') {
                                    node.parentNode.removeChild(node);
                                }
                            }
                        }

                        // Remove meta-refresh in dynamically added content
                        if (node.tagName === 'META') {
                            var httpEquiv = (node.getAttribute('http-equiv') || '').toLowerCase();
                            if (httpEquiv === 'refresh') {
                                node.parentNode.removeChild(node);
                            }
                        }

                        // Clean links in newly added content
                        if (node.querySelectorAll) {
                            var newLinks = node.querySelectorAll('a[target="_blank"]');
                            for (var k = 0; k < newLinks.length; k++) {
                                newLinks[k].removeAttribute('target');
                            }
                        }
                    }
                }
            });

            // Start observing when DOM is ready
            function startObserving() {
                if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true });
                    cleanLinks();
                    blockMetaRefresh();
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        observer.observe(document.body, { childList: true, subtree: true });
                        cleanLinks();
                        blockMetaRefresh();
                    });
                }
            }
            startObserving();
        })();
    """.trimIndent()

    private fun setupWebView() {
        webView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(android.graphics.Color.BLACK)
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

                // User agent: desktop Chrome on Linux or mobile Android, driven by desktopMode flag
                userAgentString = if (desktopMode) {
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                // Optimize for media streaming
                cacheMode = WebSettings.LOAD_DEFAULT
                databaseEnabled = true

                // Enable off-screen rendering for smoother video
                offscreenPreRaster = true

                // Block JS-initiated popups
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true)
            }

            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Set high renderer priority for better video performance
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }

            // Enable third-party cookies (required for many iframe embeds)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // Handle Downloads via Android DownloadManager
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                try {
                    val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                    request.setMimeType(mimeType)

                    // Extract filename
                    val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                    request.setTitle(fileName)

                    // Add cookie if needed
                    val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
                    request.addRequestHeader("cookie", cookies)
                    request.addRequestHeader("User-Agent", userAgent)

                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)

                    val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    dm.enqueue(request)

                    android.widget.Toast.makeText(context, "Downloading file...", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to start download", e)
                    android.widget.Toast.makeText(context, "Download failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }

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
                    // Block ALL popup window creation - this is the secure approach
                    // with setSupportMultipleWindows(true)
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

        // Known popup/ad domain patterns for additional redirect detection
        private val popupDomainPatterns = listOf(
            "popads", "popcash", "popunder", "popup",
            "clickadu", "propeller", "adcash", "exoclick",
            "trafficjunky", "juicyads", "revcontent", "mgid",
            "bidvertiser", "zedo", "adf.ly", "sh.st",
            "bc.vc", "ouo.io", "shorte.st", "linkbucks"
        )

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)

            // Inject anti-popup script once the new document's JS context is ready.
            // The __pbAdblockInjected guard prevents double-injection on soft navigations.
            view?.evaluateJavascript(antiPopupScript, null)

            // Inject cosmetic filters (element hiding CSS)
            val cosmeticCss = adBlocker.getCosmeticFilterCss()
            if (cosmeticCss.isNotEmpty()) {
                val escapedCss = cosmeticCss
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", " ")
                    .replace("\r", "")
                val injectCssScript = """
                    (function() {
                        if (document.getElementById('pb-cosmetic-filters')) return;
                        var style = document.createElement('style');
                        style.id = 'pb-cosmetic-filters';
                        style.textContent = '$escapedCss';
                        (document.head || document.documentElement).appendChild(style);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(injectCssScript, null)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            canGoBack = view?.canGoBack() ?: false
            if (!isReload && url != null) {
                currentUrl = url
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            Log.e(TAG, "WebView render process crashed (didCrash=${detail?.didCrash()}, priority=${detail?.rendererPriorityAtExit()})")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onEngineRecreateRequired(currentUrl)
            }
            return true // We handled it; don't let the system kill the app process
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

            // Use AdBlocker with popup-aware blocking for navigations
            if (adBlocker.shouldBlockNavigation(url, currentUrl)) {
                Log.d(TAG, "Blocked navigation (popup/redirect rule): $url")
                return true
            }

            // Detect rapid redirects — only for CROSS-ORIGIN navigations
            // Same-origin navigations (SPA routing, hash changes) are always allowed
            val currentHost = Uri.parse(currentUrl)?.host
            val isSameOrigin = currentHost != null && host == currentHost
            val isHashChange = url.substringBefore('#') == (currentUrl ?: "").substringBefore('#')

            if (!isSameOrigin && !isHashChange) {
                val now = System.currentTimeMillis()
                if (now - lastNavigationTime < 1000) {
                    navigationCount++
                    if (navigationCount > 3) {
                        Log.d(TAG, "Blocked rapid cross-origin redirect chain: $url")
                        navigationCount = 0
                        return true
                    }
                } else {
                    navigationCount = 0
                }
                lastNavigationTime = now
            }

            // Block cross-domain redirects that look suspicious
            if (!isSameOrigin && currentHost != null) {
                // Check against known popup/ad domain patterns
                val hostLower = host.lowercase()
                if (popupDomainPatterns.any { hostLower.contains(it) }) {
                    Log.d(TAG, "Blocked known popup domain: $host")
                    return true
                }

                // Check suspicious URL patterns
                if (url.contains("redirect=") ||
                    url.contains("goto=") ||
                    url.contains("out.php") ||
                    url.contains("click.php") ||
                    url.contains("/cgi-bin/") ||
                    url.contains("popunder") ||
                    url.contains("popad") ||
                    url.contains("/go/") && (url.contains("ad") || url.contains("click")) ||
                    url.contains("track.php")) {
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
