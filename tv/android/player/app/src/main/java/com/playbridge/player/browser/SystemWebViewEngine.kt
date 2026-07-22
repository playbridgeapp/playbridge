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
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
class SystemWebViewEngine(
    private val context: Context,
    private val adBlocker: AdBlocker,
    private val desktopMode: Boolean = false,
    private var userAgentOverride: String? = null,
    private val onFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    private val onExitFullscreen: () -> Unit,
    private val onEngineRecreateRequired: (url: String?) -> Unit = {},
    private val onDownloadStarted: (downloadId: Long, fileName: String) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "SystemWebViewEngine"
    }

    private val webView: WebView = object : WebView(context) {
        // When the user enables "Hide on-screen keyboard" (Settings → Browser), suppress the IME
        // by refusing the input connection. The field still focuses and receives text injected
        // from the phone keyboard (via JS), so the TV keyboard never steals the cursor focus.
        override fun onCreateInputConnection(
            outAttrs: android.view.inputmethod.EditorInfo
        ): android.view.inputmethod.InputConnection? {
            val hide = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                .getBoolean("hide_soft_keyboard", false)
            return if (hide) null else super.onCreateInputConnection(outAttrs)
        }
    }
    private var canGoBack = false
    private var currentUrl: String? = null
    
    // Accumulators for fractional scroll deltas to prevent loss of small movements
    private var scrollAccumulatorX = 0f
    private var scrollAccumulatorY = 0f

    // NOTE: setupWebView() is invoked from an init block at the BOTTOM of the class,
    // after all property initializers (e.g. the videoControlScript lazy delegate),
    // because setupWebView() reads them. An init block here would run too early.

    fun getView(): View = webView
    fun getCurrentUrl(): String? = currentUrl

    fun loadUrl(url: String) {
        currentUrl = url
        webView.loadUrl(url)
    }

    fun reload() {
        webView.reload()
    }

    /** Resolve the literal UA string to apply: [userAgentOverride] wins, else desktop/mobile default. */
    private fun resolveUserAgent(): String {
        userAgentOverride?.let { return it }
        return if (desktopMode) {
            // Desktop spoofing still mismatches the mobile client hints, so it stays
            // captcha-prone; at least track the device's real Chrome major version.
            val real = WebSettings.getDefaultUserAgent(context)
            val chromeVersion = Regex("Chrome/([\\d.]+)").find(real)?.groupValues?.get(1)
                ?: "120.0.0.0"
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
        } else {
            WebSettings.getDefaultUserAgent(context)
        }
    }

    /** Live update from the phone's User Agent manager — applies and reloads immediately. */
    fun setUserAgentOverride(value: String?) {
        userAgentOverride = value?.takeIf { it.isNotBlank() }
        webView.settings.userAgentString = resolveUserAgent()
        webView.reload()
    }

    fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    fun canGoBack(): Boolean = canGoBack

    fun goForward() {
        if (webView.canGoForward()) {
            webView.goForward()
        }
    }

    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null) {
        webView.evaluateJavascript(script, callback)
    }

    /** Pause page media and rendering while the user is in Android TV settings or Home. */
    fun pauseForBackground() {
        webView.evaluateJavascript(
            "(function(){document.querySelectorAll('video,audio').forEach(function(m){m.pause();});})();",
            null,
        )
        webView.onPause()
        webView.pauseTimers()
    }

    /** Resume the page engine, but deliberately leave media paused for an explicit user action. */
    fun resumeAfterBackground() {
        webView.resumeTimers()
        webView.onResume()
    }

    // ── Injected video controller bridge (pb-video-control.js) ────────────────
    // Commands target the active <video> via the injected controller. In multi-frame
    // mode they're posted to the owning frame's WebMessageListener channel (reaches
    // cross-origin iframes); results come back asynchronously and are delivered to
    // [onVideoResult] as (kind, value). BrowserActivity renders NATIVE feedback from
    // that — a DOM overlay would be hidden behind the video surface in fullscreen.

    /**
     * True when addDocumentStartJavaScript + WebMessageListener are available.
     * A computed getter (not a lazy/backed val) so it's safe to read from
     * setupWebView(), which runs from the init block before field initializers.
     */
    private val multiFrame: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /** Callback for command results: (kind in {toggle,seek,vol,rate}, value). */
    var onVideoResult: ((String, String) -> Unit)? = null

    /** Periodic playback status (a `{"type":"status",…}` JSON string) for the phone scrubber. */
    var onVideoStatus: ((String) -> Unit)? = null

    /** Callback for custom overlays: (visible, text, style, bgColor, textColor, autoDismissMs). */
    var onVideoOverlay: ((Boolean, String, String, String?, String?, Long) -> Unit)? = null

    // Every frame (main + iframes) reporting a screen-dominating video, keyed by its
    // reply proxy. `score` is the absolute on-screen video area (comparable across
    // frames); `playing` and `isMain` feed target selection. Unified pathway: the main
    // frame reports here too now, over the same channel as iframes.
    private data class FrameVideo(val score: Double, val playing: Boolean, val isMain: Boolean)
    private val activeFrames = LinkedHashMap<JavaScriptReplyProxy, FrameVideo>()

    // Target selection. By default the D-pad auto-follows the best frame (see
    // [pickBestProxy]); [stickyProxy] is the current auto pick, kept across reports
    // with hysteresis so a flickering score can't bounce the target. The user can pin
    // a specific frame via [cycleVideoTarget] ("switch source"); [overrideProxy] holds
    // that pin until its frame goes inactive or the user cycles again.
    private var stickyProxy: JavaScriptReplyProxy? = null
    private var overrideProxy: JavaScriptReplyProxy? = null

    /**
     * Drop all video-control state. Called on top-level navigation, where the previous
     * document's frames (and their reply proxies) are destroyed without a final
     * active:false report — leaving them here would strand the D-pad on a dead frame.
     */
    private fun resetVideoControlState() {
        val hadFrames = activeFrames.isNotEmpty() || videoControlActiveFallback
        activeFrames.clear()
        stickyProxy = null
        overrideProxy = null
        videoControlActiveFallback = false
        activeVideoMuted = null
        autoUnmuteDone = false
        // The destroyed frames can't send their own final idle status, so the phone's
        // now-playing strip/scrubber would stay frozen on the old video. Push one idle
        // status here so the phone clears it on navigation.
        if (hadFrames) {
            onVideoStatus?.invoke("""{"type":"status","state":"idle","position":0,"duration":0}""")
        }
    }

    /** Manual override: pin the D-pad to the next active frame in round-robin order. */
    fun cycleVideoTarget() {
        if (activeFrames.isEmpty()) { overrideProxy = null; return }
        val keys = activeFrames.keys.toList()
        val current = overrideProxy ?: currentProxy()
        val nextIndex = (keys.indexOf(current) + 1).mod(keys.size)
        overrideProxy = keys[nextIndex]
    }

    // Main-frame activation flag for the LEGACY path, set via the PBVideoNative bridge
    // (only used when multiFrame is unsupported).
    @Volatile
    private var videoControlActiveFallback = false

    fun isVideoControlActive(): Boolean =
        if (multiFrame) activeFrames.isNotEmpty() else videoControlActiveFallback

    // Last-known muted state of the active/target video, parsed from its status pushes.
    // null = unknown.
    @Volatile
    private var activeVideoMuted: Boolean? = null

    /** null = unknown; true/false = last reported muted state of the controlled video. */
    fun isActiveVideoMuted(): Boolean? = activeVideoMuted

    // Fired (once per active session) when the controlled video is found to be muted, so
    // native can dispatch a REAL tap — the trusted user gesture that actually unmutes
    // (injected JS can't). Driven by status, so it works for BOTH native HTML5 fullscreen
    // AND YouTube-style in-page expand (which never fires onShowCustomView).
    var onRequestUnmuteTap: (() -> Unit)? = null
    @Volatile
    private var autoUnmuteDone = false

    private fun trackMutedFromStatus(json: String) {
        try {
            val o = org.json.JSONObject(json)
            val before = activeVideoMuted
            if (o.has("muted")) activeVideoMuted = o.optBoolean("muted")
            val state = o.optString("state")
            val suppressUnmute = o.optBoolean("suppressUnmute", false)
            if (state == "idle") { activeVideoMuted = null; autoUnmuteDone = false }
            if (activeVideoMuted != before) Log.d(TAG, "activeVideoMuted: $before -> $activeVideoMuted")
            // Auto-unmute ONLY a muted *and actively playing* video, and never while an
            // optional script asks us to hold off (a transient overlay is up — the tap would
            // land on it). That leaves the real autoplay-muted case where a tap is consumed by
            // the "tap to unmute" affordance instead of toggling playback. Once per session.
            if (activeVideoMuted == true && state == "playing" && !suppressUnmute && !autoUnmuteDone) {
                autoUnmuteDone = true
                Log.d(TAG, "auto-unmute: muted+playing video active, requesting native tap")
                onRequestUnmuteTap?.invoke()
            }
        } catch (_: Exception) { /* ignore non-status JSON */ }
    }

    /**
     * Which frame the D-pad drives. A manual pin wins while its frame stays active;
     * otherwise auto-pick by **on-screen size first** (the big player the user is looking
     * at), using "playing" only to break ties between comparably-sized frames. Size-first
     * is deliberate: a tiny autoplaying ad or background clip must NOT steal control from
     * a large player that happens to be paused (the common iframe-embed case). Hysteresis
     * keeps the incumbent unless a challenger is clearly bigger or newly starts playing.
     */
    private fun currentProxy(): JavaScriptReplyProxy? {
        if (activeFrames.isEmpty()) { stickyProxy = null; overrideProxy = null; return null }
        overrideProxy?.let { if (activeFrames.containsKey(it)) return it else overrideProxy = null }
        val incumbent = stickyProxy?.takeIf { activeFrames.containsKey(it) }
        val best = pickBestProxy(incumbent)
        stickyProxy = best
        return best
    }

    private fun pickBestProxy(incumbent: JavaScriptReplyProxy?): JavaScriptReplyProxy? {
        // Largest on-screen video wins by default.
        val largest = activeFrames.entries.maxByOrNull { it.value.score } ?: return incumbent
        // Only let a *playing* frame override the largest one if it's comparably big
        // (≥60% of the top score). This favours real playback without letting a small
        // playing ad outrank a large paused player.
        val best = if (!largest.value.playing) {
            activeFrames.entries
                .filter { it.value.playing && it.value.score >= largest.value.score * 0.6 }
                .maxByOrNull { it.value.score } ?: largest
        } else largest
        if (incumbent != null && best.key !== incumbent) {
            val inc = activeFrames[incumbent]
            // Keep the incumbent unless the challenger is clearly bigger (≥25%) or it has
            // started playing while the incumbent hasn't — avoids thrashing on near-ties.
            val clearlyBigger = inc != null && best.value.score >= inc.score * 1.25
            val newlyPlaying = inc != null && best.value.playing && !inc.playing
            if (inc != null && !clearlyBigger && !newlyPlaying) {
                return incumbent
            }
        }
        return best.key
    }

    fun videoToggle() = sendVideoCommand("toggle")
    fun videoSeek(deltaSeconds: Int) = sendVideoCommand("seek,$deltaSeconds")
    fun videoSeekTo(seconds: Double) = sendVideoCommand("seekto,$seconds")
    fun videoVolume(delta: Double) = sendVideoCommand("vol,$delta")
    fun videoSetRate(rate: Double) = sendVideoCommand("rate,$rate")
    /** JS unmute — only reliable after native has established a user gesture (a real tap). */
    fun videoUnmute() = sendVideoCommand("unmute")

    private fun sendVideoCommand(cmd: String) {
        if (multiFrame) {
            // Unified channel pathway: post to the current target frame (main or iframe);
            // result comes back asynchronously via the message listener -> onVideoResult.
            // Guarded: a frame can be torn down between its last report and this post.
            val proxy = currentProxy() ?: return
            try {
                proxy.postMessage(cmd)
            } catch (e: Exception) {
                Log.w(TAG, "postMessage to a stale frame proxy failed; dropping it", e)
                activeFrames.remove(proxy)
                if (proxy === overrideProxy) overrideProxy = null
                if (proxy === stickyProxy) stickyProxy = null
            }
            return
        }
        // LEGACY fallback (no channel support): evaluateJavascript on the main frame.
        val arg = cmd.substringAfter(',', "")
        val kind: String
        val js: String
        when (cmd.substringBefore(',')) {
            "toggle" -> { kind = "toggle"; js = "(window.__pbvc&&window.__pbvc.toggle())||'none'" }
            "seek" -> { kind = "seek"; js = "(window.__pbvc&&window.__pbvc.seek($arg))||'none'" }
            "seekto" -> { kind = "seek"; js = "(window.__pbvc&&window.__pbvc.seekTo($arg))||'none'" }
            "vol" -> { kind = "vol"; js = "(window.__pbvc&&window.__pbvc.volume($arg))||'none'" }
            "rate" -> { kind = "rate"; js = "(window.__pbvc&&window.__pbvc.setRate($arg))||'none'" }
            else -> return
        }
        webView.evaluateJavascript(js) { raw ->
            val value = raw?.trim()?.removeSurrounding("\"") ?: "none"
            if (value != "none") onVideoResult?.invoke(kind, value)
        }
    }

    /** Parse a frame -> native message (activation change, command result, or status). */
    private fun handleFrameMessage(
        data: String,
        proxy: JavaScriptReplyProxy,
        isMainFrame: Boolean
    ) {
        // Unified pathway: the main frame reports over the channel too now, so we no
        // longer ignore it — every frame is a candidate target.
        try {
            val o = org.json.JSONObject(data)
            when (o.optString("type")) {
                "active" -> {
                    if (o.optBoolean("active")) {
                        activeFrames[proxy] = FrameVideo(
                            score = o.optDouble("score", 0.0),
                            playing = o.optBoolean("playing"),
                            isMain = isMainFrame
                        )
                    } else {
                        activeFrames.remove(proxy)
                        if (proxy === overrideProxy) overrideProxy = null
                        if (proxy === stickyProxy) stickyProxy = null
                    }
                }
                "result" -> {
                    val kind = o.optString("kind")
                    val value = o.optString("value")
                    if (kind != "none" && value != "none") onVideoResult?.invoke(kind, value)
                }
                "overlay" -> {
                    val visible = o.optBoolean("visible", false)
                    val text = o.optString("text", "")
                    val style = o.optString("style", "fullscreen")
                    val bgColor = if (o.has("backgroundColor")) o.optString("backgroundColor") else null
                    val textColor = if (o.has("textColor")) o.optString("textColor") else null
                    val autoDismissMs = o.optLong("autoDismissMs", 0L)
                    onVideoOverlay?.invoke(visible, text, style, bgColor, textColor, autoDismissMs)
                }
                "status" -> {
                    // Forward only the current target frame's status, so a background
                    // frame can't push its scrubber state over the one being controlled.
                    if (proxy === currentProxy()) {
                        trackMutedFromStatus(data)
                        onVideoStatus?.invoke(data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad frame message: $data", e)
        }
    }

    /**
     * JS-exposed bridge object for the LEGACY path only (WebViews without channel
     * support). The script calls these instead of posting to pbVideoChannel. In
     * multiFrame mode the script uses the channel and these are never called.
     */
    private inner class VideoControlBridge {
        @android.webkit.JavascriptInterface
        fun setActive(active: Boolean) {
            videoControlActiveFallback = active
        }

        @android.webkit.JavascriptInterface
        fun onStatus(json: String) {
            // Legacy path is main-frame only, so there's no other target to guard against.
            trackMutedFromStatus(json)
            onVideoStatus?.invoke(json)
        }
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

    /**
     * Scroll the element under the cursor rather than the whole document. Plain
     * [scrollBy] only moves the top-level viewport, so inner `overflow:scroll/auto`
     * regions (sidebars, modals, chat panes, horizontal carousels) never respond to
     * the touchpad. Here we hit-test the cursor position, walk up to the nearest
     * scrollable ancestor, and scroll that; if none is found we fall back to the
     * window. Handles horizontal (dx) and vertical (dy) together.
     *
     * [xDevice]/[yDevice] are cursor coordinates in the WebView's device pixels
     * (same space as touch dispatch); we convert to CSS px via devicePixelRatio in JS.
     * Limitation: cross-origin iframes are unreachable from the main frame's JS — the
     * same boundary that applies to the video-control path.
     */
    fun wheelScrollAt(xDevice: Float, yDevice: Float, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val js = """
            (function(){
              try {
                var dpr = window.devicePixelRatio || 1;
                var x = ${xDevice} / dpr, y = ${yDevice} / dpr;
                var dx = ${dx}, dy = ${dy};
                function scrollable(node){
                  while (node && node.nodeType === 1 &&
                         node !== document.body && node !== document.documentElement){
                    var s = getComputedStyle(node);
                    var canY = (s.overflowY === 'auto' || s.overflowY === 'scroll') &&
                               node.scrollHeight > node.clientHeight + 1;
                    var canX = (s.overflowX === 'auto' || s.overflowX === 'scroll') &&
                               node.scrollWidth > node.clientWidth + 1;
                    if ((dy && canY) || (dx && canX)) return node;
                    node = node.parentElement;
                  }
                  return null;
                }
                var el = document.elementFromPoint(x, y);
                var t = el ? scrollable(el) : null;
                if (t){ if (dx) t.scrollLeft += dx; if (dy) t.scrollTop += dy; }
                else { window.scrollBy(dx, dy); }
              } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * Relative pinch-zoom of the whole page using the WebView's built-in zoom
     * (enabled via setSupportZoom/builtInZoomControls). [factor] is a multiplier
     * applied to the current zoom (e.g. 1.05 = zoom in 5%); clamped per-event so a
     * single jittery gesture can't jump scale.
     */
    fun zoomBy(factor: Float) {
        if (factor.isNaN() || factor <= 0f) return
        val clamped = factor.coerceIn(0.8f, 1.25f)
        webView.zoomBy(clamped)
    }

    fun simulateClick(x: Float, y: Float) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventTime = downTime

        val downEvent = android.view.MotionEvent.obtain(
            downTime, eventTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0
        )
        downEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        val upEvent = android.view.MotionEvent.obtain(
            downTime, eventTime + 100, android.view.MotionEvent.ACTION_UP, x, y, 0
        )
        upEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN

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

    /**
     * The injected video controller (assets/pb-video-control.js). Read once; re-injected
     * on every page load. Idempotent — the script self-guards via window.__pbvcInjected.
     */
    private val videoControlScript: String by lazy {
        try {
            context.assets.open("pb-video-control.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pb-video-control.js", e)
            ""
        }
    }

    /**
     * Read every OPTIONAL user-supplied *.js from the app's external files dir
     * (Android/data/<pkg>/files/). NOT shipped in the APK — these are scripts the user
     * installs themselves (via `adb push`, a file manager, or "Install script" from the
     * phone, which writes here). Lets a user run their own scripts (e.g. a YouTube
     * ad-skipper we deliberately don't distribute). Read fresh each call so installing or
     * removing one takes effect on the next page load. Each script must self-guard against
     * double injection.
     */
    private fun externalUserScripts(): List<String> {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return emptyList()
            dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
                ?.sortedBy { it.name }
                ?.mapNotNull { if (it.canRead()) it.readText() else null }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read external user scripts", e)
            emptyList()
        }
    }

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
                allowFileAccess = false
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // User agent. A literal [userAgentOverride] (picked from the phone's User Agent
                // manager) wins outright. Otherwise, in mobile mode use the WebView's OWN
                // default UA rather than a hardcoded Chrome version: the device sends
                // User-Agent Client Hints (Sec-CH-UA) derived from the real WebView/Chrome
                // version, and Google flags the UA string when its Chrome version disagrees
                // with those hints ("I'm not a robot"). A stale "Chrome/120" string against a
                // newer WebView is exactly that mismatch.
                userAgentString = resolveUserAgent()

                // Optimize for media streaming
                cacheMode = WebSettings.LOAD_DEFAULT
                // databaseEnabled was removed — WebView database is enabled by default on
                // all supported API levels (the field has been a no-op since API 33).

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

            // Video controller transport — ONE unified pathway, chosen by the script via
            // channel presence (not frame type):
            //  • CHANNEL (preferred): a per-frame WebMessageListener (pbVideoChannel),
            //    added when the WebView supports it. Every frame — main + iframes alike —
            //    reports activation/score/status and receives commands over it.
            //  • LEGACY (fallback): on older WebViews without the features, the script
            //    falls back to the PBVideoNative interface + evaluateJavascript on the
            //    main frame only. PBVideoNative is registered solely for that path.
            addJavascriptInterface(VideoControlBridge(), "PBVideoNative")
            if (multiFrame && videoControlScript.isNotEmpty()) {
                WebViewCompat.addWebMessageListener(
                    this, "pbVideoChannel", setOf("*")
                ) { _, message, _, isMainFrame, replyProxy ->
                    message.data?.let { handleFrameMessage(it, replyProxy, isMainFrame) }
                }
                WebViewCompat.addDocumentStartJavaScript(this, videoControlScript, setOf("*"))
            }

            // Handle Downloads via Android DownloadManager
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                Log.d(TAG, "Download requested: $url (mime=$mimeType)")
                try {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        // DownloadManager only handles http(s); blob:/data: can't be enqueued.
                        Log.w(TAG, "Cannot download non-http URL: $url")
                        android.widget.Toast.makeText(context, "This download type isn't supported", android.widget.Toast.LENGTH_SHORT).show()
                        return@setDownloadListener
                    }
                    val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                    request.setMimeType(mimeType)

                    // Extract filename
                    val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                    request.setTitle(fileName)

                    // Forward the cookies, UA and referer the page would use — many hosts 403 a
                    // bare download request without them.
                    android.webkit.CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                    request.addRequestHeader("User-Agent", userAgent)
                    currentUrl?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Referer", it) }

                    request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    // Save to the public Downloads folder so the user can find it in a file manager.
                    request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)

                    val dm = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                    val downloadId = dm.enqueue(request)
                    onDownloadStarted(downloadId, fileName)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to start download", e)
                    android.widget.Toast.makeText(context, "Download failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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
            // A new top-level document tears down all previous frames, but their reply
            // proxies never send a final active:false — so without this the old frames
            // linger in activeFrames, isVideoControlActive() stays stuck true, and the
            // D-pad keeps trying to control a phantom video on the new page. Reset here.
            resetVideoControlState()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)

            // Inject anti-popup script once the new document's JS context is ready.
            // The __pbAdblockInjected guard prevents double-injection on soft navigations.
            view?.evaluateJavascript(antiPopupScript, null)

            // Inject the video controller (window.__pbvc) for phone D-pad playback control.
            // Self-guards via window.__pbvcInjected, so re-injection on soft navs is harmless.
            // Multi-frame mode injects at document start into all frames instead (see setup).
            if (!multiFrame && videoControlScript.isNotEmpty()) {
                view?.evaluateJavascript(videoControlScript, null)
            }

            // Optional, user-supplied scripts (e.g. the YouTube "Continue watching?" guard
            // or an ad-skipper). NOT shipped in the APK — loaded only from the app's
            // external files dir, where the user (or the phone's script manager) puts them.
            // Read fresh each page load so installing/removing one takes effect without a
            // reinstall. Each script self-guards against double injection.
            externalUserScripts().forEach { view?.evaluateJavascript(it, null) }

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

    // Declared last so all property initializers (incl. lazy delegates that
    // setupWebView() reads) are constructed before it runs.
    init {
        setupWebView()
    }
}
