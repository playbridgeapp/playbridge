package com.playbridge.sender.cast.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Best-effort ConnectionManager compatibility check. Any inability to inspect Sink is permissive. */
internal object DlnaProtocolInfo {
    private const val SERVICE = "urn:schemas-upnp-org:service:ConnectionManager:1"
    private const val SOAP = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:GetProtocolInfo xmlns:u=\"$SERVICE\"></u:GetProtocolInfo></s:Body></s:Envelope>"
    private val contentType = "text/xml; charset=\"utf-8\"".toMediaType()

    suspend fun preflight(avTransportUrl: String, mimeType: String) = withContext(Dispatchers.IO) {
        if (!shouldPreflightDlnaMime(mimeType)) return@withContext
        val controlUrl = DeviceDescription.connectionManagerUrl(avTransportUrl) ?: return@withContext
        val sink = runCatching { fetchSink(controlUrl) }.getOrNull() ?: return@withContext
        if (protocolInfoAllows(sink, mimeType) == false) {
            throw IOException("Renderer does not support MIME type $mimeType")
        }
    }

    private fun fetchSink(controlUrl: String): String? {
        val action = "\"$SERVICE#GetProtocolInfo\""
        fun send(method: String, headers: Map<String, String>): Pair<Int, String> {
            val builder = Request.Builder().url(controlUrl)
                .header("Content-Type", contentType.toString())
            headers.forEach { (name, value) -> builder.header(name, value) }
            val request = builder.method(method, SOAP.toRequestBody(contentType)).build()
            DlnaProxyHolder.httpClient.newCall(request).execute().use { response ->
                return response.code to response.body?.string().orEmpty()
            }
        }
        val (status, body) = send("POST", mapOf("SOAPAction" to action)).let { first ->
            if (first.first == 405) send("M-POST", dlnaMpostHeaders(action)) else first
        }
        if (status !in 200..299) return null
        return Regex("<(?:[\\w.-]+:)?Sink\\b[^>]*>(.*?)</(?:[\\w.-]+:)?Sink\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.trim()
    }
}
