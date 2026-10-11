package com.playbridge.geckoview.plugin

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.playbridge.shared.browser.VideoTargetSelector
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.ScreenLength

class GeckoViewEngine(
    private val context: Context,
    private val desktopMode: Boolean = false,
    private var userAgentOverride: String? = null,
    private val onFullscreen: ((Boolean) -> Unit)? = null,
    private val onLocationChanged: ((String) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "GeckoViewEngine"
    }

    private val geckoView = GeckoView(context)
    private val session = GeckoSession()
    private val runtime = GeckoRuntime.getDefault(context)

    private var _canGoBack: Boolean = false

    private data class FrameKey(val tabId: Int, val frameId: Int)
    private val videoTargets = VideoTargetSelector<FrameKey>()
    private val documents = mutableMapOf<FrameKey, String>()
    private var mutedTarget: FrameKey? = null
    private var activeVideoMuted: Boolean? = null
    private var autoUnmuteDone = false
    private var navigationGeneration = 0
    private val videoMessageListener: (JSONObject) -> Unit = ::handleVideoMessage

    var onVideoResult: ((String, String) -> Unit)? = null
    var onVideoStatus: ((String) -> Unit)? = null
    var onVideoOverlay: ((Boolean, String, String, String?, String?, Long) -> Unit)? = null
    var onRequestUnmuteTap: (() -> Unit)? = null

    fun isVideoControlActive(): Boolean = videoTargets.isActive
    fun isActiveVideoMuted(): Boolean? = activeVideoMuted.takeIf { mutedTarget == videoTargets.current() }
    fun cycleVideoTarget() { videoTargets.cycle() }
    fun videoToggle() = sendVideoCommand("toggle")
    fun videoSeek(deltaSeconds: Int) = sendVideoCommand("seek,$deltaSeconds")
    fun videoSeekTo(seconds: Double) = sendVideoCommand("seekto,$seconds")
    fun videoVolume(delta: Double) = sendVideoCommand("vol,$delta")
    fun videoUnmute() = sendVideoCommand("unmute")

    private fun sendVideoCommand(cmd: String) {
        val target = videoTargets.current() ?: return
        val document = documents[target] ?: return
        PbBridge.videoCommand(target.tabId, target.frameId, document, cmd)
    }

    /** Keep the delayed unmute on the document/frame that received the trusted tap. */
    fun unmuteAfterTap(): (() -> Unit)? {
        val target = videoTargets.current() ?: return null
        val document = documents[target] ?: return null
        val generation = navigationGeneration
        return {
            if (generation == navigationGeneration && videoTargets.current() == target &&
                documents[target] == document) videoUnmute()
        }
    }

    private fun resetVideoControlState() {
        val hadFrames = videoTargets.isActive
        navigationGeneration++
        videoTargets.reset()
        documents.clear()
        mutedTarget = null
        activeVideoMuted = null
        autoUnmuteDone = false
        if (hadFrames) sendIdleVideoStatus()
    }

    private fun sendIdleVideoStatus() {
        onVideoStatus?.invoke("""{"type":"status","state":"idle","position":0,"duration":0}""")
    }

    private fun handleVideoMessage(message: JSONObject) {
        try {
            val tabId = message.getInt("tabId")
            val frameId = message.getInt("frameId")
            if (tabId < 0 || frameId < 0) return
            val key = FrameKey(tabId, frameId)
            val document = message.getString("documentId")
            val data = message.getString("data")
            val o = JSONObject(data)
            when (o.optString("type")) {
                "active" -> {
                    if (o.optBoolean("active")) {
                        if (documents[key] != document) {
                            videoTargets.remove(key)
                            if (mutedTarget == key) { mutedTarget = null; activeVideoMuted = null }
                        }
                        documents[key] = document
                        videoTargets.update(key, o.optDouble("score", 0.0), o.optBoolean("playing"))
                    } else if (documents[key] == document) {
                        videoTargets.remove(key)
                        documents.remove(key)
                        if (!videoTargets.isActive) {
                            mutedTarget = null
                            activeVideoMuted = null
                            autoUnmuteDone = false
                            sendIdleVideoStatus()
                        }
                    }
                }
                "status" -> {
                    if (documents[key] != document || videoTargets.current() != key) return
                    mutedTarget = key
                    if (o.has("muted")) activeVideoMuted = o.optBoolean("muted")
                    val state = o.optString("state")
                    if (state == "idle") { activeVideoMuted = null; autoUnmuteDone = false }
                    if (activeVideoMuted == true && state == "playing" &&
                        !o.optBoolean("suppressUnmute", false) && !autoUnmuteDone) {
                        autoUnmuteDone = true
                        onRequestUnmuteTap?.invoke()
                    }
                    onVideoStatus?.invoke(data)
                }
                "result" -> {
                    if (documents[key] != document || videoTargets.current() != key) return
                    val kind = o.optString("kind")
                    val value = o.optString("value")
                    if (kind != "none" && value != "none") onVideoResult?.invoke(kind, value)
                }
                "overlay" -> {
                    if (documents[key] != document || videoTargets.current() != key) return
                    onVideoOverlay?.invoke(o.optBoolean("visible"), o.optString("text"),
                        o.optString("style", "fullscreen"),
                        if (o.has("backgroundColor")) o.optString("backgroundColor") else null,
                        if (o.has("textColor")) o.optString("textColor") else null,
                        o.optLong("autoDismissMs", 0L))
                }
            }
        } catch (_: Exception) {
            Log.w(TAG, "Invalid video bridge message")
        }
    }

    init {
        runtime.warmUp()
        PbBridge.onVideoMessage = videoMessageListener
        setupGeckoView()
    }

    fun getView(): View = geckoView

    fun loadUrl(url: String) {
        session.loadUri(url)
    }

    fun reload() {
        session.reload()
    }

    /** Live update from the phone's User Agent manager — applies and reloads immediately. */
    fun setUserAgentOverride(value: String?) {
        userAgentOverride = value?.takeIf { it.isNotBlank() }
        session.settings.userAgentOverride = userAgentOverride
        session.reload()
    }

    fun goForward() {
        session.goForward()
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
        if (PbBridge.onVideoMessage === videoMessageListener) PbBridge.onVideoMessage = null
        resetVideoControlState()
        session.close()
    }

    fun pauseForBackground() {
        evaluateJavascript(
            "(function(){document.querySelectorAll('video,audio').forEach(function(m){m.pause();});})();",
            null,
        )
        session.setActive(false)
    }

    fun resumeAfterBackground() {
        session.setActive(true)
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
        downEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        val upEvent = MotionEvent.obtain(
            downTime, eventTime + 100, MotionEvent.ACTION_UP, x, y, 0
        )
        upEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN

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
            // A literal override (picked from the phone's User Agent manager) wins outright,
            // same precedence as the phone app's own in-app browser.
            userAgentOverride = this@GeckoViewEngine.userAgentOverride
        }

        // PB Bridge (built-in) + uBlock Origin (AMO install/update) are handled once at
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
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                // Gecko NavigationDelegate location changes refer to the top-level document.
                resetVideoControlState()
                if (url != null) onLocationChanged?.invoke(url)
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                _canGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            }
        }
    }
}
