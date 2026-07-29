package com.playbridge.sender.logging

import android.util.Log
import com.playbridge.sender.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Full sender payload diagnostics for debug logcat only.
 *
 * This deliberately ignores authentication and pairing envelopes. Only media/browser
 * command URLs and their replay headers are emitted.
 */
object DebugNetworkLogger {
    /**
     * Raw network diagnostics must remain available only through developer logcat access.
     * [com.playbridge.sender.diagnostics.LogcatReader] excludes this tag from the in-app
     * diagnostics viewer and its persisted share attachments.
     */
    const val LOGCAT_TAG = "PBDebugNetworkRaw"

    fun urlAndHeaders(
        tag: String,
        label: String,
        url: String,
        headers: Map<String, String>?,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(LOGCAT_TAG, "[$tag] $label URL: $url")
        val fields = headers.orEmpty()
        Log.d(LOGCAT_TAG, "[$tag] $label headers (${fields.size}):")
        fields.forEach { (name, value) ->
            Log.d(LOGCAT_TAG, "[$tag] $label $name: $value")
        }
    }

    fun command(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val envelope = Json.parseToJsonElement(message) as? JsonObject ?: return
            val action = envelope["action"]?.jsonPrimitive?.contentOrNull ?: return
            val payload = envelope["payload"] as? JsonObject ?: return
            when (action) {
                "playlist" -> (payload["items"] as? JsonArray).orEmpty()
                    .forEachIndexed { index, item ->
                        logItem(tag, "Outbound playlist item $index", item as? JsonObject)
                    }
                "queue_add" -> logItem(
                    tag,
                    "Outbound queue item",
                    payload["item"] as? JsonObject,
                )
                "browser" -> payload["url"]?.jsonPrimitive?.contentOrNull?.let { url ->
                    urlAndHeaders(tag, "Outbound browser command", url, null)
                }
            }
        }.onFailure {
            Log.d(
                LOGCAT_TAG,
                "[$tag] Unable to decode outbound media command for debug logging: ${it.message}",
            )
        }
    }

    private fun logItem(tag: String, label: String, item: JsonObject?) {
        val value = item ?: return
        val url = value["url"]?.jsonPrimitive?.contentOrNull ?: return
        val headers = (value["headers"] as? JsonObject)
            ?.mapValues { (_, field) -> field.jsonPrimitive.contentOrNull.orEmpty() }
        urlAndHeaders(tag, label, url, headers)
    }
}
