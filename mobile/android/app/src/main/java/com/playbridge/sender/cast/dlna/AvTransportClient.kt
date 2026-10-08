package com.playbridge.sender.cast.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal fun dlnaActionFailureMessage(
    actionName: String,
    httpStatus: Int,
    upnpCode: String?,
    upnpDescription: String?,
): String = buildString {
    append("DLNA $actionName failed (HTTP $httpStatus, UPnP ${upnpCode ?: "unknown"}")
    upnpDescription?.takeIf(String::isNotBlank)?.let { description ->
        val sanitized = description.replace(URL_PATTERN, "[URL redacted]").trim()
        val limited = if (sanitized.length > MAX_DESCRIPTION_CHARS) {
            sanitized.take(MAX_DESCRIPTION_CHARS - 3) + "..."
        } else {
            sanitized
        }
        if (limited.isNotEmpty()) append(": ").append(limited)
    }
    append(')')
}

private val URL_PATTERN = Regex("https?://[^\\s<]+", RegexOption.IGNORE_CASE)
private const val MAX_DESCRIPTION_CHARS = 120

internal class DlnaActionFailure(
    val actionName: String,
    val httpStatus: Int,
    val upnpCode: String?,
    val upnpDescription: String? = null,
    private val responseText: String? = null,
) : IOException(dlnaActionFailureMessage(actionName, httpStatus, upnpCode, upnpDescription)) {
    internal val responseIndicatesTransitionUnavailable: Boolean
        get() = shouldRetrySetAvTransportAfterStop(upnpCode, upnpDescription) ||
            responseText?.contains("transition not available", ignoreCase = true) == true
}

/**
 * Minimal AVTransport (UPnP) SOAP control client. One instance per renderer control URL.
 * SOAP actions are executed off the caller thread and preserve parsed UPnP faults for callers.
 */
class AvTransportClient(
    internal val controlUrl: String,
    private val http: OkHttpClient,
) {
    data class PositionInfo(val trackDuration: String?, val relTime: String?)

    suspend fun setAvTransportUri(uri: String, metadata: String = "") {
        val args = "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${escape(uri)}</CurrentURI>" +
            "<CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>"
        try {
            requiredAction("SetAVTransportURI", args, transportRetries = 1)
        } catch (error: DlnaActionFailure) {
            if (!error.responseIndicatesTransitionUnavailable) throw error
            stop()
            requiredAction("SetAVTransportURI", args, transportRetries = 1)
        }
    }

    suspend fun play() = requiredAction("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    suspend fun pause() = requiredAction("Pause", "<InstanceID>0</InstanceID>")
    suspend fun stop() = requiredAction("Stop", "<InstanceID>0</InstanceID>")
    suspend fun seek(target: String) = requiredAction(
        "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>",
    )

    suspend fun getPositionInfo(): PositionInfo? {
        val resp = action("GetPositionInfo", "<InstanceID>0</InstanceID>") ?: return null
        return PositionInfo(tag(resp, "TrackDuration"), tag(resp, "RelTime"))
    }

    suspend fun getTransportState(): String? {
        val resp = action("GetTransportInfo", "<InstanceID>0</InstanceID>") ?: return null
        return tag(resp, "CurrentTransportState")
    }

    /** Total duration via GetMediaInfo — renderers often report it here when GetPositionInfo's is 0. */
    suspend fun getMediaDuration(): String? {
        val resp = action("GetMediaInfo", "<InstanceID>0</InstanceID>") ?: return null
        return tag(resp, "MediaDuration")
    }

    private suspend fun requiredAction(name: String, args: String, transportRetries: Int = 0) {
        if (action(name, args, required = true, transportRetries = transportRetries) == null) {
            throw IOException("DLNA $name failed")
        }
    }

    /** POST a SOAP action, retrying one transport failure when requested and falling back to M-POST on 405. */
    private suspend fun action(
        name: String,
        args: String,
        required: Boolean = false,
        transportRetries: Int = 0,
    ): String? = withContext(Dispatchers.IO) {
        val soapAction = "\"$SERVICE#$name\""
        val body = SOAP_HEAD + "<u:$name xmlns:u=\"$SERVICE\">$args</u:$name>" + SOAP_TAIL
        var lastIo: IOException? = null
        repeat(transportRetries + 1) { attempt ->
            try {
                val result = post(body, soapAction)
                if (result.status !in 200..299) {
                    val failure = DlnaActionFailure(
                        name,
                        result.status,
                        upnpErrorCode(result.body),
                        upnpErrorDescription(result.body),
                        result.body,
                    )
                    Log.w(TAG, "$name -> HTTP ${result.status}, UPnP error=${failure.upnpCode ?: "unknown"}")
                    if (required) throw failure
                    return@withContext null
                }
                Log.d(TAG, "$name -> ${result.status}")
                return@withContext result.body
            } catch (error: DlnaActionFailure) {
                throw error
            } catch (error: IOException) {
                lastIo = error
                if (attempt < transportRetries) {
                    Log.w(TAG, "$name transport failed; retrying once (${error.javaClass.simpleName})")
                }
            } catch (error: Exception) {
                Log.e(TAG, "$name failed", error)
                if (required) throw IOException("DLNA $name failed", error)
                return@withContext null
            }
        }
        val failure = lastIo ?: IOException("DLNA $name failed")
        if (required) throw failure
        Log.w(TAG, "$name transport failed: ${failure.javaClass.simpleName}")
        null
    }

    private fun post(body: String, soapAction: String): SoapResponse {
        fun request(method: String, headers: Map<String, String>): SoapResponse {
            val builder = Request.Builder().url(controlUrl)
                .header("Content-Type", CONTENT_TYPE.toString())
            headers.forEach { (name, value) -> builder.header(name, value) }
            val request = builder.method(method, body.toRequestBody(CONTENT_TYPE)).build()
            http.newCall(request).execute().use { response ->
                return SoapResponse(response.code, response.body?.string().orEmpty())
            }
        }
        val result = request("POST", mapOf("SOAPAction" to soapAction))
        return if (result.status == 405) request("M-POST", dlnaMpostHeaders(soapAction)) else result
    }

    private data class SoapResponse(val status: Int, val body: String)

    private fun escape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** UPnP action responses may prefix their argument tags, unlike many fault elements. */
    private fun tag(xml: String, name: String): String? =
        Regex("<(?:[\\w.-]+:)?$name\\b[^>]*>(.*?)</(?:[\\w.-]+:)?$name\\s*>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)

    companion object {
        /** Extract only the numeric UPnP code; fault descriptions may contain authenticated media URLs. */
        internal fun upnpErrorCode(body: String): String? =
            Regex("<(?:[\\w.-]+:)?errorCode\\b[^>]*>\\s*(\\d+)\\s*</(?:[\\w.-]+:)?errorCode\\s*>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)

        internal fun upnpErrorDescription(body: String): String? =
            Regex("<(?:[\\w.-]+:)?errorDescription\\b[^>]*>(.*?)</(?:[\\w.-]+:)?errorDescription\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.get(1)?.replace("&amp;", "&")?.trim()?.takeIf(String::isNotEmpty)

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
