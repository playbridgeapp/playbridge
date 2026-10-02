package com.playbridge.sender.browser

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/** Play builds have no native plugin execution or plugin repository dependency. */
internal object NativePluginBridge {
    fun invalidateUnauthorized() = Unit
    fun bind(session: GeckoSession, extension: WebExtension, tabId: String) {
        session.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        val raw = message.toString()
                        if (raw.toByteArray(Charsets.UTF_8).size > NativePluginBridgePolicy.MAX_REQUEST_BYTES) return
                        val request = runCatching { JSONObject(raw) }.getOrNull() ?: return
                        val id = request.opt("requestId") as? String ?: return
                        if (id.length !in 1..128) return
                        val operation = request.opt("operation") as? String
                        runCatching { port.postMessage(JSONObject().apply {
                            put("type", "plugin_response")
                            put("requestId", id)
                            put("ok", operation == "status")
                            if (operation == "status") put("data", JSONObject().apply {
                                put("available", false)
                                put("enabled", false)
                                put("providers", JSONArray())
                            }) else put("error", "native_plugins_unavailable")
                        }) }
                    }
                })
                runCatching { port.postMessage(JSONObject().put("type", "plugin_capabilities").put("available", false)) }
            }
        }, "plugins")
    }
}
