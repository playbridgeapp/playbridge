package com.playbridge.sender.browser

import com.playbridge.sender.BuildConfig
import com.playbridge.sender.data.nuvio.NuvioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/** The privileged endpoint exists only in FOSS, and is bound to a real document. */
internal object NativePluginBridge {
    private val documentChecks = mutableMapOf<WebExtension.Port, () -> Unit>()

    fun invalidateUnauthorized() {
        documentChecks.values.toList().forEach { it() }
    }

    fun bind(session: GeckoSession, extension: WebExtension, tabId: String) {
        session.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val requests = mutableMapOf<String, Pair<String, Job>>()
                val lifetime = DocumentPortLifetime {
                    documentChecks.remove(port)
                    scope.cancel()
                    requests.clear()
                }

                fun authorized(): Boolean = NativePluginBridgePolicy.authorized(
                    senderUrl = port.sender.url,
                    currentUrl = Components.nativePluginDocumentUrl(tabId, session),
                    installedAppOrigin = Components.nativePluginAppOrigin(tabId),
                    topLevel = port.sender.isTopLevel,
                    matchingSession = port.sender.session === session,
                    contentScript = port.sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT,
                    debugBuild = BuildConfig.DEBUG,
                )

                fun reply(id: String, data: JSONObject? = null, error: String? = null) {
                    if (lifetime.closed) return
                    val permitted = authorized()
                    runCatching { port.postMessage(JSONObject().apply {
                        put("type", "plugin_response")
                        put("requestId", id)
                        put("ok", permitted && error == null)
                        if (!permitted) put("error", "origin_not_approved")
                        else if (error != null) put("error", error)
                        else put("data", data ?: JSONObject())
                    }) }
                }

                documentChecks[port] = {
                    if (!authorized() && lifetime.close()) {
                        // Revoke authority/cancel work immediately, but do not race
                        // Gecko's document teardown with a second native shutdown.
                        runCatching { port.postMessage(JSONObject().put("type", "plugin_disconnected")) }
                    }
                }

                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onDisconnect(port: WebExtension.Port) {
                        lifetime.close()
                    }

                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        if (lifetime.closed) return
                        val raw = message.toString()
                        if (raw.toByteArray(Charsets.UTF_8).size > NativePluginBridgePolicy.MAX_REQUEST_BYTES) return
                        val request = runCatching { JSONObject(raw) }.getOrNull() ?: return
                        val operation = request.opt("operation") as? String ?: return
                        if (operation == "cancel") {
                            if (request.length() == 1) requests.toMap().forEach { (id, pending) ->
                                if (pending.first == "resolve") {
                                    requests.remove(id)
                                    pending.second.cancel()
                                }
                            }
                            return
                        }
                        val id = request.opt("requestId") as? String ?: return
                        if (id.length !in 1..128 || id.any { it.isISOControl() } || requests.containsKey(id)) return
                        if (!authorized()) {
                            // Ordinary browser tabs may still use browser-compatible plugins.
                            // Do not disclose installed providers outside an installed app.
                            if (operation == "status") runCatching { port.postMessage(JSONObject().apply {
                                put("type", "plugin_response")
                                put("requestId", id)
                                put("ok", true)
                                put("data", JSONObject().put("available", false).put("enabled", false).put("providers", JSONArray()))
                            }) }
                            else reply(id, error = "origin_not_approved")
                            return
                        }
                        if (requests.size >= NativePluginBridgePolicy.MAX_PENDING_REQUESTS) { reply(id, error = "resource_limit"); return }
                        if (operation !in setOf("status", "resolve", "manage")) { reply(id, error = "invalid_request"); return }
                        if (request.keys().asSequence().any { it !in setOf("requestId", "operation", "payload") }) {
                            reply(id, error = "invalid_request"); return
                        }
                        val payload = request.optJSONObject("payload")
                        if (payload == null || (operation != "resolve" && payload.length() != 0)) {
                            reply(id, error = "invalid_request"); return
                        }
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                val repository = GlobalContext.get().get<NuvioRepository>()
                                val data = withTimeout(60_000) {
                                    when (operation) {
                                        "status" -> repository.nativePluginStatus().let { status -> JSONObject().apply {
                                            put("available", status.available)
                                            put("enabled", status.enabled)
                                            put("providers", JSONArray().apply {
                                                status.providers.forEach { provider -> put(JSONObject().apply {
                                                    put("repoUrl", provider.repoUrl)
                                                    put("scraperId", provider.scraperId)
                                                    put("name", provider.name)
                                                    put("enabled", provider.enabled)
                                                    put("requiresApproval", provider.requiresApproval)
                                                }) }
                                            })
                                        } }
                                        "manage" -> {
                                            if (Components.store.state.selectedTabId != tabId ||
                                                Components.activeBridgedAppTabId != tabId ||
                                                Components.onNativePluginManagerRequested == null) {
                                                reply(id, error = "app_not_active")
                                                return@withTimeout null
                                            }
                                            JSONObject().put("opened", Components.onNativePluginManagerRequested?.invoke(tabId) == true)
                                        }
                                        else -> {
                                            if (payload.keys().asSequence().any { it !in setOf("repoUrl", "scraperIds", "tmdbId", "mediaType", "season", "episode") }) {
                                                reply(id, error = "invalid_request"); return@withTimeout null
                                            }
                                            val repoUrl = payload.opt("repoUrl") as? String ?: ""
                                            val ids = payload.optJSONArray("scraperIds")?.let { array ->
                                                (0 until array.length()).mapNotNull { array.opt(it) as? String }
                                                    .takeIf { it.size == array.length() }
                                            } ?: emptyList()
                                            val tmdbId = payload.opt("tmdbId") as? String ?: ""
                                            val mediaType = payload.opt("mediaType") as? String ?: ""
                                            fun integer(key: String): Int? = when (val value = payload.opt(key)) {
                                                null, JSONObject.NULL -> null
                                                is Int -> value
                                                else -> null
                                            }
                                            val season = integer("season")
                                            val episode = integer("episode")
                                            if (!NativePluginBridgePolicy.validResolveRequest(repoUrl, ids, tmdbId, mediaType, season, episode) ||
                                                (payload.has("season") && !payload.isNull("season") && season == null) ||
                                                (payload.has("episode") && !payload.isNull("episode") && episode == null)) {
                                                reply(id, error = "invalid_request"); return@withTimeout null
                                            }
                                            val result = repository.resolveNativePlugins(repoUrl, ids, tmdbId, mediaType, season, episode)
                                            JSONObject().apply {
                                                put("warnings", JSONArray(result.warnings))
                                                put("streams", JSONArray().apply {
                                                    result.streams.forEach { stream -> put(JSONObject().apply {
                                                        put("addonName", stream.addonName)
                                                        put("addonUrl", stream.addonUrl)
                                                        put("url", stream.url)
                                                        put("name", stream.name)
                                                        put("title", stream.title)
                                                        stream.headers?.let { put("headers", JSONObject(it)) }
                                                    }) }
                                                })
                                            }
                                        }
                                    }
                                }
                                if (data != null) reply(id, data)
                            } catch (_: CancellationException) {
                                reply(id, error = "cancelled")
                            } catch (_: Exception) {
                                // Never return exception messages containing URLs or credentials.
                                reply(id, error = "plugin_resolution_failed")
                            } finally {
                                if (requests[id]?.second === coroutineContext[Job]) requests.remove(id)
                            }
                        }
                        requests[id] = operation to job
                        job.start()
                    }
                })
                runCatching { port.postMessage(JSONObject().put("type", "plugin_capabilities").put("available", authorized())) }
            }
        }, "plugins")
    }
}
