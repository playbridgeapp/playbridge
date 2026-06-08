package com.playbridge.sender.cast.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Minimal AVTransport (UPnP) SOAP control client. One instance per renderer
 * control URL.
 */
class AvTransportClient(
    private val controlUrl: String,
    private val http: OkHttpClient,
) {
    data class PositionInfo(val trackDuration: String?, val relTime: String?)

    suspend fun setAvTransportUri(uri: String, metadata: String = "") = action(
        "SetAVTransportURI",
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escape(uri)}</CurrentURI>" +
            "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>",
    )

    suspend fun play() = action("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    suspend fun pause() = action("Pause", "<InstanceID>0</InstanceID>")
    suspend fun stop() = action("Stop", "<InstanceID>0</InstanceID>")
    suspend fun seek(target: String) =
        action("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>")

    suspend fun getPositionInfo(): PositionInfo? {
        val resp = action("GetPositionInfo", "<InstanceID>0</InstanceID>") ?: return null
        return PositionInfo(
            trackDuration = tag(resp, "TrackDuration"),
            relTime = tag(resp, "RelTime"),
        )
    }

    suspend fun getTransportState(): String? {
        val resp = action("GetTransportInfo", "<InstanceID>0</InstanceID>") ?: return null
        return tag(resp, "CurrentTransportState")
    }

    /** POST a SOAP action; returns the response body on HTTP 200, else null (logged). */
    private suspend fun action(name: String, args: String): String? = withContext(Dispatchers.IO) {
        val body = SOAP_HEAD + "<u:$name xmlns:u=\"$SERVICE\">$args</u:$name>" + SOAP_TAIL
        val req = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPAction", "\"$SERVICE#$name\"")
            .post(body.toRequestBody(CONTENT_TYPE))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "$name -> HTTP ${resp.code}: ${text.take(300)}")
                    return@use null
                }
                Log.d(TAG, "$name -> 200")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "$name failed", e)
            null
        }
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** UPnP action responses return arguments as unprefixed child elements. */
    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "AvTransportClient"
        private const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        private val CONTENT_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
        private const val SOAP_HEAD =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
        private const val SOAP_TAIL = "</s:Body></s:Envelope>"
    }
}
