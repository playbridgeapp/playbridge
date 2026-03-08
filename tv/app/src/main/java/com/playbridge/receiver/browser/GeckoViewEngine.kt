package com.playbridge.receiver.browser

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
            } catch (e: Exception) {
                Log.e(TAG, "Bridge eval error", e)
            }
        } else {
            // Fallback: javascript: URI with double-quote wrapper
            // Key: use " instead of ' for the decodeURIComponent string delimiter,
            // and force-encode both ' and " to prevent breaking the wrapper.
            try {
                val clean = script.trim()
                val encoded = java.net.URLEncoder.encode(clean, "UTF-8")
                    .replace("+", "%20")
                    .replace("'", "%27")
                    .replace("\"", "%22")
                session.loadUri("javascript:void(eval(decodeURIComponent(%22$encoded%22)))")
            } catch (e: Exception) {
                Log.e(TAG, "JS fallback error", e)
            }
        }
        callback?.invoke(null)
    }

    override fun destroy() {
        bridgePort = null
        session.close()
    }

    override fun scrollBy(dx: Int, dy: Int) {
        evaluateJavascript("window.scrollBy($dx, $dy)", null)
    }

    override fun simulateClick(x: Float, y: Float) {
        // Find DOM element at exact coordinates and invoke click() programmatically
        // Note: x/y coordinates need to account for device pixel ratio
        val script = """
            (function() {
                var dpr = window.devicePixelRatio || 1;
                var el = document.elementFromPoint($x / dpr, $y / dpr);
                if (el) {
                    el.click();
                }
            })();
        """.trimIndent()
        evaluateJavascript(script, null)
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
        
        // Install PB Bridge extension for native JS evaluation
        // First, try to register delegate for already-installed extension (handles app restart)
        runtime.webExtensionController.list().accept({ extensions ->
            val bridge = extensions?.find { it.id == "pb-bridge@playbridge.com" }
            if (bridge != null) {
                Log.i(TAG, "PB Bridge already installed, registering delegate")
                registerBridgeDelegate(bridge)
            }
        }, { e -> Log.w(TAG, "Failed to list extensions", e) })
        
        // Then, ensure it's installed (handles first run + updates)
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/extensions/pb_bridge/",
            "pb-bridge@playbridge.com"
        ).accept(
            { ext ->
                Log.i(TAG, "PB Bridge extension ensured")
                registerBridgeDelegate(ext!!)
            },
            { e -> Log.e(TAG, "Failed to install PB Bridge extension", e) }
        )
        
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                onFullscreen?.invoke(fullScreen)
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
