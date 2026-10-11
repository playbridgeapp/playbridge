package com.playbridge.geckoview.plugin

import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * Process-global bridge to the `pb_bridge` WebExtension, used to evaluate JS in the active page
 * (text injection, fullscreen/maximize, etc.).
 *
 * The message delegate MUST be registered on the extension where it is first ensured (the
 * Application), before the extension's background script calls `connectNative`. If no delegate is
 * registered for the native app name, GeckoView falls back to looking up a native-messaging
 * manifest — which Android doesn't support ("Native manifests are not supported on android"),
 * so the bridge never connects.
 *
 * The port belongs to the runtime, not any one GeckoSession, so it survives browser Activity
 * recreation; evals always target whatever tab is currently active.
 */
object PbBridge {
    private const val TAG = "PbBridge"
    private var port: WebExtension.Port? = null
    private val pending = ArrayDeque<String>()

    // One active engine owns video delivery. Detach by identity on destroy so Activity
    // recreation cannot retain an old engine or clear a replacement engine's listener.
    var onVideoMessage: ((JSONObject) -> Unit)? = null

    /** Commands are never queued: a disconnected frame/document must not receive a stale action. */
    fun videoCommand(tabId: Int, frameId: Int, documentId: String, cmd: String) {
        val p = port ?: return
        try {
            p.postMessage(JSONObject().put("type", "video_cmd").put("tabId", tabId)
                .put("frameId", frameId).put("documentId", documentId).put("cmd", cmd))
        } catch (_: Exception) {
            Log.w(TAG, "video command delivery failed")
        }
    }

    /** Register on the extension returned by `ensureBuiltIn`. Idempotent. */
    fun register(ext: WebExtension) {
        ext.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onConnect(p: WebExtension.Port) {
                    Log.i(TAG, "bridge port connected")
                    port = p
                    p.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message as? JSONObject ?: return
                            if (json.optString("type") == "video") onVideoMessage?.invoke(json)
                        }
                        override fun onDisconnect(disconnected: WebExtension.Port) {
                            if (port === disconnected) port = null
                        }
                    })
                    while (pending.isNotEmpty()) post(p, pending.removeFirst())
                }
            },
            "pbBridge"
        )
        Log.d(TAG, "bridge delegate registered")
    }

    /** Evaluate [script] in the active page; queued if the bridge isn't connected yet. */
    fun eval(script: String) {
        val p = port
        if (p != null) {
            post(p, script)
        } else {
            Log.w(TAG, "bridge not connected; queueing eval (${pending.size + 1} pending)")
            pending.addLast(script)
            while (pending.size > 20) pending.removeFirst()
        }
    }

    private fun post(p: WebExtension.Port, script: String) {
        try {
            p.postMessage(JSONObject().put("type", "eval").put("code", script))
        } catch (e: Exception) {
            Log.e(TAG, "eval post error", e)
        }
    }
}
